package fr.micodes.media2ha

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandRouterTest {

    private val topics = Topics("dev")
    private val router = CommandRouter(topics)

    private fun caps(
        transports: Set<TransportCapability> = TransportCapability.entries.toSet(),
        playing: Boolean = false,
        volume: VolumeControl = VolumeControl.SessionAbsolute(10),
    ) = CommandCapabilities(transports, playing, volume)

    @Test
    fun `play follows the capability`() {
        assertEquals(CommandDecision.Play, router.route(topics.cmdPlay, "play", caps()))
        assertEquals(
            CommandDecision.Ignore,
            router.route(topics.cmdPlay, "play", caps(transports = emptySet())),
        )
    }

    @Test
    fun `pause follows the capability`() {
        assertEquals(CommandDecision.Pause, router.route(topics.cmdPause, "pause", caps()))
        assertEquals(
            CommandDecision.Ignore,
            router.route(topics.cmdPause, "pause", caps(transports = emptySet())),
        )
    }

    @Test
    fun `playpause toggles from the playing state`() {
        assertEquals(CommandDecision.Pause, router.route(topics.cmdPlayPause, "", caps(playing = true)))
        assertEquals(CommandDecision.Play, router.route(topics.cmdPlayPause, "", caps(playing = false)))
    }

    @Test
    fun `playpause ignores when the needed capability is missing`() {
        val onlyPlay = setOf(TransportCapability.PLAY)
        val onlyPause = setOf(TransportCapability.PAUSE)
        assertEquals(
            CommandDecision.Ignore,
            router.route(topics.cmdPlayPause, "", caps(transports = onlyPlay, playing = true)),
        )
        assertEquals(
            CommandDecision.Ignore,
            router.route(topics.cmdPlayPause, "", caps(transports = onlyPause, playing = false)),
        )
    }

    @Test
    fun `next and previous follow their capabilities`() {
        assertEquals(CommandDecision.Next, router.route(topics.cmdNext, "", caps()))
        assertEquals(CommandDecision.Previous, router.route(topics.cmdPrevious, "", caps()))
        assertEquals(
            CommandDecision.Ignore,
            router.route(topics.cmdNext, "", caps(transports = emptySet())),
        )
        assertEquals(
            CommandDecision.Ignore,
            router.route(topics.cmdPrevious, "", caps(transports = emptySet())),
        )
    }

    @Test
    fun `seek parses seconds and follows the capability`() {
        assertEquals(CommandDecision.SeekTo(40), router.route(topics.cmdSeek, " 40 ", caps()))
        assertEquals(
            CommandDecision.Ignore,
            router.route(topics.cmdSeek, "abc", caps()),
        )
        assertEquals(
            CommandDecision.Ignore,
            router.route(
                topics.cmdSeek,
                "40",
                caps(transports = setOf(TransportCapability.PLAY)),
            ),
        )
    }

    @Test
    fun `session volume maps to an absolute index`() {
        assertEquals(
            CommandDecision.SetVolume(11, useSession = true),
            router.route(topics.cmdVolume, "0.7", caps(volume = VolumeControl.SessionAbsolute(15))),
        )
    }

    @Test
    fun `device volume maps to a stream index`() {
        assertEquals(
            CommandDecision.SetVolume(5, useSession = false),
            router.route(topics.cmdVolume, "0.5", caps(volume = VolumeControl.Device(10))),
        )
    }

    @Test
    fun `volume is clamped and invalid payloads are ignored`() {
        assertEquals(
            CommandDecision.SetVolume(10, useSession = true),
            router.route(topics.cmdVolume, "2", caps(volume = VolumeControl.SessionAbsolute(10))),
        )
        assertEquals(CommandDecision.Ignore, router.route(topics.cmdVolume, "loud", caps()))
        assertEquals(
            CommandDecision.Ignore,
            router.route(topics.cmdVolume, "0.5", caps(volume = VolumeControl.Unavailable)),
        )
    }

    @Test
    fun `mute parses the payload`() {
        assertEquals(
            CommandDecision.SetMute(true, useSession = true),
            router.route(topics.cmdMute, "mute", caps(volume = VolumeControl.SessionAbsolute(10))),
        )
        assertEquals(
            CommandDecision.SetMute(false, useSession = false),
            router.route(topics.cmdMute, "unmute", caps(volume = VolumeControl.Device(10))),
        )
        assertEquals(
            CommandDecision.SetMute(true, useSession = false),
            router.route(topics.cmdMute, "on", caps(volume = VolumeControl.Device(10))),
        )
        assertEquals(
            CommandDecision.SetMute(false, useSession = false),
            router.route(topics.cmdMute, "false", caps(volume = VolumeControl.Device(10))),
        )
    }

    @Test
    fun `mute falls back to the device path when no scale is available`() {
        assertEquals(
            CommandDecision.SetMute(true, useSession = false),
            router.route(topics.cmdMute, "mute", caps(volume = VolumeControl.Unavailable)),
        )
    }

    @Test
    fun `unknown mute payload is ignored`() {
        assertEquals(CommandDecision.Ignore, router.route(topics.cmdMute, "perhaps", caps()))
    }

    @Test
    fun `turn on and off map to play and pause`() {
        assertEquals(CommandDecision.Play, router.route(topics.cmdTurnOn, "on", caps()))
        assertEquals(CommandDecision.Pause, router.route(topics.cmdTurnOff, "off", caps()))
        assertEquals(
            CommandDecision.Ignore,
            router.route(topics.cmdTurnOn, "on", caps(transports = emptySet())),
        )
    }

    @Test
    fun `unknown topic is ignored`() {
        assertEquals(CommandDecision.Ignore, router.route("media2ha/dev/cmd/unknown", "", caps()))
    }
}
