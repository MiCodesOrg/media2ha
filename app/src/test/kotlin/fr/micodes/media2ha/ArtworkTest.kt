package fr.micodes.media2ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkTest {

    @Test
    fun `prefers the display icon uri`() {
        assertEquals("icon", Artwork.pickUri("icon", "art", "album"))
    }

    @Test
    fun `falls back to art then album art`() {
        assertEquals("art", Artwork.pickUri(null, "art", "album"))
        assertEquals("album", Artwork.pickUri(null, null, "album"))
    }

    @Test
    fun `no uri available`() {
        assertNull(Artwork.pickUri(null, null, null))
    }

    @Test
    fun `plex direct-connect hosts may use an untrusted certificate`() {
        assertTrue(Artwork.allowsUntrustedCertificate("abc123def456.plex.direct"))
    }

    @Test
    fun `local and private hosts may use an untrusted certificate`() {
        assertTrue(Artwork.allowsUntrustedCertificate("localhost"))
        assertTrue(Artwork.allowsUntrustedCertificate("media.lan.local"))
        assertTrue(Artwork.allowsUntrustedCertificate("127.0.0.1"))
        assertTrue(Artwork.allowsUntrustedCertificate("10.0.0.4"))
        assertTrue(Artwork.allowsUntrustedCertificate("192.168.1.231"))
        assertTrue(Artwork.allowsUntrustedCertificate("172.16.5.9"))
        assertTrue(Artwork.allowsUntrustedCertificate("172.31.255.1"))
    }

    @Test
    fun `public hosts keep certificate validation`() {
        assertFalse(Artwork.allowsUntrustedCertificate("plex.example.com"))
        assertFalse(Artwork.allowsUntrustedCertificate("8.8.8.8"))
        assertFalse(Artwork.allowsUntrustedCertificate("172.32.0.1"))
        assertFalse(Artwork.allowsUntrustedCertificate(null))
        assertFalse(Artwork.allowsUntrustedCertificate(""))
    }
}
