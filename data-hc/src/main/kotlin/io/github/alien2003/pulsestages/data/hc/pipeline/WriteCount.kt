package io.github.alien2003.pulsestages.data.hc.pipeline

/**
 * The sessions a run wrote to Health Connect so far. The caller creates one per run (a sync, a backfill or a
 * recompute) and passes it down; [NightPipeline] counts each session as soon as it is written, so the count is kept
 * also when the run ends with an exception or is cancelled after some writes. Only a process death loses it. One run
 * at a time: not thread-safe.
 */
class WriteCount {
    /** New or recomputed sessions written ([PipelineReport.estimated]). */
    var written: Int = 0
        private set

    /** Written sessions rewritten because their input changed ([PipelineReport.refreshed]). */
    var rewritten: Int = 0
        private set

    /** Counts [step] of a [NightRun] when it wrote or rewrote a session. */
    internal fun add(step: Step) {
        when ((step as? Step.Done)?.result) {
            NightResult.WRITTEN -> written++
            NightResult.REFRESHED -> rewritten++
            else -> Unit
        }
    }
}
