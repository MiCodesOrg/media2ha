# SPEC — Media2HA

> Spec d'implémentation du fork **Media2HA** (`fr.micodes.media2ha`) : relais média Android ↔ Home Assistant via MQTT, exposant un `media_player` complet. Android 6+.
>
> Carte d'origine : [Wayfinder: Media2HA](https://github.com/MiCodesOrg/media2ha/issues/1). Chaque décision ci-dessous vit dans son ticket ; les liens pointent vers le détail et la justification.

## 1. Objectif

Fork de [`saihgupr/android_relay`](https://github.com/saihgupr/android_relay), approprié sous la racine `fr.micodes`, pour :

- être compatible **Android 6+** (`minSdk 23`) ;
- publier un **`media_player` Home Assistant complet** (état, infos média, artwork) via MQTT **discovery** ;
- **recevoir** les commandes HA sur MQTT (play, pause, next, previous, volume) ;
- rester **simple, léger et performant**.

Home Assistant ne supporte **pas nativement** de `media_player` MQTT. L'entité est fournie par l'intégration custom HACS [`bkbilly/mqtt_media_player`](https://github.com/bkbilly/mqtt_media_player). Référence : [issue #2](https://github.com/MiCodesOrg/media2ha/issues/2).

## 2. Identité & plateforme

| Élément | Valeur |
|---|---|
| applicationId / namespace | `fr.micodes.media2ha` |
| Nom affiché | `Media2HA` |
| `minSdk` / `targetSdk` | `23` / `34` |
| UI | `appcompat` existant ; **pas de Material3/Compose** |

Package Kotlin sous `fr.micodes.media2ha`. Le rebrand remplace l'applicationId d'origine (rupture nette, pas de mise à jour par-dessus l'APK d'origine).

## 3. Connexion MQTT

- Client : **Eclipse Paho `org.eclipse.paho.client.mqttv3:1.2.5` nu**. Retirer `org.eclipse.paho.android.service` (déprécié). Référence : [issue #4](https://github.com/MiCodesOrg/media2ha/issues/4), [issue #9](https://github.com/MiCodesOrg/media2ha/issues/9).
- **Temps réel** : socket TCP permanent, `messageArrived` livré immédiatement (pas de WorkManager, contrairement à hannesa2).
- Propriétaire : le `NotificationListenerService` existant (system-bound, re-lié au boot) + un **foreground service** léger (API 26+, `foregroundServiceType="connectedDevice"`) pour garantir la survie du process.
- `setAutomaticReconnect(true)` ; sur `MqttCallbackExtended.connectComplete` : **resubscribe** au topic de commande, **republier** la config discovery et l'état, publier `online`.
- `ConnectivityManager.NetworkCallback` → `reconnect()` au retour du réseau.
- LWT sur `media2ha/<device_id>/availability` = `offline` ; `cleanSession=false`, QoS 1.

## 4. Découverte & topics

Référence : [issue #8](https://github.com/MiCodesOrg/media2ha/issues/8).

### Identité
- Topic discovery : `homeassistant/media_player/<device_id>/config`, payload JSON **retained**. Préfixe `homeassistant` imposé par le composant.
- `device_id` : **un seul segment** `[a-z0-9_]`, sert d'`unique_id` HA. Défaut = slug du nom d'appareil + court suffixe stable dérivé du matériel (ex. `tv_salon_a1b2`), **prérempli et éditable**.
- Le `name` du payload est le nom affiché (`Build.MODEL` par défaut).

### Topics runtime — namespace `media2ha/<device_id>`

| Rôle | Topic | Retained |
|---|---|---|
| Disponibilité (LWT) | `media2ha/<device_id>/availability` | oui |
| État | `media2ha/<device_id>/state` | oui |
| Titre / Artiste / Album | `.../title`, `.../artist`, `.../album` | oui |
| Type média | `.../mediatype` | oui |
| Durée | `.../duration` | oui |
| Position | `.../position` | non |
| Volume | `.../volume` (0.0–1.0) | oui |
| Album art | `.../albumart` (base64 JPEG) | non |
| Commandes | `.../cmd/{play,pause,playpause,next,previous}` | — |
| Volume (cmd) | `.../cmd/volume` (float 0.0–1.0) | — |

### Payload de découverte (exemple)

```json
{
  "name": "<nom affiché>",
  "availability": {
    "topic": "media2ha/<device_id>/availability",
    "payload_available": "online",
    "payload_not_available": "offline"
  },
  "state_state_topic": "media2ha/<device_id>/state",
  "state_title_topic": "media2ha/<device_id>/title",
  "state_artist_topic": "media2ha/<device_id>/artist",
  "state_album_topic": "media2ha/<device_id>/album",
  "state_duration_topic": "media2ha/<device_id>/duration",
  "state_position_topic": "media2ha/<device_id>/position",
  "state_volume_topic": "media2ha/<device_id>/volume",
  "state_albumart_topic": "media2ha/<device_id>/albumart",
  "state_mediatype_topic": "media2ha/<device_id>/mediatype",
  "command_volume_topic": "media2ha/<device_id>/cmd/volume",
  "command_play_topic": "media2ha/<device_id>/cmd/play",
  "command_play_payload": "play",
  "command_pause_topic": "media2ha/<device_id>/cmd/pause",
  "command_pause_payload": "pause",
  "command_playpause_topic": "media2ha/<device_id>/cmd/playpause",
  "command_playpause_payload": "playpause",
  "command_next_topic": "media2ha/<device_id>/cmd/next",
  "command_next_payload": "next",
  "command_previous_topic": "media2ha/<device_id>/cmd/previous",
  "command_previous_payload": "previous",
  "device": {
    "identifiers": ["<device_id>"],
    "name": "<nom affiché>",
    "manufacturer": "micodes",
    "model": "<Build.MODEL>",
    "sw_version": "<versionName>"
  }
}
```

`origin` non supporté → omis. Un payload **vide retained** sur le topic discovery efface la config retenue. **Le composant ne supprime pas pour autant l'entité déjà créée** : elle reste présente (marquée indisponible) et doit être retirée dans l'UI Home Assistant.

## 5. Modèle d'état

Référence : [issue #7](https://github.com/MiCodesOrg/media2ha/issues/7). Une seule entité = la **session active** (celle qui joue, sinon la plus récemment mise à jour). Les sessions fantômes sont dédupliquées par package.

| `PlaybackState` Android | État HA |
|---|---|
| `STATE_PLAYING` | `playing` |
| `STATE_PAUSED` | `paused` |
| `STATE_BUFFERING` / `CONNECTING` | `playing` |
| `STATE_STOPPED` | `stopped` |
| `STATE_FAST_FORWARDING` / `REWINDING` / `SKIPPING_*` | `playing` |
| `STATE_NONE` / `STATE_ERROR` | `idle` |

- **Jamais `off`** ; sans session active → `idle` + métadonnées effacées. `unavailable` vient du LWT.
- Champs : `title`, `artist`, `album`, `duration` (int s), `position` (int s), `mediatype` (`music`/`video`), `volume` (0.0–1.0). L'app/source n'est pas exposable par le composant.
- Position : publiée aux changements (play/pause/seek/piste) + resync toutes les 30 s en lecture ; non retained.
- Valeurs absentes → payload vide (`duration == 0`/inconnue → vide).

## 6. Commandes

Référence : [issue #10](https://github.com/MiCodesOrg/media2ha/issues/10). L'app s'abonne à `media2ha/<device_id>/cmd/+` et dispatche par suffixe.

| Commande | Action Android | Garde |
|---|---|---|
| `play` | `transportControls.play()` | `ACTION_PLAY` |
| `pause` | `transportControls.pause()` | `ACTION_PAUSE` |
| `playpause` | toggle play/pause | géré si reçu |
| `next` | `skipToNext()` | `ACTION_SKIP_TO_NEXT` |
| `previous` | `skipToPrevious()` | `ACTION_SKIP_TO_PREVIOUS` |
| `volume` | session absolue si `maxVolume > 0`, sinon `AudioManager` `STREAM_MUSIC` | — |

- Chaque action vérifie `PlaybackState.getActions()` ; échec silencieux + log, sans modifier l'état publié.
- `volume` : payload borné `[0.0, 1.0]`. Si la session expose une échelle absolue (`VOLUME_CONTROL_ABSOLUTE` avec `maxVolume > 0`) → `setVolumeTo` ; sinon (cas fréquent des apps vidéo locales : `ABSOLUTE` avec `maxVolume = 0`) → repli sur le volume système `STREAM_MUSIC` via `AudioManager`.
- Le niveau publié suit la même priorité ; un `ContentObserver` sur `Settings.System` republie le volume après un changement externe (télécommande).
- **`command_playpause_topic` n'est jamais émis par le composant** : configurer `play` et `pause` séparément ; `playpause` reste forward-compat.

## 7. Artwork

Référence : [issue #6](https://github.com/MiCodesOrg/media2ha/issues/6). Base64 JPEG sur `.../albumart`, non retained.

- Source : `METADATA_KEY_ALBUM_ART` → repli `METADATA_KEY_ART`.
- Encodage : 512 px max (plus grand côté), JPEG qualité 80, sur thread de fond.
- Publication au changement (hash) + à la connexion ; payload vide si absent ; pas d'icône d'app.

## 8. Configuration

Référence : [issue #11](https://github.com/MiCodesOrg/media2ha/issues/11). Champs uniquement : **hôte**, **port** (défaut `1883`), **auth** (+ user/pass), **nom d'appareil**, **`device_id`** (visible, validé `[a-z0-9_]`).

- Hôte **vide par défaut** — saisie explicite de l'IP.
- **Aucune découverte broker** (pas de mDNS fiable, pas de scan de port). Browse mDNS best-effort noté en fog.
- **Aucune migration** depuis l'ancienne config du fork : on repart de zéro.
- Actions : **Tester la connexion** (publie discovery + `online`) et **Dépublier de Home Assistant** (payload vide retained sur la découverte + disponibilité `offline`). L'entité existante n'est pas supprimée par le composant ; elle reste à retirer dans l'UI HA.
- Statut affiché : permission Notification Access, connexion MQTT, discovery publié.

## 9. Performance

Référence : [issue #12](https://github.com/MiCodesOrg/media2ha/issues/12). **Cibles indicatives, non bloquantes** :

| Métrique | Cible |
|---|---|
| APK (release, minifié) | ≤ 4 Mo (objectif ≤ 3.5 Mo) |
| PSS repos / lecture | ≤ 40 / ≤ 45 Mo |
| CPU repos / lecture | ~0 % / ≤ 5 % |
| État → MQTT | < 200 ms (LAN) |
| Commande HA → action | < 300 ms (LAN) |

Aucun wakelock, aucun polling ; resync position seulement en lecture. Mesure : `dumpsys meminfo`/`cpuinfo`, profiler, sortie de build, sur AVD API 23 et API 34.

## 10. Banc de test

Référence : [issue #5](https://github.com/MiCodesOrg/media2ha/issues/5). Voir `test-harness/` : `docker compose up -d` lance Mosquitto + Home Assistant avec MQTT discovery ; installer le composant custom dans `custom_components/`. Créer un AVD API 23 (l'existant est API 34) ; depuis l'émulateur, le broker est sur `10.0.2.2:1883`.

## 11. Hors périmètre

- TLS / broker distant.
- `play_media` (lancer un média depuis HA).
- Browse mDNS best-effort du broker (fog).

## 12. Différé

- [Support mute / seek / turn_on-off](https://github.com/MiCodesOrg/media2ha/issues/14) — absent du composant ; à traiter après le reste, éventuellement par contribution upstream ou fork.
- App/source dans HA (même ticket).

## 13. Définition de « fini »

- L'app compile et tourne sur **API 23** et **API 34**.
- À la connexion, l'entité `media_player` apparaît automatiquement dans HA via le composant.
- État, métadonnées, artwork et volume remontent ; play/pause/next/previous/volume agissent sur la session active.
- Le retrait publie un payload vide et marque l'entité indisponible ; la suppression définitive se fait dans HA.
- Aucun wakelock, pas de polling ; budgets perf constatés et documentés.
