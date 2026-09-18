# Banc de test Media2HA

Broker **Mosquitto** + **Home Assistant** + le composant [`bkbilly/mqtt_media_player`](https://github.com/bkbilly/mqtt_media_player), pour valider discovery et commandes de bout en bout.

## Prérequis

- Docker + Docker Compose
- Un appareil/émulateur Android (cible API 23 ; validé en test sur API 34)

## Lancement

1. Installer le composant custom (attention : le dépôt imbrique `custom_components`) :

   ```bash
   git clone --depth 1 https://github.com/bkbilly/mqtt_media_player.git /tmp/mqtt_media_player
   mkdir -p test-harness/homeassistant/custom_components
   cp -r /tmp/mqtt_media_player/custom_components/mqtt_media_player \
     test-harness/homeassistant/custom_components/
   ```

2. Démarrer la stack :

   ```bash
   cd test-harness
   docker compose up -d
   ```

3. Ouvrir Home Assistant sur <http://localhost:8123> et créer le compte d'onboarding.

4. **Ajouter l'intégration MQTT** : Paramètres → Appareils et services → Ajouter une intégration → **MQTT** → broker `mosquitto`, port `1883`.

   > L'Home Assistant moderne n'accepte plus `mqtt:` dans `configuration.yaml` ; l'intégration se configure par le flux (UI ou API).

5. Redémarrer HA pour que `mqtt_media_player:` (listé dans `configuration.yaml`) s'initialise une fois sa dépendance `mqtt` disponible :

   ```bash
   docker compose restart homeassistant
   ```

## App Android

- Broker : `localhost:1883` en local.
- Depuis un émulateur, l'hôte est `10.0.2.2`. En WSL, plus fiable : `adb reverse tcp:1883 tcp:1883` puis hôte `127.0.0.1`, qui tunnelise vers le broker.
- Identifiants : aucun (anonyme), pas de TLS.

## AVD API 23

L'émulateur de test est en API 34 ; la cible du projet est `minSdk 23`. Pour créer un AVD API 23 :

```bash
SDK="$ANDROID_HOME"
"$SDK/cmdline-tools/latest/bin/sdkmanager" --list | grep "system-images;android-23"
"$SDK/cmdline-tools/latest/bin/sdkmanager" "emulator" "system-images;android-23;google_apis;x86_64"
"$SDK/cmdline-tools/latest/bin/avdmanager" create avd \
  -n media2ha-api23 \
  -k "system-images;android-23;google_apis;x86_64" \
  --device "tv_1080p"
"$SDK/emulator/emulator" -avd media2ha-api23
```

Adapter le `-k` à l'image réellement disponible.

## Vérifier

```bash
# Tous les topics de l'app
docker exec -it media2ha-mosquitto mosquitto_sub -t 'homeassistant/media_player/#' -t 'media2ha/#' -v
# Envoyer une commande
docker exec -it media2ha-mosquitto mosquitto_pub -t 'media2ha/<device_id>/cmd/play' -m 'play'
```

L'entité `media_player` apparaît automatiquement dans HA (intégration « MQTT Media Player »).

**Limite** : publier un payload vide sur le topic discovery efface la config retenue mais **ne supprime pas** l'entité déjà créée ; celle-ci reste à retirer dans l'UI HA.

## Arrêt

```bash
docker compose down
```
