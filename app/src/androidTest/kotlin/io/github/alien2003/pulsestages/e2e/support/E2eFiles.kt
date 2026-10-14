package io.github.alien2003.pulsestages.e2e.support

import io.github.alien2003.pulsestages.e2e.E2e
import io.github.alien2003.pulsestages.e2e.safety.PendingTestIds
import io.github.alien2003.pulsestages.e2e.safety.ReadBudget

/** File names of the reports in the e2e directory that several tests share. */
object E2eFiles {
    /** The snapshot before the run. */
    const val SNAPSHOT_BEFORE: String = "snapshot-before.json"

    /** The snapshot after the run. */
    const val SNAPSHOT_AFTER: String = "snapshot-after.json"

    /** Files that outlive a run: what the cleanup still needs, and the read windows of the last day. */
    private val KEPT: Set<String> = setOf(PendingTestIds.FILE_NAME, DemoFlagBackup.FILE_NAME, ReadBudget.LEDGER_FILE)

    /**
     * Empties the e2e directory for a new run. Only that directory (inside the app's external files directory) is
     * touched; the [PendingTestIds] and the [DemoFlagBackup] of an interrupted earlier run are kept for the cleanup, and
     * the read ledger keeps its entries of the last 24 hours (the runner removes the whole directory after a run whose
     * cleanup it confirmed, and keeps the totals on the computer).
     */
    fun resetOutputDir() {
        E2e.outDir
            .listFiles()
            .orEmpty()
            .filter { it.name !in KEPT }
            .forEach { it.deleteRecursively() }
        ReadBudget.pruneLedger()
    }
}
