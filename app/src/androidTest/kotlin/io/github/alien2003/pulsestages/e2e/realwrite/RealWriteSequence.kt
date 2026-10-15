package io.github.alien2003.pulsestages.e2e.realwrite

import android.os.SystemClock
import io.github.alien2003.pulsestages.data.hc.EstimatedSessionWrite
import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.pipeline.OutputOptions
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.e2e.E2e
import io.github.alien2003.pulsestages.e2e.safety.GuardedSleepInserter
import io.github.alien2003.pulsestages.e2e.safety.SafetyGuardRepository
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.UUID

/**
 * One step of the real write.
 *
 * @property name the step.
 * @property atMs milliseconds since the first insert.
 * @property records records with the test id read back.
 * @property recordId their metadata.id.
 * @property version their clientRecordVersion.
 * @property stages their number of stages.
 */
@Serializable
data class RealWriteStep(
    val name: String,
    val atMs: Long,
    val records: Int,
    val recordId: String?,
    val version: Long?,
    val stages: Int?,
)

/**
 * The upserts and the delete of [io.github.alien2003.pulsestages.e2e.RealWriteE2eTest] on ONE record with a fresh
 * test-namespace id; every write goes through [repository] (the guarded real repository, whose reads the suite's budget
 * never holds back: [io.github.alien2003.pulsestages.e2e.safety.E2eHealth.recordedRepository]) or the guarded plain
 * insert. Everything is prepared in the constructor, before the first write. A failed read is not retried for long
 * (the repository's own retries take about 30 s): the test's `finally` deletes the record first.
 */
class RealWriteSequence(
    private val repository: HealthRepository,
    night: TestNight,
    settings: AppSettings,
) {
    /** The test record's clientRecordId (`ps-e2e:<uuid>`). */
    val clientRecordId: String = E2e.TEST_NAMESPACE + UUID.randomUUID()

    private val v1: EstimatedSessionWrite = night.write(clientRecordId, TestNight.FIRST_VERSION, settings)
    private val v2: EstimatedSessionWrite =
        movedStart(
            v1.copy(clientRecordVersion = v1.clientRecordVersion + 1, stages = TestNight.rotated(v1.stages)),
            night.candidate.startMs,
        )
    private val rawV1 = TestNight.record(v1)
    private val inserter = GuardedSleepInserter()
    private var started = 0L

    /** Every step done. */
    val steps: MutableList<RealWriteStep> = mutableListOf()

    /** Time from the first insert to the confirmed delete, once it is confirmed. */
    var aliveMs: Long? = null
        private set

    /** Whether metadata.id stayed the same through the upserts, once known. */
    var sameIdAfterUpdate: Boolean? = null
        private set

    init {
        check(SafetyGuardRepository.isTestId(clientRecordId))
        check(v2.stages != v1.stages) { "v2 must have other stages than v1" }
        check(v2.startMs != v1.startMs) { "v2 must start at another time than v1" }
    }

    /** The six steps; throws on the first broken expectation (the caller cleans up in `finally`). */
    suspend fun run() {
        started = SystemClock.elapsedRealtime()
        repository.writeEstimatedSession(v1)
        val first = single(v1, "after the first insert")
        assertStored(v1, first)
        steps += step("insert v1", first)

        repository.writeEstimatedSession(v1)
        val again = single(v1, "after inserting the same version again")
        assertStored(v1, again)
        assertEquals("metadata.id after the same version again", first.uuid, again.uuid)
        steps += step("insert v1 again", again)

        repository.writeEstimatedSession(v2)
        val updated = single(v2, "after inserting v2")
        assertStored(v2, updated)
        sameIdAfterUpdate = updated.uuid == first.uuid
        assertEquals("metadata.id after the update to v2", first.uuid, updated.uuid)
        steps += step("insert v2", updated)

        inserter.insert(rawV1)
        val ignored = single(v2, "after a plain insert of the lower v1")
        assertEquals("Health Connect replaced v2 by the lower v1", v2.clientRecordVersion, ignored.clientRecordVersion)
        assertEquals("stages after the ignored lower version", v2.stages, ignored.stages)
        steps += step("plain insert of v1 (ignored by Health Connect)", ignored)

        repository.writeEstimatedSession(v1)
        val repaired = single(v1, "after the app's verified write of v1 over v2")
        assertStored(v1, repaired)
        steps += step("VerifiedUpsert of v1 over the stale v2 (delete and insert)", repaired)

        repository.deleteOwnSessions(listOf(clientRecordId))
        val gone = withId(v1)
        steps += RealWriteStep("delete by clientRecordId", elapsed(), gone.size, null, null, null)
        assertTrue("the test record is still there after its delete: $gone", gone.isEmpty())
        aliveMs = elapsed()
    }

    private fun elapsed(): Long = SystemClock.elapsedRealtime() - started

    /**
     * [write] with its start moved by the shift of `write.startBeforeSource`, the other way than v1 (one second before
     * the night when v1 starts with it, back to the night otherwise), and its first stage moved with it: turning the
     * option on or off rewrites a record in place with another start.
     */
    private fun movedStart(
        write: EstimatedSessionWrite,
        nightStartMs: Long,
    ): EstimatedSessionWrite {
        val startMs = if (write.startMs == nightStartMs) nightStartMs - OutputOptions.START_BEFORE_SOURCE_MS else nightStartMs
        return write.copy(startMs = startMs, stages = listOf(write.stages.first().copy(startMs = startMs)) + write.stages.drop(1))
    }

    /** Our records with [write]'s clientRecordId around its night. */
    private suspend fun withId(write: EstimatedSessionWrite): List<HcSleepSession> =
        repository.readOwnSessions(write.startMs - DAY_MS, write.endMs + DAY_MS).filter { it.clientRecordId == write.clientRecordId }

    private suspend fun single(
        write: EstimatedSessionWrite,
        moment: String,
    ): HcSleepSession {
        val found = withId(write)
        assertEquals("records with the test id $moment", 1, found.size)
        return found.single()
    }

    /** The stored record is [write] as written. */
    private fun assertStored(
        write: EstimatedSessionWrite,
        stored: HcSleepSession,
    ) {
        assertEquals("clientRecordVersion", write.clientRecordVersion, stored.clientRecordVersion)
        assertEquals("stages", write.stages, stored.stages)
        assertEquals("title", write.title, stored.title)
        assertEquals("notes", write.notes, stored.notes)
        assertEquals("start", write.startMs, stored.startMs)
        assertEquals("end", write.endMs, stored.endMs)
        assertEquals("start zone offset", write.startZoneOffsetSec, stored.startZoneOffsetSec)
        assertEquals("end zone offset", write.endZoneOffsetSec, stored.endZoneOffsetSec)
        assertEquals("data origin", E2e.APP_PACKAGE, stored.packageName)
    }

    private fun step(
        name: String,
        stored: HcSleepSession,
    ): RealWriteStep = RealWriteStep(name, elapsed(), 1, stored.uuid, stored.clientRecordVersion, stored.stages.size)

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
