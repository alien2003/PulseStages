package io.github.alien2003.pulsestages.e2e.support

import io.github.alien2003.pulsestages.e2e.E2e
import io.github.alien2003.pulsestages.e2e.safety.PendingTestIds

/** File names of the reports in the e2e directory that several tests share. */
object E2eFiles {
    /** The snapshot before the run. */
    const val SNAPSHOT_BEFORE: String = "snapshot-before.json"

    /** The snapshot after the run. */
    const val SNAPSHOT_AFTER: String = "snapshot-after.json"

    /**
     * Empties the e2e directory for a new run. Only that directory (inside the app's external files directory) is
     * touched, and the [PendingTestIds] of an interrupted earlier run are kept for the cleanup.
     */
    fun resetOutputDir() {
        E2e.outDir
            .listFiles()
            .orEmpty()
            .filter { it.name != PendingTestIds.FILE_NAME }
            .forEach { it.deleteRecursively() }
    }
}
