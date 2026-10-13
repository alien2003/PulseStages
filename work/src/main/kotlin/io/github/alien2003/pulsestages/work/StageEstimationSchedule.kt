package io.github.alien2003.pulsestages.work

/** Scheduling parameters for the periodic stage estimation work. */
object StageEstimationSchedule {
    const val UNIQUE_WORK_NAME = "pulsestages.stage-estimation"
    const val MIN_REPEAT_INTERVAL_HOURS = 1L
    const val MAX_REPEAT_INTERVAL_HOURS = 24L
    const val DEFAULT_REPEAT_INTERVAL_HOURS = 6L

    /** Clamps a user-chosen repeat interval to the supported range. */
    fun repeatIntervalHours(requested: Long?): Long =
        (requested ?: DEFAULT_REPEAT_INTERVAL_HOURS).coerceIn(MIN_REPEAT_INTERVAL_HOURS, MAX_REPEAT_INTERVAL_HOURS)
}
