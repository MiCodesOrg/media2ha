package fr.micodes.media2ha

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Home Assistant discovery seam: owns the naming scheme ([topics]) and builds the
 * retained discovery payload. Android-free, so the payload contract is unit-testable.
 */
class Discovery(
    val deviceId: String,
    val deviceName: String,
    discoveryPrefix: String,
    val manufacturer: String = "micodes",
    val model: String = "Android",
    val swVersion: String = "1.0",
) {

    val topics = Topics(deviceId, discoveryPrefix)

    fun payload(): String {
        val availability = JSONObject()
            .put("topic", topics.availability)
            .put("payload_available", Topics.PAYLOAD_ONLINE)
            .put("payload_not_available", Topics.PAYLOAD_OFFLINE)

        val device = JSONObject()
            .put("identifiers", JSONArray().put(deviceId))
            .put("name", deviceName)
            .put("manufacturer", manufacturer)
            .put("model", model)
            .put("sw_version", swVersion)

        return JSONObject()
            .put("name", deviceName)
            .put("availability", availability)
            .put("state_state_topic", topics.state)
            .put("state_title_topic", topics.title)
            .put("state_artist_topic", topics.artist)
            .put("state_album_topic", topics.album)
            .put("state_duration_topic", topics.duration)
            .put("state_position_topic", topics.position)
            .put("state_volume_topic", topics.volume)
            .put("state_albumart_topic", topics.albumArt)
            .put("state_mediatype_topic", topics.mediatype)
            .put("state_mute_topic", topics.mute)
            .put("state_source_topic", topics.source)
            .put("command_volume_topic", topics.cmdVolume)
            .put("command_play_topic", topics.cmdPlay)
            .put("command_play_payload", "play")
            .put("command_pause_topic", topics.cmdPause)
            .put("command_pause_payload", "pause")
            .put("command_playpause_topic", topics.cmdPlayPause)
            .put("command_playpause_payload", "playpause")
            .put("command_next_topic", topics.cmdNext)
            .put("command_next_payload", "next")
            .put("command_previous_topic", topics.cmdPrevious)
            .put("command_previous_payload", "previous")
            .put("command_mute_topic", topics.cmdMute)
            .put("command_mute_on_payload", "mute")
            .put("command_mute_off_payload", "unmute")
            .put("command_seek_topic", topics.cmdSeek)
            .put("command_turn_on_topic", topics.cmdTurnOn)
            .put("command_turn_on_payload", "on")
            .put("command_turn_off_topic", topics.cmdTurnOff)
            .put("command_turn_off_payload", "off")
            .put("device", device)
            .toString()
    }
}
