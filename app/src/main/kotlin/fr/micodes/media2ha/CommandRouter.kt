package fr.micodes.media2ha

import kotlin.math.roundToInt

/** What the active session can do, observed by the adapter. */
data class CommandCapabilities(
    val transports: Set<TransportCapability>,
    val playing: Boolean,
    val volume: VolumeControl,
)

enum class TransportCapability { PLAY, PAUSE, SKIP_TO_NEXT, SKIP_TO_PREVIOUS, SEEK_TO }

sealed interface VolumeControl {
    /** The session exposes its own absolute scale (set with [MediaController.setVolumeTo]). */
    data class SessionAbsolute(val max: Int) : VolumeControl

    /** The session has no usable scale; volume maps to the device STREAM_MUSIC. */
    data class Device(val max: Int) : VolumeControl

    data object Unavailable : VolumeControl
}

/** What the adapter should do about one received command. */
sealed interface CommandDecision {
    data object Play : CommandDecision
    data object Pause : CommandDecision
    data object Next : CommandDecision
    data object Previous : CommandDecision
    data class SeekTo(val seconds: Long) : CommandDecision
    data class SetVolume(val index: Int, val useSession: Boolean) : CommandDecision
    data class SetMute(val mute: Boolean, val useSession: Boolean) : CommandDecision
    data object Ignore : CommandDecision
}

/**
 * The command router module: turns a received command into a decision, from the topic, the
 * payload and what the session can do. Pure; the adapter executes the decision.
 */
class CommandRouter(private val topics: Topics) {

    fun route(topic: String, payload: String, capabilities: CommandCapabilities): CommandDecision =
        when (topic) {
            topics.cmdPlay, topics.cmdTurnOn -> if (capabilities.can(TransportCapability.PLAY)) {
                CommandDecision.Play
            } else {
                CommandDecision.Ignore
            }

            topics.cmdPause, topics.cmdTurnOff -> if (capabilities.can(TransportCapability.PAUSE)) {
                CommandDecision.Pause
            } else {
                CommandDecision.Ignore
            }

            topics.cmdPlayPause -> toggle(capabilities)

            topics.cmdNext -> if (capabilities.can(TransportCapability.SKIP_TO_NEXT)) {
                CommandDecision.Next
            } else {
                CommandDecision.Ignore
            }

            topics.cmdPrevious -> if (capabilities.can(TransportCapability.SKIP_TO_PREVIOUS)) {
                CommandDecision.Previous
            } else {
                CommandDecision.Ignore
            }

            topics.cmdSeek -> seek(payload, capabilities)
            topics.cmdVolume -> volume(payload, capabilities)
            topics.cmdMute -> mute(payload, capabilities)
            else -> CommandDecision.Ignore
        }

    private fun toggle(capabilities: CommandCapabilities): CommandDecision = when {
        capabilities.playing -> if (capabilities.can(TransportCapability.PAUSE)) {
            CommandDecision.Pause
        } else {
            CommandDecision.Ignore
        }

        else -> if (capabilities.can(TransportCapability.PLAY)) {
            CommandDecision.Play
        } else {
            CommandDecision.Ignore
        }
    }

    private fun seek(payload: String, capabilities: CommandCapabilities): CommandDecision {
        val seconds = payload.trim().toLongOrNull() ?: return CommandDecision.Ignore
        return if (capabilities.can(TransportCapability.SEEK_TO)) {
            CommandDecision.SeekTo(seconds)
        } else {
            CommandDecision.Ignore
        }
    }

    private fun volume(payload: String, capabilities: CommandCapabilities): CommandDecision {
        val value = payload.trim().toFloatOrNull()?.coerceIn(0f, 1f) ?: return CommandDecision.Ignore
        return when (val control = capabilities.volume) {
            is VolumeControl.SessionAbsolute ->
                CommandDecision.SetVolume(index(value, control.max), useSession = true)

            is VolumeControl.Device ->
                CommandDecision.SetVolume(index(value, control.max), useSession = false)

            VolumeControl.Unavailable -> CommandDecision.Ignore
        }
    }

    private fun mute(payload: String, capabilities: CommandCapabilities): CommandDecision {
        val mute = when (payload.trim().lowercase()) {
            "mute", "muted", "on", "true", "1" -> true
            "unmute", "unmuted", "off", "false", "0" -> false
            else -> return CommandDecision.Ignore
        }
        val useSession = capabilities.volume is VolumeControl.SessionAbsolute
        return CommandDecision.SetMute(mute, useSession)
    }

    private fun index(value: Float, max: Int): Int =
        (value * max).roundToInt().coerceIn(0, max)

    private fun CommandCapabilities.can(capability: TransportCapability): Boolean =
        capability in transports
}
