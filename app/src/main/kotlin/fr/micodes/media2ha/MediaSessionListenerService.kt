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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Owns the media-session observation and the MQTT connection.
 *
 * It is a NotificationListenerService, so the system binds it and rebinds it after boot.
 * State changes are pushed to MQTT; commands arriving on `.../cmd/+` are applied to the
 * active media session.
 */
class MediaSessionListenerService : NotificationListenerService(),
    MediaSessionManager.OnActiveSessionsChangedListener {

    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var config: Media2HaConfig

    private var mqtt: MqttClientManager? = null
    private var topics: Topics? = null
    private var activeSignature: String? = null

    private val controllers = mutableMapOf<String, MediaController>()
    private val callbacks = mutableMapOf<String, MediaController.Callback>()
    private val lastPayloads = mutableMapOf<String, String>()
    private var lastArtHash: String? = null
    private var lastState: String? = null
    private var cachedSessionVolume = -1
    private var lastPositionSec: Long = -1
    private var lastPositionAtElapsed: Long = 0

    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    /** Republish the device volume when it changes (fallback sessions). */
    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            mainHandler.post {
                publishMute()
                activeController()?.let { publishVolume(it) }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val artworkExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "media2ha-artwork").apply { isDaemon = true }
    }
    private var positionLoopRunning = false

    private val positionLoop = object : Runnable {
        override fun run() {
            publishPosition(force = true)
            mainHandler.postDelayed(this, POSITION_RESYNC_MS)
        }
    }

    private val mqttListener = object : MqttClientManager.Listener {
        override fun onConnected(reconnect: Boolean) {
            val t = topics ?: return
            config.setStatus("Connecté au broker", false)
            mqtt?.publish(t.availability, Topics.PAYLOAD_ONLINE, true)
            mqtt?.publish(t.discovery, DiscoveryPayload.build(config, t), true)
            mqtt?.subscribe(t.cmdWildcard, 1)
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
        val t = Topics(config.deviceId)
        topics = t
        lastPayloads.clear()
        lastArtHash = null
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

                    override fun onAudioInfoChanged(info: MediaController.PlaybackInfo?) {
                        publishVolume(controller)
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

    private fun mapState(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "playing"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "playing"
        else -> "idle"
    }

    private fun mediaType(metadata: MediaMetadata?): String {
        val mime = metadata?.getString(METADATA_KEY_MIME)
        return if (!mime.isNullOrBlank() && mime.startsWith("video/")) "video" else "music"
    }

    private fun currentPositionMs(state: PlaybackState): Long {
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            position += (elapsed * state.playbackSpeed).toLong()
        }
        return max(0L, position)
    }

    // ------------------------------------------------------------- publishing

    private fun publishIfChanged(topic: String, payload: String, retained: Boolean, force: Boolean) {
        if (!force && lastPayloads[topic] == payload) return
        lastPayloads[topic] = payload
        mqtt?.publish(topic, payload, retained)
    }

    private fun publishCurrentState(force: Boolean = false) {
        val t = topics ?: return
        if (mqtt == null) return

        val controller = activeController()
        if (controller == null) {
            publishIfChanged(t.state, "idle", true, force)
            publishIfChanged(t.title, "", true, force)
            publishIfChanged(t.artist, "", true, force)
            publishIfChanged(t.album, "", true, force)
            publishIfChanged(t.duration, "", true, force)
            publishIfChanged(t.position, "", false, force)
            publishIfChanged(t.albumArt, "", false, force)
            publishIfChanged(t.source, "", true, force)
            lastArtHash = null
            lastState = null
            lastPositionSec = -1
            stopPositionLoop()
            return
        }

        val playbackState = controller.playbackState
        val state = mapState(playbackState?.state)
        val stateChanged = force || state != lastState
        publishIfChanged(t.state, state, true, force)
        lastState = state

        val metadata = controller.metadata
        publishIfChanged(t.title, metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(), true, force)
        publishIfChanged(t.artist, metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(), true, force)
        publishIfChanged(t.album, metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(), true, force)

        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        publishIfChanged(
            t.duration,
            if (durationMs > 0) (durationMs / 1000).toString() else "",
            true,
            force
        )
        publishIfChanged(t.mediatype, mediaType(metadata), true, force)

        publishVolume(controller)
        publishMute()
        publishSource(controller)
        publishPosition(stateChanged)
        publishArtwork(metadata, force)

        if (state == "playing") startPositionLoop() else stopPositionLoop()
    }

    /**
     * Prefer the session's own absolute volume scale. When a session declares
     * [VolumeProvider.VOLUME_CONTROL_ABSOLUTE] with `maxVolume == 0` (common for local
     * video apps that defer to the device stream), fall back to the system STREAM_MUSIC
     * volume so the HA slider still controls something real.
     */
    private fun publishVolume(controller: MediaController) {
        val info = controller.playbackInfo
        if (info != null && info.volumeControl == VolumeProvider.VOLUME_CONTROL_ABSOLUTE && info.maxVolume > 0) {
            publishVolumeLevel(info.currentVolume.toFloat() / info.maxVolume)
            return
        }
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        publishVolumeLevel(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max)
    }

    private fun publishVolumeLevel(level: Float) {
        val t = topics ?: return
        val rounded = (level * 100).roundToInt() / 100.0
        publishIfChanged(t.volume, rounded.toString(), true, false)
    }

    private fun publishMute() {
        val t = topics ?: return
        val muted = try {
            audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
        } catch (e: Exception) {
            false
        }
        publishIfChanged(t.mute, if (muted) "mute" else "unmute", true, false)
    }

    private fun publishSource(controller: MediaController) {
        val t = topics ?: return
        publishIfChanged(t.source, sourceLabel(controller.packageName), true, false)
    }

    private fun sourceLabel(packageName: String): String = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        packageName
    }

    /**
     * Position is published on real transitions (play/pause/track), on a detected seek
     * (a jump from the extrapolated position), and by the 30 s resync loop. Steady
     * playback callbacks do not republish it, so the broker is not spammed.
     */
    private fun publishPosition(force: Boolean) {
        val t = topics ?: return
        val controller = activeController() ?: return
        val state = controller.playbackState ?: return
        val seconds = currentPositionMs(state) / 1000
        val now = SystemClock.elapsedRealtime()
        if (!force && lastPositionSec >= 0) {
            val expected = lastPositionSec + (now - lastPositionAtElapsed) / 1000
            if (abs(seconds - expected) <= SEEK_THRESHOLD_SECONDS) return
        }
        lastPositionSec = seconds
        lastPositionAtElapsed = now
        mqtt?.publish(t.position, seconds.toString(), false)
    }

    private fun publishArtwork(metadata: MediaMetadata?, force: Boolean) {
        val t = topics ?: return
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        artworkExecutor.execute {
            val base64 = AlbumArt.encodeToBase64(bitmap)
            val hash = AlbumArt.hash(base64)
            if (!force && hash == lastArtHash) return@execute
            lastArtHash = hash
            mqtt?.publish(t.albumArt, base64 ?: "", false)
        }
    }

    private fun startPositionLoop() {
        if (positionLoopRunning) return
        positionLoopRunning = true
        mainHandler.postDelayed(positionLoop, POSITION_RESYNC_MS)
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
        publishMute()
    }

    private fun applySeek(controller: MediaController, actions: Long, payload: String) {
        val seconds = payload.trim().toLongOrNull() ?: return
        if (hasAction(actions, PlaybackState.ACTION_SEEK_TO)) {
            controller.transportControls.seekTo(seconds * 1000)
        }
    }

    companion object {
        private const val TAG = "Media2HA"
        private const val POSITION_RESYNC_MS = 30_000L
        private const val SEEK_THRESHOLD_SECONDS = 3L
        private const val METADATA_KEY_MIME = "android.media.metadata.MIME"
    }
}
