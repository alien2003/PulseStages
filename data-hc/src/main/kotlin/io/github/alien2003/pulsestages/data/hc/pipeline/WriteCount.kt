package io.github.alien2003.pulsestages.data.hc.pipeline

/**
 * The sessions a run wrote to Health Connect so far. The caller creates one per run (a sync, a backfill or a
 * recompute) and passes it down; [NightRun] counts each session right after Health Connect accepted its insert, so the
 * count is kept also when a later step of that night fails (the ledger update, superseding an older output), when the
 * run ends with an exception, or when it is cancelled after some writes. Only a process death loses it. One run at a
 * time: not thread-safe.
 */
class WriteCount {
    /** New or recomputed sessions written ([PipelineReport.estimated]). */
    var written: Int = 0
        private set

    /** Written sessions rewritten because their input changed ([PipelineReport.refreshed]). */
    var rewritten: Int = 0
        private set

    /** Counts a session written for the first time or recomputed. */
    internal fun countWritten() {
        written++
    }

    /** Counts a written session rewritten because its input changed. */
    internal fun countRewritten() {
        rewritten++
    }
}
