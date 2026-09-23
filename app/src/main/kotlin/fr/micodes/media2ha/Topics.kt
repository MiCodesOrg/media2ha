package fr.micodes.media2ha

/** Topic layout, per the spec: runtime namespace is `media2ha/<device_id>`. */
class Topics(deviceId: String, discoveryPrefix: String = "homeassistant") {
    private val base = "media2ha/$deviceId"

    val availability = "$base/availability"
    val state = "$base/state"
    val title = "$base/title"
    val artist = "$base/artist"
    val album = "$base/album"
    val mediatype = "$base/mediatype"
    val duration = "$base/duration"
    val position = "$base/position"
    val volume = "$base/volume"
    val albumArt = "$base/albumart"
    val mute = "$base/mute"
    val source = "$base/source"
    val summary = "$base/summary"
    val season = "$base/season"
    val episode = "$base/episode"
    val series = "$base/series"
    val year = "$base/year"

    val cmd = "$base/cmd"
    val cmdPlay = "$cmd/play"
    val cmdPause = "$cmd/pause"
    val cmdPlayPause = "$cmd/playpause"
    val cmdNext = "$cmd/next"
    val cmdPrevious = "$cmd/previous"
    val cmdVolume = "$cmd/volume"
    val cmdMute = "$cmd/mute"
    val cmdSeek = "$cmd/seek"
    val cmdTurnOn = "$cmd/turn_on"
    val cmdTurnOff = "$cmd/turn_off"

    val cmdWildcard = "$cmd/+"

    /** Discovery topic: `<discovery_prefix>/media_player/<device_id>/config`. */
    val discovery = "${discoveryPrefix.trim('/')}/media_player/$deviceId/config"

    companion object {
        const val PAYLOAD_ONLINE = "online"
        const val PAYLOAD_OFFLINE = "offline"
    }
}
