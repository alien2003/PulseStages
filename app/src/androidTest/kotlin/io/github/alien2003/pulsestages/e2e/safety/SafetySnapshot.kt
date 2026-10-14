package io.github.alien2003.pulsestages.e2e.safety

import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.local.sync.SyncStateStore
import io.github.alien2003.pulsestages.e2e.E2e
import io.github.alien2003.pulsestages.e2e.support.AppSettingsFile
import io.github.alien2003.pulsestages.e2e.support.DemoFlag
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

/**
 * The time ranges a snapshot covers. The "after" snapshot reuses the ranges of the "before" one, so that records do
 * not drop out of (or into) a moving window during the run.
 *
 * @property sleepFromMs start of the sleep-session range (0: all history).
 * @property sleepToMs end of the sleep-session range (a day after the "before" snapshot).
 * @property seriesFromMs start of the heart-rate / steps range (30 days before the "before" snapshot).
 * @property seriesToMs end of the heart-rate / steps range.
 */
@Serializable
data class SnapshotWindow(
    val sleepFromMs: Long,
    val sleepToMs: Long,
    val seriesFromMs: Long,
    val seriesToMs: Long,
)

/**
 * One of the app's own sleep sessions, with everything that must stay byte-identical.
 *
 * @property id metadata.id.
 * @property clientRecordId metadata.clientRecordId.
 * @property clientRecordVersion metadata.clientRecordVersion.
 * @property lastModifiedMs metadata.lastModifiedTime, epoch ms.
 * @property startMs session start.
 * @property endMs session end.
 * @property startZoneOffsetSec start zone offset.
 * @property endZoneOffsetSec end zone offset.
 * @property stageCount number of stages.
 * @property stagesSha256 SHA-256 of the stages (start, end, type of each, in order).
 * @property title the title.
 * @property notesSha256 SHA-256 of the notes (they list other apps' record ids, so only the hash is kept).
 * @property notesLength length of the notes.
 */
@Serializable
data class OwnRecord(
    val id: String,
    val clientRecordId: String?,
    val clientRecordVersion: Long,
    val lastModifiedMs: Long,
    val startMs: Long,
    val endMs: Long,
    val startZoneOffsetSec: Int?,
    val endZoneOffsetSec: Int?,
    val stageCount: Int,
    val stagesSha256: String,
    val title: String?,
    val notesSha256: String?,
    val notesLength: Int,
) {
    /** True for a record of the suite (`ps-e2e:`). */
    val isTestRecord: Boolean get() = SafetyGuardRepository.isTestId(clientRecordId)

    /** Constructors. */
    companion object {
        /** The fingerprint of [session]. */
        fun of(session: HcSleepSession): OwnRecord =
            OwnRecord(
                id = session.uuid,
                clientRecordId = session.clientRecordId,
                clientRecordVersion = session.clientRecordVersion,
                lastModifiedMs = session.lastModifiedMs,
                startMs = session.startMs,
                endMs = session.endMs,
                startZoneOffsetSec = session.startZoneOffsetSec,
                endZoneOffsetSec = session.endZoneOffsetSec,
                stageCount = session.stages.size,
                stagesSha256 = sha256(session.stages.joinToString(";") { "${it.startMs},${it.endMs},${it.type}" }),
                title = session.title,
                notesSha256 = session.notes?.let(::sha256),
                notesLength = session.notes?.length ?: 0,
            )
    }
}

/**
 * The records of one data origin and record type.
 *
 * @property count number of records.
 * @property sha256 SHA-256 over the sorted `id,lastModified` lines.
 * @property records the sorted keys (for telling additions from changes when the fingerprints differ).
 */
@Serializable
data class Fingerprint(
    val count: Int,
    val sha256: String,
    val records: List<KeyEntry>,
)

/**
 * Id and last modification of one record.
 *
 * @property id metadata.id.
 * @property lastModifiedMs metadata.lastModifiedTime, epoch ms.
 */
@Serializable
data class KeyEntry(
    val id: String,
    val lastModifiedMs: Long,
)

/**
 * The app's local state that the suite must leave alone and can check without opening any of the app's stores: the
 * bytes of the settings DataStore file, the bytes of the sync-state DataStore file (the Changes-API token and the run
 * state: they change only when the app's own sync runs) and the debug build's demo-mode flag. Files are only read.
 *
 * @property settingsSha256 SHA-256 of `files/datastore/app_settings.json`, or null without the file.
 * @property syncStateSha256 SHA-256 of `files/datastore/sync_state.preferences_pb`, or null without the file.
 * @property demoFlag the persisted demo-mode flag.
 */
@Serializable
data class LocalAppState(
    val settingsSha256: String?,
    val syncStateSha256: String?,
    val demoFlag: DemoFlag,
) {
    /** Reading. */
    companion object {
        /** The state now. */
        fun now(): LocalAppState =
            LocalAppState(
                settingsSha256 = AppSettingsFile.currentSha256(),
                syncStateSha256 = fileSha256(File(E2e.app.filesDir, "datastore/${SyncStateStore.FILE_NAME}.preferences_pb")),
                demoFlag = DemoFlag.read(),
            )

        private fun fileSha256(file: File): String? = file.takeIf { it.isFile }?.readBytes()?.let(::sha256)
    }
}

/**
 * What Health Connect holds before or after the run, as far as the suite could possibly touch it, plus the context.
 *
 * @property runId id of the run.
 * @property label "before", "after", ...
 * @property takenAtMs when it was taken.
 * @property window the ranges read.
 * @property appVersionName installed versionName of the app.
 * @property appVersionCode installed versionCode of the app.
 * @property device manufacturer and model.
 * @property sdkInt Build.VERSION.SDK_INT.
 * @property own the app's own sleep sessions of all time, read with the own-package DataOrigin filter.
 * @property ownSeenInAnyOriginRead how many of the app's own sessions the any-origin read returned (a cross-check).
 * @property otherSleep other apps' sleep sessions of all time, per package.
 * @property heartRate heart-rate records in [SnapshotWindow.seriesFromMs]..[SnapshotWindow.seriesToMs], per package.
 * @property steps steps records in the same range, per package.
 * @property local the app's settings file, sync state and demo-mode flag.
 * @property readCallsCharged read calls of this run so far (after the snapshot).
 */
@Serializable
data class SafetySnapshot(
    val runId: String,
    val label: String,
    val takenAtMs: Long,
    val window: SnapshotWindow,
    val appVersionName: String,
    val appVersionCode: Long,
    val device: String,
    val sdkInt: Int,
    val own: List<OwnRecord>,
    val ownSeenInAnyOriginRead: Int,
    val otherSleep: Map<String, Fingerprint>,
    val heartRate: Map<String, Fingerprint>,
    val steps: Map<String, Fingerprint>,
    val local: LocalAppState,
    val readCallsCharged: Int,
) {
    /** The app's own records outside the test namespace (the 8 real nights). */
    val appRecords: List<OwnRecord> get() = own.filterNot { it.isTestRecord }

    /** The suite's own records (`ps-e2e:`); must be empty after the run. */
    val testRecords: List<OwnRecord> get() = own.filter { it.isTestRecord }
}

/**
 * Takes [SafetySnapshot]s. Read-only: own sessions through the guarded [repository], every other record through
 * [reader]; both charge their reads to the run's budget as they were built to ([ReadCharging]).
 */
class SafetySnapshots(
    private val repository: HealthRepository,
    private val reader: ReadOnlyHealthConnect = ReadOnlyHealthConnect(),
) {
    /** The app's own sessions of all history (own-package DataOrigin filter), sorted by id. */
    suspend fun ownRecords(nowMs: Long = System.currentTimeMillis()): List<OwnRecord> =
        repository.readOwnSessions(0L, nowMs + FUTURE_MS).map(OwnRecord::of).sortedBy { it.id }

    /** A full snapshot labelled [label]; [window] defaults to the ranges around now. */
    suspend fun take(
        label: String,
        window: SnapshotWindow? = null,
    ): SafetySnapshot {
        val now = System.currentTimeMillis()
        val ranges = window ?: SnapshotWindow(0L, now + FUTURE_MS, now - SERIES_DAYS * DAY_MS, now + FUTURE_MS)
        val ownPackage = E2e.app.packageName
        E2e.progress("snapshot $label: the app's own sessions")
        val own = ownRecords(now)
        E2e.progress("snapshot $label: sleep sessions of every app")
        val sleep = reader.sleepKeys(ranges.sleepFromMs, ranges.sleepToMs)
        E2e.progress("snapshot $label: heart rate of the last ${SERIES_DAYS} days")
        val heartRate = reader.heartRateKeys(ranges.seriesFromMs, ranges.seriesToMs)
        E2e.progress("snapshot $label: steps of the last ${SERIES_DAYS} days")
        val steps = reader.stepsKeys(ranges.seriesFromMs, ranges.seriesToMs)
        val info = E2e.app.packageManager.getPackageInfo(ownPackage, 0)
        return SafetySnapshot(
            runId = E2e.runId,
            label = label,
            takenAtMs = now,
            window = ranges,
            appVersionName = info.versionName.orEmpty(),
            appVersionCode = PackageInfoCompat.getLongVersionCode(info),
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            sdkInt = Build.VERSION.SDK_INT,
            own = own,
            ownSeenInAnyOriginRead = sleep.count { it.packageName == ownPackage },
            otherSleep = fingerprints(sleep.filter { it.packageName != ownPackage }),
            heartRate = fingerprints(heartRate),
            steps = fingerprints(steps),
            local = LocalAppState.now(),
            readCallsCharged = ReadBudget.shared().spent(),
        )
    }

    /** Constants and helpers. */
    companion object {
        /** Days of heart rate and steps covered. */
        const val SERIES_DAYS: Long = 30

        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Ranges end a day after now: no session starts in the future, but clocks may disagree. */
        private const val FUTURE_MS = DAY_MS

        /** Per-package fingerprints of [keys]. */
        fun fingerprints(keys: List<RecordKey>): Map<String, Fingerprint> =
            keys
                .groupBy { it.packageName }
                .toSortedMap()
                .mapValues { (_, list) ->
                    val sorted = list.map { KeyEntry(it.id, it.lastModifiedMs) }.sortedWith(compareBy({ it.id }, { it.lastModifiedMs }))
                    Fingerprint(sorted.size, sha256(sorted.joinToString("\n") { "${it.id},${it.lastModifiedMs}" }), sorted)
                }
    }
}

/** Lower-case hex SHA-256 of the UTF-8 bytes of [text]. */
fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

/** Lower-case hex SHA-256 of [bytes]. */
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
