package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightDao
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import java.time.Clock
import javax.inject.Inject

/**
 * Result of [MyDataEraser.eraseAll].
 *
 * @property fromLedger clientRecordIds of nights the ledger believed to have an output.
 * @property foundOnlyInHealthConnect our sessions found in Health Connect that the ledger did not know (e.g. after a
 *   reinstall), deleted as well.
 * @property deleted clientRecordIds passed to Health Connect for deletion.
 * @property markedDeleted ledger nights marked DELETED.
 * @property withoutClientRecordId our sessions without a clientRecordId (cannot be deleted by id; never written by
 *   this app version).
 */
data class EraseReport(
    val fromLedger: Int,
    val foundOnlyInHealthConnect: Int,
    val deleted: Int,
    val markedDeleted: Int,
    val withoutClientRecordId: Int,
)

/**
 * "Delete all my sessions from Health Connect": deletes every session this app wrote, by clientRecordId (Health
 * Connect only lets an app delete its own records, so nobody else's data can be touched). The ids come from the
 * ledger (nights with an output) plus, as a safety net, every one of our sessions found by reading our own data over
 * the full range. A running (or resumable) backfill is cancelled first, so that it cannot write the erased nights
 * again; it stops before its next night, and the night it may be finishing is erased as well because erasing waits
 * for the ledger lock. The affected nights are marked DELETED; automatic syncs do not write them again, not even when
 * a later fragment joins them (a backfill started afterwards or an explicit recompute does). Throws
 * [io.github.alien2003.pulsestages.data.hc.HealthDataException] on failure (nothing is marked then; the action can
 * simply be repeated).
 */
class MyDataEraser
    @Inject
    constructor(
        private val repository: HealthRepository,
        private val nightDao: EstimatedNightDao,
        private val backfill: BackfillEngine,
        private val logger: AppLogger,
        private val clock: Clock,
    ) {
        /** Deletes all our sessions; see the class documentation. */
        suspend fun eraseAll(): EraseReport {
            backfill.cancel()
            return LedgerLock.withLock {
                val now = clock.millis()
                val fromLedger = nightDao.clientRecordIdsWithOutput()
                val own = repository.readOwnSessions(0L, now + 1)
                val ownIds = own.mapNotNull { it.clientRecordId }
                val all = (fromLedger + ownIds).distinct()
                repository.deleteOwnSessions(all)
                val marked = nightDao.markDeleted(all, "deleted by the user (delete all my sessions)", clock.millis())
                val report =
                    EraseReport(
                        fromLedger = fromLedger.size,
                        foundOnlyInHealthConnect = ownIds.count { it !in fromLedger.toSet() },
                        deleted = all.size,
                        markedDeleted = marked,
                        withoutClientRecordId = own.count { it.clientRecordId == null },
                    )
                logger.i(TAG, "deleted our sessions: $report")
                report
            }
        }

        private companion object {
            const val TAG = "MyDataEraser"
        }
    }
