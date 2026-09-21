package fr.micodes.media2ha

import kotlin.math.abs
import kotlin.math.roundToInt

/** Android-free description of the active session, produced by the adapter. */
data class SessionSnapshot(
    val hasSession: Boolean,
    val state: PlaybackStateValue = PlaybackStateValue.NONE,
    val positionMs: Long = 0L,
    val playbackSpeed: Float = 0f,
    val lastPositionUpdateTimeMs: Long = 0L,
    val nowElapsedMs: Long = 0L,
    val durationMs: Long = 0L,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val mimeType: String? = null,
    val hasVideoSize: Boolean = false,
    val sessionTag: String? = null,
    val packageName: String = "",
    val artworkUri: String? = null,
    val artworkHash: String? = null,
    val volumeLevel: Float? = null,
    val muted: Boolean = false,
    val sourceLabel: String = "",
)

enum class PlaybackStateValue {
    NONE, STOPPED, PAUSED, PLAYING, FAST_FORWARDING, REWINDING,
    BUFFERING, ERROR, CONNECTING, SKIPPING_TO_NEXT, SKIPPING_TO_PREVIOUS,
    SKIPPING_TO_QUEUE_ITEM, OTHER,
}

/** One MQTT write the adapter should perform. */
data class Publish(val topic: String, val payload: String, val retained: Boolean)

/** The result of translating a snapshot: what to publish, and whether artwork needs encoding. */
data class Report(val publishes: List<Publish>, val encodeArtwork: Boolean)

/**
 * The session report module: translates a [SessionSnapshot] into the payloads Home Assistant
 * consumes. Pure and stateful only for translation memory (last state, last position, last
 * artwork hash); wire-level deduplication stays in the adapter.
 */
class SessionReport(private val topics: Topics) {

    private var lastState: String? = null
    private var lastPositionSec: Long = -1
    private var lastPositionAtElapsed: Long = 0
    private var lastArtHash: String? = null

    /** Forget translation memory, so the next report republishes everything (e.g. reconnect). */
    fun reset() {
        lastState = null
        lastPositionSec = -1
        lastPositionAtElapsed = 0
        lastArtHash = null
    }

    fun report(snapshot: SessionSnapshot, force: Boolean = false): Report {
        if (!snapshot.hasSession) return reportNoSession()

        val publishes = mutableListOf<Publish>()
        val state = mapState(snapshot.state)
        val stateChanged = force || state != lastState
        lastState = state

        publishes += Publish(topics.state, state, true)
        publishes += Publish(topics.title, snapshot.title, true)
        publishes += Publish(topics.artist, snapshot.artist, true)
        publishes += Publish(topics.album, snapshot.album, true)
        publishes += Publish(
            topics.duration,
            if (snapshot.durationMs > 0) (snapshot.durationMs / 1000).toString() else "",
            true,
        )
        publishes += Publish(topics.mediatype, mediaType(snapshot), true)
        publishes += Publish(topics.mute, if (snapshot.muted) "mute" else "unmute", true)
        publishes += Publish(topics.source, snapshot.sourceLabel, true)
        snapshot.volumeLevel?.let { publishes += Publish(topics.volume, formatLevel(it), true) }

        val positionSeconds = currentPositionMs(snapshot) / 1000
        if (shouldPublishPosition(positionSeconds, snapshot, force, stateChanged)) {
            publishes += Publish(topics.position, positionSeconds.toString(), false)
            lastPositionSec = positionSeconds
            lastPositionAtElapsed = snapshot.nowElapsedMs
        }

        var encodeArtwork = false
        val hash = snapshot.artworkHash
        if (hash == null) {
            publishes += Publish(topics.albumArt, "", false)
            lastArtHash = null
        } else if (force || hash != lastArtHash) {
            encodeArtwork = true
            lastArtHash = hash
        }

        return Report(publishes, encodeArtwork)
    }

    private fun reportNoSession(): Report {
        val publishes = listOf(
            Publish(topics.state, "idle", true),
            Publish(topics.title, "", true),
            Publish(topics.artist, "", true),
            Publish(topics.album, "", true),
            Publish(topics.duration, "", true),
            Publish(topics.position, "", false),
            Publish(topics.albumArt, "", false),
            Publish(topics.source, "", true),
        )
        reset()
        return Report(publishes, encodeArtwork = false)
    }

    private fun mapState(state: PlaybackStateValue): String = when (state) {
        PlaybackStateValue.PLAYING -> "playing"
        PlaybackStateValue.PAUSED -> "paused"
        PlaybackStateValue.BUFFERING, PlaybackStateValue.CONNECTING -> "playing"
        PlaybackStateValue.STOPPED -> "stopped"
        PlaybackStateValue.FAST_FORWARDING,
        PlaybackStateValue.REWINDING,
        PlaybackStateValue.SKIPPING_TO_NEXT,
        PlaybackStateValue.SKIPPING_TO_PREVIOUS,
        PlaybackStateValue.SKIPPING_TO_QUEUE_ITEM -> "playing"
        else -> "idle"
    }

    /**
     * Video vs music is rarely explicit: many video apps (Plex, for one) publish no MIME
     * type at all, and may even reuse the ARTIST/ALBUM keys. So we take the first signal
     * that says "video", and fall back to music.
     */
    private fun mediaType(snapshot: SessionSnapshot): String {
        val mime = snapshot.mimeType
        if (!mime.isNullOrBlank() && mime.startsWith("video/")) return "video"
        if (snapshot.hasVideoSize) return "video"
        if (snapshot.sessionTag?.contains("video", ignoreCase = true) == true) return "video"
        if (snapshot.packageName in VIDEO_PACKAGES) return "video"
        return "music"
    }

    private fun currentPositionMs(snapshot: SessionSnapshot): Long {
        var position = snapshot.positionMs
        if (snapshot.state == PlaybackStateValue.PLAYING) {
            val elapsed = snapshot.nowElapsedMs - snapshot.lastPositionUpdateTimeMs
            position += (elapsed * snapshot.playbackSpeed).toLong()
        }
        return maxOf(0L, position)
    }

    private fun shouldPublishPosition(
        seconds: Long,
        snapshot: SessionSnapshot,
        force: Boolean,
        stateChanged: Boolean,
    ): Boolean {
        if (force || stateChanged) return true
        if (lastPositionSec < 0) return true
        val expected = lastPositionSec + (snapshot.nowElapsedMs - lastPositionAtElapsed) / 1000
        if (abs(seconds - expected) > SEEK_THRESHOLD_SECONDS) return true
        return snapshot.nowElapsedMs - lastPositionAtElapsed >= POSITION_RESYNC_MS
    }

    private fun formatLevel(level: Float): String {
        val rounded = (level * 100).roundToInt() / 100.0
        return rounded.toString()
    }

    companion object {
        const val POSITION_RESYNC_MS = 30_000L
        const val SEEK_THRESHOLD_SECONDS = 3L

        /** Apps known to play video, for sessions that expose no MIME type or tag. */
        private val VIDEO_PACKAGES = setOf(
            "com.plexapp.android",
            "com.google.android.youtube.tv",
            "com.google.android.youtube",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.amazon.avod.thirdpartyclient",
            "com.disney.disneyplus",
            "com.apple.atve.androidtv.appletv",
            "com.molotov.app",
            "com.canal.android.canal",
            "com.arte.tv",
            "org.xbmc.kodi",
            "org.videolan.vlc",
            "com.mxtech.videoplayer.ad",
        )
    }
}
