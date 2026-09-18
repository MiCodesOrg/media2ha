# Banc de test Media2HA

Broker **Mosquitto** + **Home Assistant** + le composant [`bkbilly/mqtt_media_player`](https://github.com/bkbilly/mqtt_media_player), pour valider discovery et commandes de bout en bout.

## Prérequis

- Docker + Docker Compose
- Un appareil/émulateur Android (API 23 pour la cible réelle, API 34 pour le quotidien)

## Lancement

1. Installer le composant custom dans le config HA :

   ```bash
   git clone https://github.com/bkbilly/mqtt_media_player.git \
     test-harness/homeassistant/custom_components/mqtt_media_player
   ```

2. Démarrer la stack :

   ```bash
   cd test-harness
   docker compose up -d
   ```

3. Ouvrir Home Assistant sur <http://localhost:8123> et créer le compte d'onboarding.

- Broker MQTT (depuis l'app Android) : `localhost:1883` en local ; depuis l'émulateur Android, l'hôte est **`10.0.2.2`**.
- Identifiants : aucun (anonyme), pas de TLS.

## AVD API 23

L'émulateur n'a ici que l'API 34 ; la cible du projet est `minSdk 23`. Pour créer un AVD API 23 :

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

Adapter le `-k` à l'image réellement disponible si `google_apis;x86_64` n'existe pas pour l'API 23.

## Vérifier l'intégration

Une fois l'app configurée et connectée, l'entité `media_player` doit apparaître automatiquement dans HA (intégration « MQTT Media Player »). Sinon, inspecter les topics :

```bash
docker exec -it media2ha-mosquitto mosquitto_sub -t 'homeassistant/media_player/#' -v
docker exec -it media2ha-mosquitto mosquitto_sub -t 'media2ha/#' -v
```

## Arrêt

```bash
docker compose down
```
