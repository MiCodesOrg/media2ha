package fr.micodes.media2ha

import java.util.Locale

/** Artwork source selection, so the priority order stays unit-testable. */
object Artwork {

    /**
     * Many video apps (Plex, for one) expose only a URI and no bitmap: the display icon is
     * the poster, then the plain art, then the album art.
     */
    fun pickUri(displayIconUri: String?, artUri: String?, albumArtUri: String?): String? =
        displayIconUri ?: artUri ?: albumArtUri

    /**
     * Plex serves posters from its direct-connect host (`*.plex.direct`), which points at
     * the user's own server but whose certificate older devices (Android 6) cannot validate.
     * Local and private hosts have the same problem. Artwork is non-sensitive, so an
     * untrusted certificate is accepted for those hosts rather than dropping the poster.
     */
    fun allowsUntrustedCertificate(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val name = host.lowercase(Locale.US)
        if (name == "localhost" || name.endsWith(".local") || name.endsWith(".plex.direct")) return true
        val parts = name.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull()?.takeIf { value -> value in 0..255 } }
        if (octets.any { it == null }) return false
        val (first, second) = octets[0]!! to octets[1]!!
        return first == 10 || first == 127 ||
            (first == 192 && second == 168) ||
            (first == 172 && second in 16..31)
    }
}
