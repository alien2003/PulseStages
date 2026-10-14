package io.github.alien2003.pulsestages.e2e.safety

import android.os.SystemClock
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.isRetryable
import io.github.alien2003.pulsestages.e2e.E2e
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream

/**
 * The clientRecordIds the suite is about to write, recorded BEFORE the first insert (`e2e/pending-test-ids.txt`), so
 * that a cleanup after a crash or a timeout deletes them first, without having to read anything. Only ids of the test
 * namespace are ever written to or read from it.
 */
object PendingTestIds {
    /** File name inside the e2e directory; kept when the snapshot test clears the directory. */
    const val FILE_NAME: String = "pending-test-ids.txt"

    private val file: File get() = E2e.file(FILE_NAME)

    /** Records [clientRecordId] (must be in the test namespace), synchronously on disk. */
    fun add(clientRecordId: String) {
        SafetyGuardRepository.requireTestNamespace("PendingTestIds.add", listOf(clientRecordId))
        FileOutputStream(file, true).use { out ->
            out.write("$clientRecordId\n".toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    /** The recorded ids (test namespace only). */
    fun read(): List<String> =
        if (file.isFile) {
            file
                .readLines()
                .map { it.trim() }
                .filter(SafetyGuardRepository::isTestId)
                .distinct()
        } else {
            emptyList()
        }

    /** Forgets the recorded ids (after a verified cleanup). */
    fun clear() {
        file.delete()
    }
}

/**
 * Result of [NamespaceCleaner.cleanup].
 *
 * @property pending ids recorded in [PendingTestIds] before their insert; deleted first, without a read.
 * @property found test records found by reading the app's own sessions of all history.
 * @property deleted ids passed to deleteOwnSessions (by clientRecordId).
 * @property remaining test records still found by the last verification read (must be empty).
 * @property verified the last verification read succeeded (otherwise [remaining] is unknown and the cleanup failed).
 * @property attempts delete / verify rounds after the delete of the pending ids.
 * @property errors what failed on the way (each retried until the cleanup's patience ran out).
 */
@Serializable
data class CleanupReport(
    val pending: List<String>,
    val found: List<String>,
    val deleted: List<String>,
    val remaining: List<String>,
    val verified: Boolean,
    val attempts: Int,
    val errors: List<String>,
) {
    /** No record of the suite is left, confirmed by a successful read. */
    val confirmed: Boolean get() = verified && remaining.isEmpty()
}

/**
 * Deletes every record of the suite from the real Health Connect:
 * 1. the [PendingTestIds] are deleted BY CLIENTRECORDID at once, before anything is read (Health Connect's framework
 *    ignores ids that do not exist), so a read quota that is used up cannot keep a test record alive;
 * 2. the app's own sessions of all history are read with the own-package DataOrigin filter, every clientRecordId in the
 *    test namespace among them is deleted by clientRecordId, and a verification read must find none.
 * Every delete goes through the guarded [repository] (which refuses any id outside `ps-e2e:`) and is checked here
 * again. [repository] must not hold reads back ([E2eHealth.recordedRepository]); instead, Health Connect's own rate
 * limit and transient errors are waited out here for up to [patienceMs] in all, with progress lines. Never throws
 * for a Health Connect error: the report says whether the result is [CleanupReport.confirmed]. Idempotent and safe
 * to run at any time; never deletes by time range and never touches an id outside `ps-e2e:`.
 */
class NamespaceCleaner(
    private val repository: HealthRepository,
    private val patienceMs: Long = DEFAULT_PATIENCE_MS,
) {
    private val errors = mutableListOf<String>()
    private var deadline = 0L

    /** Deletes and verifies (see the class documentation). */
    suspend fun cleanup(): CleanupReport {
        errors.clear()
        deadline = SystemClock.elapsedRealtime() + patienceMs
        val deleted = mutableListOf<String>()
        val pending =
            runCatching { PendingTestIds.read() }.getOrElse { e ->
                errors += "the pending ids could not be read: ${e.message}"
                emptyList()
            }
        if (pending.isNotEmpty() && delete(pending)) deleted += pending
        val firstRead = read()
        val found = firstRead.orEmpty()
        var remaining: List<String>? = firstRead
        var targets = found
        var attempts = 0
        while (targets.isNotEmpty() && attempts < MAX_ATTEMPTS) {
            attempts++
            if (delete(targets)) deleted += targets
            remaining = read()
            targets = remaining.orEmpty()
            if (targets.isNotEmpty()) delay(RETRY_DELAY_MS * attempts)
        }
        // The last read failed (its patience is spent by now): one more try.
        if (remaining == null) remaining = read()
        val report =
            CleanupReport(
                pending = pending,
                found = found,
                deleted = deleted.distinct(),
                remaining = remaining.orEmpty(),
                verified = remaining != null,
                attempts = attempts,
                errors = errors.toList(),
            )
        if (report.confirmed) PendingTestIds.clear()
        return report
    }

    /** The clientRecordIds of the suite in Health Connect now, or null when no read succeeded within the patience. */
    private suspend fun read(): List<String>? =
        patiently("read the app's own sessions") {
            repository
                .readOwnSessions(0L, System.currentTimeMillis() + FUTURE_MS)
                .mapNotNull { it.clientRecordId }
                .filter(SafetyGuardRepository::isTestId)
                .distinct()
        }

    /** Deletes [ids] (all in the test namespace) by clientRecordId; false when it did not succeed within the patience. */
    private suspend fun delete(ids: List<String>): Boolean {
        SafetyGuardRepository.requireTestNamespace("cleanup", ids)
        return patiently("delete ${ids.size} test record id(s)") { repository.deleteOwnSessions(ids) } != null
    }

    /** Runs [block], retrying Health Connect's rate limit and transient errors until the deadline; null when it failed. */
    private suspend fun <T : Any> patiently(
        what: String,
        block: suspend () -> T,
    ): T? {
        var waits = 0
        while (true) {
            try {
                return block()
            } catch (e: HealthDataException) {
                errors += "$what: ${e.javaClass.simpleName}: ${e.message}"
                val left = deadline - SystemClock.elapsedRealtime()
                if (!e.isRetryable || left <= 0) return null
                waits++
                val wait = minOf(WAIT_STEP_MS * waits, MAX_WAIT_MS, left)
                E2e.progress(
                    "cleanup: could not $what (${e.javaClass.simpleName}); trying again in ${wait / SECOND_MS} s " +
                        "(gives up in ${left / SECOND_MS} s)",
                )
                delay(wait)
            }
        }
    }

    /** Constants. */
    companion object {
        /**
         * How long the cleanup waits out Health Connect's rate limit and transient errors in all: longer than the
         * 15-minute rate-limit window, shorter than the runner's timeout of the cleanup step (20 minutes).
         */
        const val DEFAULT_PATIENCE_MS: Long = 16L * 60 * 1000

        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2_000L
        private const val WAIT_STEP_MS = 30_000L
        private const val MAX_WAIT_MS = 120_000L
        private const val SECOND_MS = 1_000L
        private const val FUTURE_MS = 2L * 24 * 60 * 60 * 1000
    }
}
