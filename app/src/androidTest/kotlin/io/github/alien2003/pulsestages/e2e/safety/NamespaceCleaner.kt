package io.github.alien2003.pulsestages.e2e.safety

import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.e2e.E2e
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.io.File

/**
 * The clientRecordIds the suite is about to write, recorded BEFORE the first insert (`e2e/pending-test-ids.txt`), so
 * that a cleanup after a crash or a timeout deletes them even when the read of the own sessions misses them. Only ids
 * of the test namespace are ever written to or read from it.
 */
object PendingTestIds {
    /** File name inside the e2e directory; kept when the snapshot test clears the directory. */
    const val FILE_NAME: String = "pending-test-ids.txt"

    private val file: File get() = E2e.file(FILE_NAME)

    /** Records [clientRecordId] (must be in the test namespace). */
    fun add(clientRecordId: String) {
        SafetyGuardRepository.requireTestNamespace("PendingTestIds.add", listOf(clientRecordId))
        file.appendText("$clientRecordId\n")
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
 * @property found test records found by reading the app's own sessions of all history.
 * @property pending ids recorded in [PendingTestIds] before their insert.
 * @property deleted ids passed to deleteOwnSessions (by clientRecordId).
 * @property remaining test records still found by the verification read (must be empty).
 * @property attempts delete / verify rounds.
 */
@Serializable
data class CleanupReport(
    val found: List<String>,
    val pending: List<String>,
    val deleted: List<String>,
    val remaining: List<String>,
    val attempts: Int,
)

/**
 * Deletes every record of the suite from the real Health Connect: the app's own sessions of all history are read with
 * the own-package DataOrigin filter, every clientRecordId in the test namespace (plus the [PendingTestIds]) is deleted
 * BY CLIENTRECORDID through the guarded [repository] (which refuses any other id), and a verification read must find
 * none. Idempotent and safe to run at any time; never deletes by time range and never touches an id outside
 * `ps-e2e:`.
 */
class NamespaceCleaner(
    private val repository: HealthRepository,
) {
    /** Deletes and verifies; [NamespaceCleaner] retries up to [MAX_ATTEMPTS] rounds. */
    suspend fun cleanup(): CleanupReport {
        val found = testIdsInHealthConnect()
        val pending = PendingTestIds.read()
        val deleted = mutableListOf<String>()
        var targets = (found + pending).distinct()
        var remaining = found
        var attempts = 0
        while (targets.isNotEmpty() && attempts < MAX_ATTEMPTS) {
            attempts++
            SafetyGuardRepository.requireTestNamespace("cleanup", targets)
            repository.deleteOwnSessions(targets)
            deleted += targets
            remaining = testIdsInHealthConnect()
            targets = remaining
            if (remaining.isNotEmpty()) delay(RETRY_DELAY_MS * attempts)
        }
        if (attempts == 0) remaining = testIdsInHealthConnect()
        if (remaining.isEmpty()) PendingTestIds.clear()
        return CleanupReport(found, pending, deleted.distinct(), remaining, attempts)
    }

    /** The clientRecordIds of the suite currently in Health Connect (own-package read of all history). */
    suspend fun testIdsInHealthConnect(): List<String> =
        repository
            .readOwnSessions(0L, System.currentTimeMillis() + FUTURE_MS)
            .mapNotNull { it.clientRecordId }
            .filter(SafetyGuardRepository::isTestId)
            .distinct()

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 2_000L
        const val FUTURE_MS = 2L * 24 * 60 * 60 * 1000
    }
}
