package fr.micodes.media2ha

import android.content.ComponentName
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Adapter: observes the Android media sessions and the MQTT connection, feeds plain
 * snapshots to [SessionReport], and executes the resulting publishes and command decisions.
 */
class MediaSessionListenerService : NotificationListenerService(),
    MediaSessionManager.OnActiveSessionsChangedListener {

    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var config: Media2HaConfig

    private var mqtt: MqttClientManager? = null
    private var topics: Topics? = null
    private var report: SessionReport? = null
    private var activeSignature: String? = null

    private val controllers = mutableMapOf<String, MediaController>()
    private val callbacks = mutableMapOf<String, MediaController.Callback>()
    private val lastPayloads = mutableMapOf<String, String>()
    private var lastArtMetadata: MediaMetadata? = null
    private var lastArtHashValue: String? = null
    private var cachedSessionVolume = -1

    private val mainHandler = Handler(Looper.getMainLooper())
    private val artworkExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "media2ha-artwork").apply { isDaemon = true }
    }
    private var positionLoopRunning = false

    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    /** Republish state when the device volume changes (fallback sessions). */
    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            mainHandler.post { publishCurrentState() }
        }
    }

    private val positionLoop = object : Runnable {
        override fun run() {
            publishCurrentState()
            mainHandler.postDelayed(this, SessionReport.POSITION_RESYNC_MS)
        }
    }

    private val mqttListener = object : MqttClientManager.Listener {
        override fun onConnected(reconnect: Boolean) {
            val t = topics ?: return
            config.setStatus("Connecté au broker", false)
            mqtt?.publish(t.availability, Topics.PAYLOAD_ONLINE, true)
            mqtt?.publish(t.discovery, DiscoveryPayload.build(config, t), true)
            mqtt?.subscribe(t.cmdWildcard, 1)
            report?.reset()
            mainHandler.post { publishCurrentState(force = true) }
        }

        override fun onConnectionLost(cause: Throwable?) {
            config.setStatus("Connexion perdue: ${cause?.message ?: "inconnue"}", true)
        }

        override fun onMessage(topic: String, payload: String) {
            mainHandler.post { handleCommand(topic, payload) }
        }

        override fun onLog(message: String, isError: Boolean) {
            Log.i(TAG, message)
            if (isError) config.setStatus(message, true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        config = Media2HaConfig(this)
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        ensureMqtt()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureMqtt()
        return START_STICKY
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        ensureMqtt()
        val componentName = ComponentName(this, MediaSessionListenerService::class.java)
        runCatching {
            mediaSessionManager.addOnActiveSessionsChangedListener(this, componentName)
            updateSessions(mediaSessionManager.getActiveSessions(componentName))
        }.onFailure { Log.w(TAG, "getActiveSessions: ${it.message}") }
    }

    override fun onActiveSessionsChanged(activeControllers: List<MediaController>?) {
        updateSessions(activeControllers)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPositionLoop()
        runCatching { mediaSessionManager.removeOnActiveSessionsChangedListener(this) }
        runCatching { contentResolver.unregisterContentObserver(volumeObserver) }
        mqtt?.disconnect()
        artworkExecutor.shutdownNow()
    }

    // ---------------------------------------------------------------- MQTT setup

    private fun configSignature(): String =
        "${config.host}:${config.port}:${config.useAuth}:${config.username}:${config.deviceId}"

    private fun ensureMqtt() {
        if (!config.isConfigured) {
            config.setStatus("Broker non configuré", true)
            return
        }
        val signature = configSignature()
        if (mqtt == null || signature != activeSignature) {
            activeSignature = signature
            startMqtt()
        }
    }

    private fun startMqtt() {
        mqtt?.disconnect()
        val t = Topics(config.deviceId, config.discoveryPrefix)
        topics = t
        report = SessionReport(t)
        lastPayloads.clear()
        mqtt = MqttClientManager(
            serverUri = config.serverUri(),
            clientId = MqttClientManager.stableClientId(config.deviceId),
            username = if (config.useAuth) config.username else null,
            password = if (config.useAuth) config.password.toCharArray() else null,
            willTopic = t.availability,
            listener = mqttListener
        ).also { it.connect() }
    }

    // ---------------------------------------------------------- media sessions

    private fun updateSessions(activeControllers: List<MediaController>?) {
        val activePackages = activeControllers?.map { it.packageName }?.toSet() ?: emptySet()

        val iterator = controllers.entries.iterator()
        while (iterator.hasNext()) {
            val (pkg, controller) = iterator.next()
            if (pkg !in activePackages) {
                callbacks.remove(pkg)?.let { cb -> runCatching { controller.unregisterCallback(cb) } }
                iterator.remove()
            }
        }

        activeControllers?.forEach { controller ->
            val pkg = controller.packageName
            if (!controllers.containsKey(pkg)) {
                val cb = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        publishCurrentState()
                    }

                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        publishCurrentState()
                    }

                    override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) {
                        publishCurrentState()
                    }
                }
                runCatching { controller.registerCallback(cb) }
                controllers[pkg] = controller
                callbacks[pkg] = cb
            }
        }

        publishCurrentState(force = true)
    }

    /** The active session: the one playing, else the most recently updated. */
    private fun activeController(): MediaController? {
        val list = controllers.values
        if (list.isEmpty()) return null
        return list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0L }
    }

    // ------------------------------------------------------------- snapshot

    private fun buildSnapshot(controller: MediaController?): SessionSnapshot {
        if (controller == null) return SessionSnapshot(hasSession = false)
        val playbackState = controller.playbackState
        val metadata = controller.metadata
        return SessionSnapshot(
            hasSession = true,
            state = playbackStateValue(playbackState?.state),
            positionMs = playbackState?.position ?: 0L,
            playbackSpeed = playbackState?.playbackSpeed ?: 0f,
            lastPositionUpdateTimeMs = playbackState?.lastPositionUpdateTime ?: 0L,
            nowElapsedMs = SystemClock.elapsedRealtime(),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            mimeType = metadata?.getString(METADATA_KEY_MIME),
            artworkHash = artworkHash(metadata),
            volumeLevel = currentVolumeLevel(controller),
            muted = isStreamMuted(),
            sourceLabel = sourceLabel(controller.packageName),
        )
    }

    private fun playbackStateValue(state: Int?): PlaybackStateValue = when (state) {
        PlaybackState.STATE_NONE -> PlaybackStateValue.NONE
        PlaybackState.STATE_STOPPED -> PlaybackStateValue.STOPPED
        PlaybackState.STATE_PAUSED -> PlaybackStateValue.PAUSED
        PlaybackState.STATE_PLAYING -> PlaybackStateValue.PLAYING
        PlaybackState.STATE_FAST_FORWARDING -> PlaybackStateValue.FAST_FORWARDING
        PlaybackState.STATE_REWINDING -> PlaybackStateValue.REWINDING
        PlaybackState.STATE_BUFFERING -> PlaybackStateValue.BUFFERING
        PlaybackState.STATE_ERROR -> PlaybackStateValue.ERROR
        PlaybackState.STATE_CONNECTING -> PlaybackStateValue.CONNECTING
        PlaybackState.STATE_SKIPPING_TO_NEXT -> PlaybackStateValue.SKIPPING_TO_NEXT
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> PlaybackStateValue.SKIPPING_TO_PREVIOUS
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> PlaybackStateValue.SKIPPING_TO_QUEUE_ITEM
        else -> PlaybackStateValue.OTHER
    }

    /**
     * Prefer the session's own absolute volume scale. When a session declares
     * [VolumeProvider.VOLUME_CONTROL_ABSOLUTE] with `maxVolume == 0` (common for local
     * video apps that defer to the device stream), fall back to the system STREAM_MUSIC
     * volume so the HA slider still controls something real.
     */
    private fun currentVolumeLevel(controller: MediaController): Float? {
        val info = controller.playbackInfo
        if (info != null && info.volumeControl == VolumeProvider.VOLUME_CONTROL_ABSOLUTE && info.maxVolume > 0) {
            return info.currentVolume.toFloat() / info.maxVolume
        }
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return null
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private fun isStreamMuted(): Boolean = try {
        audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
    } catch (e: Exception) {
        false
    }

    private fun sourceLabel(packageName: String): String = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        packageName
    }

    /** Cheap fingerprint of the artwork, cached per metadata object, so changes are detectable. */
    private fun artworkHash(metadata: MediaMetadata?): String? {
        if (metadata == null) return null
        if (metadata === lastArtMetadata) return lastArtHashValue
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val hash = if (bitmap == null) {
            null
        } else {
            val sb = StringBuilder().append(bitmap.width).append('x').append(bitmap.height)
            val stepX = maxOf(1, bitmap.width / 8)
            val stepY = maxOf(1, bitmap.height / 8)
            var y = 0
            while (y < bitmap.height) {
                var x = 0
                while (x < bitmap.width) {
                    sb.append(':').append(bitmap.getPixel(x, y))
                    x += stepX
                }
                y += stepY
            }
            Integer.toHexString(sb.toString().hashCode())
        }
        lastArtMetadata = metadata
        lastArtHashValue = hash
        return hash
    }

    // ------------------------------------------------------------- publishing

    private fun publishIfChanged(topic: String, payload: String, retained: Boolean, force: Boolean) {
        if (!force && lastPayloads[topic] == payload) return
        lastPayloads[topic] = payload
        mqtt?.publish(topic, payload, retained)
    }

    private fun publishCurrentState(force: Boolean = false) {
        val t = topics ?: return
        val r = report ?: return
        if (mqtt == null) return

        val controller = activeController()
        val result = r.report(buildSnapshot(controller), force)
        for (p in result.publishes) {
            if (p.topic == t.position) {
                mqtt?.publish(p.topic, p.payload, p.retained)
            } else {
                publishIfChanged(p.topic, p.payload, p.retained, force)
            }
        }
        if (result.encodeArtwork) publishArtwork(controller?.metadata)

        val state = result.publishes.firstOrNull { it.topic == t.state }?.payload
        if (state == "playing") startPositionLoop() else stopPositionLoop()
    }

    private fun publishArtwork(metadata: MediaMetadata?) {
        val t = topics ?: return
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        artworkExecutor.execute {
            mqtt?.publish(t.albumArt, AlbumArt.encodeToBase64(bitmap) ?: "", false)
        }
    }

    private fun startPositionLoop() {
        if (positionLoopRunning) return
        positionLoopRunning = true
        mainHandler.postDelayed(positionLoop, SessionReport.POSITION_RESYNC_MS)
    }

    private fun stopPositionLoop() {
        if (!positionLoopRunning) return
        positionLoopRunning = false
        mainHandler.removeCallbacks(positionLoop)
    }

    // ---------------------------------------------------------------- commands

    private fun hasAction(actions: Long, action: Long): Boolean = (actions and action) != 0L

    private fun handleCommand(topic: String, payload: String) {
        val t = topics ?: return
        Log.d(TAG, "Commande reçue: $topic = $payload")
        val controller = activeController()
        if (controller == null) {
            Log.d(TAG, "Commande ignorée: aucune session active")
            return
        }
        val actions = controller.playbackState?.actions ?: 0L

        when (topic) {
            t.cmdPlay -> if (hasAction(actions, PlaybackState.ACTION_PLAY)) controller.transportControls.play()
            t.cmdPause -> if (hasAction(actions, PlaybackState.ACTION_PAUSE)) controller.transportControls.pause()
            t.cmdPlayPause -> togglePlayPause(controller, actions)
            t.cmdNext -> if (hasAction(actions, PlaybackState.ACTION_SKIP_TO_NEXT)) {
                controller.transportControls.skipToNext()
            }
            t.cmdPrevious -> if (hasAction(actions, PlaybackState.ACTION_SKIP_TO_PREVIOUS)) {
                controller.transportControls.skipToPrevious()
            }
            t.cmdVolume -> applyVolume(controller, payload)
            t.cmdMute -> applyMute(controller, payload)
            t.cmdSeek -> applySeek(controller, actions, payload)
            t.cmdTurnOn -> if (hasAction(actions, PlaybackState.ACTION_PLAY)) {
                controller.transportControls.play()
            }
            t.cmdTurnOff -> if (hasAction(actions, PlaybackState.ACTION_PAUSE)) {
                controller.transportControls.pause()
            }
            else -> Log.d(TAG, "Commande inconnue sur $topic")
        }
    }

    private fun togglePlayPause(controller: MediaController, actions: Long) {
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing) {
            if (hasAction(actions, PlaybackState.ACTION_PAUSE)) controller.transportControls.pause()
        } else {
            if (hasAction(actions, PlaybackState.ACTION_PLAY)) controller.transportControls.play()
        }
    }

    private fun applyVolume(controller: MediaController, payload: String) {
        val value = payload.trim().toFloatOrNull()?.coerceIn(0f, 1f) ?: return
        val info = controller.playbackInfo
        if (info != null && info.volumeControl == VolumeProvider.VOLUME_CONTROL_ABSOLUTE && info.maxVolume > 0) {
            val target = (value * info.maxVolume).roundToInt().coerceIn(0, info.maxVolume)
            controller.setVolumeTo(target, 0)
            return
        }
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val target = (value * max).roundToInt().coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    private fun applyMute(controller: MediaController, payload: String) {
        val mute = when (payload.trim().lowercase()) {
            "mute", "muted", "on", "true", "1" -> true
            "unmute", "unmuted", "off", "false", "0" -> false
            else -> return
        }
        val info = controller.playbackInfo
        if (info != null && info.volumeControl == VolumeProvider.VOLUME_CONTROL_ABSOLUTE && info.maxVolume > 0) {
            try {
                controller.adjustVolume(
                    if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    0
                )
            } catch (e: Exception) {
                if (mute) {
                    cachedSessionVolume = info.currentVolume
                    controller.setVolumeTo(0, 0)
                } else {
                    val restore = if (cachedSessionVolume > 0) cachedSessionVolume else info.maxVolume / 3
                    controller.setVolumeTo(restore, 0)
                }
            }
        } else {
            try {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    0
                )
            } catch (e: Exception) {
                Log.w(TAG, "mute: ${e.message}")
            }
        }
        publishCurrentState()
    }

    private fun applySeek(controller: MediaController, actions: Long, payload: String) {
        val seconds = payload.trim().toLongOrNull() ?: return
        if (hasAction(actions, PlaybackState.ACTION_SEEK_TO)) {
            controller.transportControls.seekTo(seconds * 1000)
        }
    }

    companion object {
        private const val TAG = "Media2HA"
        private const val METADATA_KEY_MIME = "android.media.metadata.MIME"
    }
}
