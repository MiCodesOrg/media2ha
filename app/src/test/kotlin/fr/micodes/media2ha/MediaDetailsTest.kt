package fr.micodes.media2ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaDetailsTest {

    @Test
    fun `reads season and episode from the common subtitle shapes`() {
        assertEquals("1" to "2", MediaDetails.season("S1E2", null) to MediaDetails.episode("S1E2"))
        assertEquals("1" to "2", MediaDetails.season("S01E02", null) to MediaDetails.episode("S01E02"))
        assertEquals("1" to "2", MediaDetails.season("1x02", null) to MediaDetails.episode("1x02"))
        assertEquals("1" to "2", MediaDetails.season("Season 1 Episode 2", null) to MediaDetails.episode("Season 1 Episode 2"))
        assertEquals("1" to "2", MediaDetails.season("Saison 1 Épisode 2", null) to MediaDetails.episode("Saison 1 Épisode 2"))
        assertEquals("3" to "4", MediaDetails.season("s3.e4", null) to MediaDetails.episode("s3.e4"))
    }

    @Test
    fun `episode alone is read without a season`() {
        assertEquals("2", MediaDetails.episode("Episode 2"))
        assertEquals("2", MediaDetails.episode("E02"))
        assertEquals("2", MediaDetails.episode("Ep. 2"))
        assertNull(MediaDetails.season("Episode 2", null))
    }

    @Test
    fun `the album is a season fallback, as Plex reports it`() {
        assertEquals("4", MediaDetails.season(null, "Season 4"))
        assertEquals("4", MediaDetails.season(null, "Saison 4"))
        assertEquals("4", MediaDetails.season(null, "S4"))
    }

    @Test
    fun `the subtitle wins over the album`() {
        assertEquals("1", MediaDetails.season("S1E2", "Season 4"))
    }

    @Test
    fun `unknown or non-episode text yields nothing`() {
        assertNull(MediaDetails.season(null, null))
        assertNull(MediaDetails.season("", ""))
        assertNull(MediaDetails.season("Danse à la campagne", "Greatest Hits"))
        assertNull(MediaDetails.episode(null))
        assertNull(MediaDetails.episode("Danse à la campagne"))
    }
}
