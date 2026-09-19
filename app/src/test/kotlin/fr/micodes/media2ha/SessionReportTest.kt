package fr.micodes.media2ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReportTest {

    private val topics = Topics("dev")

    private fun payload(publishes: List<Publish>, topic: String): String? =
        publishes.firstOrNull { it.topic == topic }?.payload

    private fun session(
        state: PlaybackStateValue = PlaybackStateValue.PLAYING,
        positionMs: Long = 0L,
        speed: Float = 1f,
        lastUpdate: Long = 0L,
        now: Long = 0L,
        durationMs: Long = 0L,
        title: String = "title",
        artist: String = "artist",
        album: String = "album",
        mime: String? = null,
        artHash: String? = null,
        volume: Float? = null,
        muted: Boolean = false,
        source: String = "SmartTube",
    ) = SessionSnapshot(
        hasSession = true,
        state = state,
        positionMs = positionMs,
        playbackSpeed = speed,
        lastPositionUpdateTimeMs = lastUpdate,
        nowElapsedMs = now,
        durationMs = durationMs,
        title = title,
        artist = artist,
        album = album,
        mimeType = mime,
        artworkHash = artHash,
        volumeLevel = volume,
        muted = muted,
        sourceLabel = source,
    )

    @Test
    fun `maps every playback state`() {
        val cases = mapOf(
            PlaybackStateValue.PLAYING to "playing",
            PlaybackStateValue.BUFFERING to "playing",
            PlaybackStateValue.CONNECTING to "playing",
            PlaybackStateValue.FAST_FORWARDING to "playing",
            PlaybackStateValue.REWINDING to "playing",
            PlaybackStateValue.SKIPPING_TO_NEXT to "playing",
            PlaybackStateValue.SKIPPING_TO_PREVIOUS to "playing",
            PlaybackStateValue.SKIPPING_TO_QUEUE_ITEM to "playing",
            PlaybackStateValue.PAUSED to "paused",
            PlaybackStateValue.STOPPED to "stopped",
            PlaybackStateValue.NONE to "idle",
            PlaybackStateValue.ERROR to "idle",
            PlaybackStateValue.OTHER to "idle",
        )
        for ((value, expected) in cases) {
            val result = SessionReport(topics).report(session(state = value))
            assertEquals("state $value", expected, payload(result.publishes, topics.state))
        }
    }

    @Test
    fun `no session clears state and metadata`() {
        val result = SessionReport(topics).report(SessionSnapshot(hasSession = false))
        assertEquals("idle", payload(result.publishes, topics.state))
        assertEquals("", payload(result.publishes, topics.title))
        assertEquals("", payload(result.publishes, topics.artist))
        assertEquals("", payload(result.publishes, topics.album))
        assertEquals("", payload(result.publishes, topics.duration))
        assertEquals("", payload(result.publishes, topics.position))
        assertEquals("", payload(result.publishes, topics.albumArt))
        assertEquals("", payload(result.publishes, topics.source))
        assertFalse(result.encodeArtwork)
    }

    @Test
    fun `duration zero is published empty`() {
        val result = SessionReport(topics).report(session(durationMs = 0L))
        assertEquals("", payload(result.publishes, topics.duration))
    }

    @Test
    fun `duration is published in seconds`() {
        val result = SessionReport(topics).report(session(durationMs = 240_000L))
        assertEquals("240", payload(result.publishes, topics.duration))
    }

    @Test
    fun `media type follows the mime type`() {
        assertEquals("video", payload(SessionReport(topics).report(session(mime = "video/mp4")).publishes, topics.mediatype))
        assertEquals("music", payload(SessionReport(topics).report(session(mime = "audio/mpeg")).publishes, topics.mediatype))
        assertEquals("music", payload(SessionReport(topics).report(session(mime = null)).publishes, topics.mediatype))
    }

    @Test
    fun `volume is rounded to two decimals`() {
        val result = SessionReport(topics).report(session(volume = 1f / 3f))
        assertEquals("0.33", payload(result.publishes, topics.volume))
    }

    @Test
    fun `missing volume publishes nothing`() {
        val result = SessionReport(topics).report(session(volume = null))
        assertNull(payload(result.publishes, topics.volume))
    }

    @Test
    fun `mute payload follows the muted flag`() {
        assertEquals("mute", payload(SessionReport(topics).report(session(muted = true)).publishes, topics.mute))
        assertEquals("unmute", payload(SessionReport(topics).report(session(muted = false)).publishes, topics.mute))
    }

    @Test
    fun `position is published on the first report`() {
        val result = SessionReport(topics).report(session(positionMs = 0L, lastUpdate = 1_000L, now = 1_000L))
        assertEquals("0", payload(result.publishes, topics.position))
    }

    @Test
    fun `steady playback does not republish position`() {
        val report = SessionReport(topics)
        report.report(session(positionMs = 0L, lastUpdate = 1_000L, now = 1_000L))
        val result = report.report(session(positionMs = 0L, lastUpdate = 1_000L, now = 5_000L))
        assertNull(payload(result.publishes, topics.position))
    }

    @Test
    fun `a seek republishes position`() {
        val report = SessionReport(topics)
        report.report(session(positionMs = 0L, lastUpdate = 1_000L, now = 1_000L))
        val result = report.report(session(positionMs = 20_000L, lastUpdate = 5_000L, now = 5_000L))
        assertEquals("20", payload(result.publishes, topics.position))
    }

    @Test
    fun `resync interval republishes position`() {
        val report = SessionReport(topics)
        report.report(session(positionMs = 0L, lastUpdate = 1_000L, now = 1_000L))
        val result = report.report(session(positionMs = 0L, lastUpdate = 1_000L, now = 31_000L))
        assertEquals("30", payload(result.publishes, topics.position))
    }

    @Test
    fun `force republishes position`() {
        val report = SessionReport(topics)
        report.report(session(positionMs = 0L, lastUpdate = 1_000L, now = 1_000L))
        val result = report.report(session(positionMs = 0L, lastUpdate = 1_000L, now = 5_000L), force = true)
        assertEquals("4", payload(result.publishes, topics.position))
    }

    @Test
    fun `a state change republishes position`() {
        val report = SessionReport(topics)
        report.report(session(state = PlaybackStateValue.PAUSED, now = 1_000L))
        val result = report.report(session(state = PlaybackStateValue.PLAYING, lastUpdate = 1_100L, now = 1_100L))
        assertEquals("0", payload(result.publishes, topics.position))
    }

    @Test
    fun `missing artwork clears the album art topic`() {
        val result = SessionReport(topics).report(session(artHash = null))
        assertEquals("", payload(result.publishes, topics.albumArt))
        assertFalse(result.encodeArtwork)
    }

    @Test
    fun `changed artwork is encoded once`() {
        val report = SessionReport(topics)
        val first = report.report(session(artHash = "aaa"))
        assertTrue(first.encodeArtwork)
        assertNull(payload(first.publishes, topics.albumArt))

        val second = report.report(session(artHash = "aaa"))
        assertFalse(second.encodeArtwork)

        val third = report.report(session(artHash = "bbb"))
        assertTrue(third.encodeArtwork)
    }

    @Test
    fun `force re-encodes artwork`() {
        val report = SessionReport(topics)
        report.report(session(artHash = "aaa"), force = true)
        val result = report.report(session(artHash = "aaa"), force = true)
        assertTrue(result.encodeArtwork)
    }

    @Test
    fun `reset republishes everything`() {
        val report = SessionReport(topics)
        report.report(session(state = PlaybackStateValue.PAUSED, now = 1_000L, artHash = "aaa"))
        report.reset()
        val result = report.report(session(state = PlaybackStateValue.PAUSED, now = 1_100L, artHash = "aaa"))
        assertEquals("0", payload(result.publishes, topics.position))
        assertTrue(result.encodeArtwork)
    }
}
