<h1>Media2HA</h1>

**Relais média Android ↔ Home Assistant via MQTT** — Android 6+ (`minSdk 23`).

Media2HA observe la session média active d'un appareil Android / Android TV et l'expose dans Home Assistant comme un `media_player` complet : état, métadonnées, pochette et volume. Il reçoit aussi les commandes de Home Assistant (play, pause, next, previous, mute, seek, volume, on/off).

- **Simple / léger / performant** : Paho MQTT nu, pas de dépendance lourde, pas de polling.
- Une seule entité = **la session active** (celle qui joue, sinon la plus récemment mise à jour).
- Découverte automatique MQTT ; aucun YAML côté Home Assistant.

## Comment ça marche

L'app est un `NotificationListenerService` (accès aux notifications requis) qui suit les `MediaSession` du système. Elle publie un état retenu sur des topics dédiés, et s'abonne aux topics de commande.

L'entité Home Assistant est fournie par [`MiCodesOrg/mqtt_media_player`](https://github.com/MiCodesOrg/mqtt_media_player), un fork de `bkbilly/mqtt_media_player` : Home Assistant ne dispose pas nativement d'un `media_player` MQTT.

## Prérequis

- Android 6.0+ (`minSdk 23`)
- Un broker MQTT accessible depuis l'appareil
- Home Assistant avec l'intégration **MQTT**
- L'intégration custom **`MiCodesOrg/mqtt_media_player`** (fork, via [HACS](https://hacs.xyz/))

## Installation

1. Construire ou récupérer `media2ha.apk`.
2. Installer l'APK sur l'appareil (ADB ou gestionnaire de fichiers).
3. **Accorder l'accès aux notifications** (obligatoire) :
   - Réglages système → Accès aux notifications → Media2HA, ou
   - en ADB :
     ```bash
     adb shell cmd notification allow_listener fr.micodes.media2ha/.MediaSessionListenerService
     ```
4. Ouvrir l'app, saisir l'IP/le port du broker (et les identifiants si nécessaire), puis **Enregistrer**.
5. Dans Home Assistant, ajouter `MiCodesOrg/mqtt_media_player` comme dépôt HACS puis redémarrer. L'entité `media_player.<device_id>` apparaît automatiquement.

> Sur émulateur, le broker de la machine hôte est joignable via `10.0.2.2` ; en WSL, `adb reverse tcp:1883 tcp:1883` puis hôte `127.0.0.1` est plus fiable.

## Configuration

Champs exposés (et rien d'autre) :

| Champ | Défaut | Notes |
|---|---|---|
| Hôte broker | *(vide)* | IP ou nom d'hôte ; saisie explicite, aucune découverte réseau |
| Port | `1883` | |
| Identifiants | désactivé | utilisateur / mot de passe |
| Nom d'appareil | `Build.MODEL` | nom affiché dans Home Assistant |
| `device_id` | slug du nom + suffixe stable | identifiant MQTT, prérempli et modifiable (`[a-z0-9_]`) |

Actions : **Tester la connexion** (publie la découverte + `online`) et **Dépublier de Home Assistant** (payload vide + `offline`).

## Topics MQTT

Namespace runtime : `media2ha/<device_id>`.

| Rôle | Topic | Retained |
|---|---|---|
| Découverte | `homeassistant/media_player/<device_id>/config` | oui |
| Disponibilité | `media2ha/<device_id>/availability` (`online`/`offline`) | oui |
| État | `.../state` | oui |
| Titre / Artiste / Album | `.../title`, `.../artist`, `.../album` | oui |
| Type média | `.../mediatype` (`music`/`video`) | oui |
| Durée | `.../duration` (secondes) | oui |
| Position | `.../position` (secondes) | non |
| Volume | `.../volume` (`0.0`–`1.0`) | oui |
| Pochette | `.../albumart` (base64 JPEG, 512 px max) | non |
| Muet | `.../mute` (`mute`/`unmute`) | oui |
| Source (app) | `.../source` | oui |
| Commandes | `.../cmd/<action>` | — |

États publiés : `playing`, `paused`, `stopped`, `idle` (jamais `off`). Sans session active : `idle` et métadonnées effacées.

## Commandes

L'app s'abonne à `media2ha/<device_id>/cmd/+` et applique l'action à la session active, après vérification des capacités de la session.

| Commande | Action |
|---|---|
| `cmd/play` | lecture |
| `cmd/pause` | pause |
| `cmd/playpause` | bascule lecture/pause (non émis par le composant actuel) |
| `cmd/next` | piste suivante |
| `cmd/previous` | piste précédente |
| `cmd/volume` | volume `0.0`–`1.0` |
| `cmd/mute` | mute / unmute (`mute`/`unmute`) |
| `cmd/seek` | position en secondes |
| `cmd/turn_on` | lecture |
| `cmd/turn_off` | pause |

Le volume utilise l'échelle propre de la session si elle en expose une (`VOLUME_CONTROL_ABSOLUTE` avec `maxVolume > 0`) ; sinon le volume système `STREAM_MUSIC` sert de repli, et les changements externes (télécommande) sont republiés.

## Performance

Cibles indicatives, non bloquantes :

| Métrique | Cible |
|---|---|
| APK (release, minifié) | ≤ 4 Mo (objectif ≤ 3.5 Mo) |
| PSS repos / lecture | ≤ 40 / ≤ 45 Mo |
| CPU repos / lecture | ~0 % / ≤ 5 % |
| État → MQTT | < 200 ms (LAN) |
| Commande HA → action | < 300 ms (LAN) |

Aucun wakelock, aucun polling ; la position n'est republiée qu'aux transitions, sur seek détecté, et toutes les 30 s en lecture.

## Développement

- JDK 17, Android SDK (`compileSdk 34`, `minSdk 23`, `targetSdk 34`)
- Gradle via le wrapper (`./gradlew`)

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/media2ha.apk
./gradlew assembleRelease   # APK non signé : à signer pour distribuer
```

Banc de test local (Mosquitto + Home Assistant + composant custom) : voir [`test-harness/`](test-harness/README.md).

Le spec d'implémentation complet vit dans [`docs/SPEC.md`](docs/SPEC.md).

## Limites connues

- Le composant `mqtt_media_player` utilisé est un **fork** (`MiCodesOrg/mqtt_media_player`) ; le chemin upstream n'expose pas encore mute/seek/power/source. Une PR amont est possible.
- « Dépublier de Home Assistant » efface la découverte retenue et marque l'entité indisponible, mais ne supprime pas l'entité déjà créée : à retirer dans l'UI Home Assistant.
- Compatibilité avec l'ancien topic `android_tv/playback_state` : non.
