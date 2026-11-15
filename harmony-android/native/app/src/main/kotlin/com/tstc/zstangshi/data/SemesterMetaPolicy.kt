package com.tstc.zstangshi.data

/**
 * Semester metadata ownership rules.
 *
 * The archive is the only writable source of truth. timetable.json and Prefs
 * are mirrors for legacy readers.
 */
object SemesterMetaPolicy {

    const val MIN_WEEKS = 1

    /** Keep the archive policy aligned with the existing settings/UI limit. */
    const val MAX_WEEKS = 30
    const val DEFAULT_WEEKS = 20

    @JvmStatic
    fun clampWeeks(weeks: Int): Int = Math.max(MIN_WEEKS, Math.min(MAX_WEEKS, weeks))

    /**
     * Backfill only a missing archive start date from a legacy timetable.json
     * mirror. An existing archive date always wins, even if the mirror is newer,
     * because background writes must not revive stale dates.
     */
    @JvmStatic
    fun shouldSeedStartDate(
        archiveSemesterId: String?,
        mirrorSemesterId: String?,
        archiveStartDate: String?,
        mirrorStartDate: String?
    ): Boolean {
        if (!sameSemester(archiveSemesterId, mirrorSemesterId)) return false
        val hasArchiveStart = !archiveStartDate.isNullOrEmpty()
        val hasMirrorStart = !mirrorStartDate.isNullOrEmpty()
        return !hasArchiveStart && hasMirrorStart
    }

    /**
     * Total weeks are an archive value too. A legacy mirror may fill the field
     * only when the archive JSON did not contain that field at all; a present
     * value (including the default 20) must not be replaced by the mirror.
     */
    @JvmStatic
    fun shouldSeedTotalWeeks(
        archiveSemesterId: String?,
        mirrorSemesterId: String?,
        archiveHasTotalWeeks: Boolean,
        mirrorTotalWeeks: Int
    ): Boolean {
        if (!sameSemester(archiveSemesterId, mirrorSemesterId)) return false
        if (archiveHasTotalWeeks) return false
        return mirrorTotalWeeks in MIN_WEEKS..MAX_WEEKS
    }

    private fun sameSemester(archiveSemesterId: String?, mirrorSemesterId: String?): Boolean =
        !archiveSemesterId.isNullOrEmpty() && archiveSemesterId == mirrorSemesterId

    /**
     * Archive wins. A missing archive date is preserved as missing rather than
     * copied from the mirror; explicit user saves update the archive first.
     */
    @JvmStatic
    fun resolveStartDate(archiveStartDate: String?): String = archiveStartDate ?: ""

    @JvmStatic
    fun resolveTotalWeeks(archiveTotalWeeks: Int): Int = clampWeeks(archiveTotalWeeks)
}
