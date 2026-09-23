package fr.micodes.media2ha

/**
 * Best-effort extraction of TV details Android does not model: season and episode. Apps
 * mostly stash them in the display subtitle ("S1E2", "1x02", "Season 1 Episode 2") or,
 * like Plex, put the season in the album ("Season 4").
 */
object MediaDetails {

    private val SEASON_WITH_EPISODE = listOf(
        Regex("""\bs(?:eason|aison)?\s*0*(\d+)\s*[.\-:]?\s*[eéEÉ](?:p|pisode|pisode)?\s*0*(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""\b0*(\d+)\s*x\s*0*(\d+)\b""", RegexOption.IGNORE_CASE),
    )
    private val SEASON_ALONE =
        Regex("""\bs(?:eason|aison)?\s*0*(\d+)\b""", RegexOption.IGNORE_CASE)
    private val EPISODE_ALONE =
        Regex("""\b[eéEÉ](?:p|pisode|pisode)?[.\-:]?\s*0*(\d+)\b""", RegexOption.IGNORE_CASE)

    /** The season from the subtitle ("S1E2"), else from the album ("Season 4"); null when unknown. */
    fun season(subtitle: String?, album: String?): String? =
        subtitle?.trim()?.takeIf { it.isNotEmpty() }?.let { findSeason(it) }
            ?: album?.trim()?.takeIf { it.isNotEmpty() }?.let { findSeason(it) }

    /** The episode from the subtitle ("S1E2", "Episode 2"); null when unknown. */
    fun episode(subtitle: String?): String? {
        val text = subtitle?.trim().orEmpty()
        if (text.isEmpty()) return null
        for (regex in SEASON_WITH_EPISODE) {
            regex.find(text)?.let { return it.groupValues[2] }
        }
        return EPISODE_ALONE.find(text)?.groupValues?.get(1)
    }

    private fun findSeason(text: String): String? {
        for (regex in SEASON_WITH_EPISODE) {
            regex.find(text)?.let { return it.groupValues[1] }
        }
        return SEASON_ALONE.find(text)?.groupValues?.get(1)
    }
}
