package fr.micodes.media2ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
