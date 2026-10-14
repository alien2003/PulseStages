package io.github.alien2003.pulsestages.e2e.shadow

import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.pipeline.NightInputReader
import io.github.alien2003.pulsestages.data.hc.pipeline.SessionText
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Checks of a shadow scenario and the per-session report. */
object ShadowChecks {
    private const val MINUTE_MS = 60_000L
    private const val SECOND_MS = 1000.0
    private const val MAX_REASON = 300
    private val LOCAL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** [WriteInvariants] of every write in [writes], prefixed with the night. */
    fun invariantProblems(
        writes: List<CapturedWrite>,
        settings: AppSettings,
        defaultTitle: String,
    ): List<String> =
        writes.flatMap { captured ->
            WriteInvariants
                .problems(
                    captured.write,
                    settings,
                    defaultTitle,
                ).map { "${captured.write.clientRecordId.take(ID_PREFIX)}…: $it" }
        }

    /**
     * The ledger and the simulated Health Connect agree: every WRITTEN night has exactly its record (same version and
     * id), every record belongs to a WRITTEN night, and no two records describe the same sleep (overlapping by more
     * than half of the shorter one): one output per night.
     */
    suspend fun consistencyProblems(env: ShadowEnvironment): List<String> {
        val problems = mutableListOf<String>()
        val written = env.database.estimatedNightDao().findOverlapping(Long.MIN_VALUE, Long.MAX_VALUE, listOf(NightStatus.WRITTEN))
        val stored = env.health.stored
        written.forEach { night ->
            val record = stored[night.clientRecordId]
            when {
                record == null -> {
                    problems += "WRITTEN night ${night.clientRecordId.take(ID_PREFIX)}… has no record"
                }

                record.clientRecordVersion != night.clientRecordVersion -> {
                    problems +=
                        "night ${night.clientRecordId.take(
                            ID_PREFIX,
                        )}…: ledger v${night.clientRecordVersion}, record v${record.clientRecordVersion}"
                }

                record.uuid != night.hcRecordId -> {
                    problems +=
                        "night ${night.clientRecordId.take(ID_PREFIX)}…: ledger id ${night.hcRecordId}, record id ${record.uuid}"
                }
            }
        }
        val writtenIds = written.mapTo(HashSet()) { it.clientRecordId }
        (stored.keys - writtenIds).forEach { problems += "record ${it.take(ID_PREFIX)}… belongs to no WRITTEN night" }
        val records = stored.values.sortedBy { it.startMs }
        records.forEachIndexed { index, a ->
            records.drop(index + 1).takeWhile { it.startMs < a.endMs }.forEach { b ->
                if (overlapsMostly(a, b)) {
                    problems +=
                        "records ${a.clientRecordId?.take(ID_PREFIX)}… and ${b.clientRecordId?.take(ID_PREFIX)}… describe the same sleep"
                }
            }
        }
        return problems
    }

    /** Number of written stages per STAGE_TYPE_* value. */
    fun stageTypes(writes: List<CapturedWrite>): Map<Int, Int> =
        writes
            .flatMap { it.write.stages }
            .groupingBy { it.type }
            .eachCount()
            .toSortedMap()

    /** The per-session report of [env] (every ledger night) under [settings]. */
    suspend fun sessionReports(
        env: ShadowEnvironment,
        settings: AppSettings,
        reads: CachingReadRepository,
        appRecords: Map<String, HcSleepSession>,
    ): List<SessionReport> {
        val nights = env.database.estimatedNightDao().findOverlapping(Long.MIN_VALUE, Long.MAX_VALUE, NightStatus.entries.toList())
        val stored = env.health.stored
        val marginMs = settings.algo.preprocess.contextMarginMin * MINUTE_MS
        return nights.map { night ->
            val heartRate =
                reads.cachedHeartRate(
                    night.sessionStartMs - marginMs,
                    night.sessionEndMs + marginMs,
                    NightInputReader.heartRatePackages(night.sourcePackage, settings),
                )
            val record = stored[night.clientRecordId]
            val app = appRecords[night.clientRecordId]
            SessionReport(
                clientRecordId = night.clientRecordId,
                sourcePackage = night.sourcePackage,
                date = local(night.sessionStartMs, night.startZoneOffsetSec).take(DATE_LENGTH),
                start = local(night.sessionStartMs, night.startZoneOffsetSec),
                end = local(night.sessionEndMs, night.endZoneOffsetSec),
                lengthMin = (night.sessionEndMs - night.sessionStartMs) / MINUTE_MS,
                mode = night.mode.name,
                outcome = night.status.name,
                reason = night.statusReason?.take(MAX_REASON),
                estimator = night.estimator.name,
                coverage = SessionText.round3(night.coverage),
                confidence = SessionText.round3(night.confidence),
                shares = shares(night),
                warnings = night.warnings.map { it.name },
                hrSamples = night.hrSamples,
                hrMedianIntervalSec =
                    heartRate?.let {
                        medianIntervalSec(
                            it.map { s ->
                                s.timeMs
                            },
                            night.sessionStartMs,
                            night.sessionEndMs,
                        )
                    },
                estimateMs = env.estimator.millisFor(night.sessionStartMs),
                writtenVersion = record?.clientRecordVersion,
                stageCount = record?.stages?.size,
                matchesAppRecord = app?.let { record != null && it.stages == record.stages },
            )
        }
    }

    private fun shares(night: EstimatedNightEntity): Map<String, Double> {
        val minutes =
            mapOf(
                "awake" to night.awakeMin,
                "light" to night.lightMin,
                "deep" to night.deepMin,
                "rem" to night.remMin,
                "unknown" to night.unknownMin,
            )
        val total = minutes.values.sum()
        return if (total <= 0.0) emptyMap() else minutes.mapValues { SessionText.round3(it.value / total) }
    }

    /** Median time between consecutive samples of [times] inside `[startMs, endMs)`, in seconds; null with fewer than two. */
    fun medianIntervalSec(
        times: List<Long>,
        startMs: Long,
        endMs: Long,
    ): Double? {
        val inside = times.filter { it in startMs until endMs }.sorted()
        if (inside.size < 2) return null
        val gaps = inside.zipWithNext { a, b -> b - a }.sorted()
        val middle = gaps.size / 2
        val median = if (gaps.size % 2 == 1) gaps[middle].toDouble() else (gaps[middle - 1] + gaps[middle]) / 2.0
        return SessionText.round3(median / SECOND_MS)
    }

    private fun local(
        epochMs: Long,
        offsetSec: Int,
    ): String = Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.ofTotalSeconds(offsetSec)).format(LOCAL_TIME)

    private fun overlapsMostly(
        a: HcSleepSession,
        b: HcSleepSession,
    ): Boolean {
        val overlap = minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs)
        val shorter = minOf(a.endMs - a.startMs, b.endMs - b.startMs)
        return overlap > 0 && overlap * 2 > shorter
    }

    private const val ID_PREFIX = 14
    private const val DATE_LENGTH = 10
}
