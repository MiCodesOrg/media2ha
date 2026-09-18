package fr.micodes.media2ha

/** Topic layout, per the spec: runtime namespace is `media2ha/<device_id>`. */
class Topics(deviceId: String) {
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

    val cmd = "$base/cmd"
    val cmdPlay = "$cmd/play"
    val cmdPause = "$cmd/pause"
    val cmdPlayPause = "$cmd/playpause"
    val cmdNext = "$cmd/next"
    val cmdPrevious = "$cmd/previous"
    val cmdVolume = "$cmd/volume"

    val cmdWildcard = "$cmd/+"

    /** Discovery topic is fixed by the bkbilly component. */
    val discovery = "homeassistant/media_player/$deviceId/config"

    companion object {
        const val PAYLOAD_ONLINE = "online"
        const val PAYLOAD_OFFLINE = "offline"
    }
}
