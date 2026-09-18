package fr.micodes.media2ha

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Builds the retained discovery payload for the bkbilly `mqtt_media_player` component. */
object DiscoveryPayload {

    fun build(config: Media2HaConfig, topics: Topics): String {
        val availability = JSONObject()
            .put("topic", topics.availability)
            .put("payload_available", Topics.PAYLOAD_ONLINE)
            .put("payload_not_available", Topics.PAYLOAD_OFFLINE)

        val device = JSONObject()
            .put("identifiers", JSONArray().put(config.deviceId))
            .put("name", config.deviceName)
            .put("manufacturer", "micodes")
            .put("model", Build.MODEL ?: "Android")
            .put("sw_version", BuildConfig.VERSION_NAME)

        return JSONObject()
            .put("name", config.deviceName)
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
            .put("device", device)
            .toString()
    }
}
