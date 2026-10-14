package io.github.alien2003.pulsestages.e2e.safety

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * How the records of one data origin and record type changed between two snapshots.
 *
 * @property type "sleep", "heartRate" or "steps".
 * @property packageName the data origin.
 * @property countBefore records before.
 * @property countAfter records after.
 * @property removed ids that were there before and are gone.
 * @property modified ids whose lastModifiedTime changed (with the new time).
 * @property added ids that appeared during the run (with their lastModifiedTime).
 */
@Serializable
data class OriginDiff(
    val type: String,
    val packageName: String,
    val countBefore: Int,
    val countAfter: Int,
    val removed: List<String>,
    val modified: List<KeyEntry>,
    val added: List<KeyEntry>,
)

/**
 * The verdict of [SnapshotComparison.compare].
 *
 * @property passed no failure.
 * @property failures what makes the comparison fail.
 * @property warnings what is reported but does not fail it.
 * @property hints explanations of the failures.
 * @property appRecordsBefore the app's own records (outside the test namespace) before.
 * @property appRecordsAfter the same after.
 * @property testRecordsAfter records of the suite (`ps-e2e:`) left after the run.
 * @property origins every origin whose records differ.
 */
@Serializable
data class ComparisonReport(
    val passed: Boolean,
    val failures: List<String>,
    val warnings: List<String>,
    val hints: List<String>,
    val appRecordsBefore: Int,
    val appRecordsAfter: Int,
    val testRecordsAfter: Int,
    val origins: List<OriginDiff>,
)

/**
 * Compares two [SafetySnapshot]s:
 * - the app's own records outside the test namespace must be identical (same ids, versions, lastModifiedTime, start,
 *   end, zone offsets, stages, title, notes) and none may be added or removed;
 * - no record of the suite (`ps-e2e:`) may be left;
 * - the app's settings file, its sync state and the demo-mode flag must be unchanged ([LocalAppState]);
 * - other apps' records that existed before must all still exist with the same lastModifiedTime (sleep of all time,
 *   heart rate and steps of the snapshot window); records other apps ADDED during the run are reported as warnings,
 *   since PulseStages cannot write in another app's name (and holds no write permission for heart rate or steps).
 */
object SnapshotComparison {
    /** Compares [before] with [after]. */
    fun compare(
        before: SafetySnapshot,
        after: SafetySnapshot,
    ): ComparisonReport {
        val failures = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        compareOwn(before, after, failures, warnings)
        compareLocal(before.local, after.local, failures)
        val origins =
            diffs("sleep", before.otherSleep, after.otherSleep) +
                diffs("heartRate", before.heartRate, after.heartRate) +
                diffs("steps", before.steps, after.steps)
        origins.forEach { diff ->
            val where = "${diff.type} of ${diff.packageName}"
            if (diff.removed.isNotEmpty()) failures += "$where: ${diff.removed.size} record(s) that existed before are gone"
            if (diff.modified.isNotEmpty()) {
                val at = diff.modified.maxOf { it.lastModifiedMs }
                failures += "$where: ${diff.modified.size} record(s) were modified (latest change at ${Instant.ofEpochMilli(at)})"
            }
            if (diff.added.isNotEmpty()) warnings += "$where: ${diff.added.size} record(s) were added during the run by that app"
        }
        if (before.window != after.window) failures += "the snapshots cover different ranges: ${before.window} vs ${after.window}"
        if (before.runId != after.runId) warnings += "the snapshots belong to different runs (${before.runId} / ${after.runId})"
        return ComparisonReport(
            passed = failures.isEmpty(),
            failures = failures,
            warnings = warnings,
            hints = hints(failures),
            appRecordsBefore = before.appRecords.size,
            appRecordsAfter = after.appRecords.size,
            testRecordsAfter = after.testRecords.size,
            origins = origins,
        )
    }

    private fun compareOwn(
        before: SafetySnapshot,
        after: SafetySnapshot,
        failures: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val left = before.appRecords.associateBy { it.id }
        val right = after.appRecords.associateBy { it.id }
        left.forEach { (id, record) ->
            val now = right[id]
            when {
                now == null -> failures += "the app's record $id (${record.clientRecordId}) is gone"
                now != record -> failures += "the app's record $id (${record.clientRecordId}) changed: ${fieldsChanged(record, now)}"
            }
        }
        (right.keys - left.keys).forEach { id ->
            failures += "a new record of the app appeared: $id (${right.getValue(id).clientRecordId})"
        }
        if (after.testRecords.isNotEmpty()) {
            val ids = after.testRecords.map { it.clientRecordId }
            failures += "${ids.size} record(s) of the suite ($ids) are still in Health Connect"
        }
        if (before.testRecords.isNotEmpty()) {
            warnings += "${before.testRecords.size} record(s) of the suite were left by an earlier run before this one started"
        }
        if (after.ownSeenInAnyOriginRead != after.own.size) {
            warnings += "the any-origin read saw ${after.ownSeenInAnyOriginRead} of the app's ${after.own.size} own sessions"
        }
    }

    private fun fieldsChanged(
        a: OwnRecord,
        b: OwnRecord,
    ): String =
        listOfNotNull(
            "clientRecordId".takeIf { a.clientRecordId != b.clientRecordId },
            "version ${a.clientRecordVersion} -> ${b.clientRecordVersion}".takeIf { a.clientRecordVersion != b.clientRecordVersion },
            "lastModifiedTime ${a.lastModifiedMs} -> ${b.lastModifiedMs}".takeIf { a.lastModifiedMs != b.lastModifiedMs },
            "start/end".takeIf { a.startMs != b.startMs || a.endMs != b.endMs },
            "zone offsets".takeIf { a.startZoneOffsetSec != b.startZoneOffsetSec || a.endZoneOffsetSec != b.endZoneOffsetSec },
            "stages".takeIf { a.stagesSha256 != b.stagesSha256 || a.stageCount != b.stageCount },
            "title".takeIf { a.title != b.title },
            "notes".takeIf { a.notesSha256 != b.notesSha256 || a.notesLength != b.notesLength },
        ).joinToString()

    private fun diffs(
        type: String,
        before: Map<String, Fingerprint>,
        after: Map<String, Fingerprint>,
    ): List<OriginDiff> =
        (before.keys + after.keys).sorted().mapNotNull { packageName ->
            val left = before[packageName]
            val right = after[packageName]
            if (left?.sha256 == right?.sha256 && left?.count == right?.count) return@mapNotNull null
            val leftById = left?.records.orEmpty().associateBy { it.id }
            val rightById = right?.records.orEmpty().associateBy { it.id }
            OriginDiff(
                type = type,
                packageName = packageName,
                countBefore = left?.count ?: 0,
                countAfter = right?.count ?: 0,
                removed = (leftById.keys - rightById.keys).sorted(),
                modified = leftById.values.mapNotNull { old -> rightById[old.id]?.takeIf { it.lastModifiedMs != old.lastModifiedMs } },
                added = (rightById.keys - leftById.keys).sorted().map { rightById.getValue(it) },
            )
        }

    private fun compareLocal(
        before: LocalAppState,
        after: LocalAppState,
        failures: MutableList<String>,
    ) {
        if (before.settingsSha256 != after.settingsSha256) failures += "the app's settings file changed during the run"
        if (before.syncStateSha256 != after.syncStateSha256) failures += "the app's sync state changed during the run"
        if (before.demoFlag != after.demoFlag) failures += "the demo-mode flag is ${after.demoFlag}, it was ${before.demoFlag}"
    }

    /** Explanations of the failures. */
    private fun hints(failures: List<String>): List<String> =
        if (failures.isEmpty()) {
            emptyList()
        } else {
            listOf(
                "The app's own background work cannot run inside the suite's processes (their WorkManager is an in-memory " +
                    "one), and the app's jobs are cancelled by every instrumentation start until the runner opens the app " +
                    "at the end. A change of the app's records or local state therefore points at the suite, or at the app " +
                    "opened by hand during the run.",
            )
        }
}
