package fr.micodes.media2ha

/** Artwork source selection, so the priority order stays unit-testable. */
object Artwork {

    /**
     * Many video apps (Plex, for one) expose only a URI and no bitmap: the display icon is
     * the poster, then the plain art, then the album art.
     */
    fun pickUri(displayIconUri: String?, artUri: String?, albumArtUri: String?): String? =
        displayIconUri ?: artUri ?: albumArtUri
}
