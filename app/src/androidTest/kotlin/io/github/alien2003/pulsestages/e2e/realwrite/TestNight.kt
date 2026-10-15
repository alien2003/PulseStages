package io.github.alien2003.pulsestages.e2e.realwrite

import android.os.Build
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.room.Room
import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.params.NapOutputPolicy
import io.github.alien2003.pulsestages.data.hc.EstimatedSessionWrite
import io.github.alien2003.pulsestages.data.hc.HcStage
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.pipeline.NightCandidate
import io.github.alien2003.pulsestages.data.hc.pipeline.NightCandidateBuilder
import io.github.alien2003.pulsestages.data.hc.pipeline.NightInputReader
import io.github.alien2003.pulsestages.data.hc.pipeline.NightSpan
import io.github.alien2003.pulsestages.data.hc.pipeline.OutputOptions
import io.github.alien2003.pulsestages.data.hc.pipeline.ResourceDefaultTitleProvider
import io.github.alien2003.pulsestages.data.hc.pipeline.SessionText
import io.github.alien2003.pulsestages.data.hc.pipeline.StageMapping
import io.github.alien2003.pulsestages.data.hc.pipeline.SystemZoneOffsetProvider
import io.github.alien2003.pulsestages.data.hc.usecase.PersonalModelStore
import io.github.alien2003.pulsestages.data.local.db.PulseStagesDatabase
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.e2e.E2e
import io.github.alien2003.pulsestages.e2e.safety.OwnRecord
import io.github.alien2003.pulsestages.work.estimate.AndroidModelAssetSource
import io.github.alien2003.pulsestages.work.estimate.BundledHybridModelProvider
import io.github.alien2003.pulsestages.work.estimate.SleepStagerNightEstimator
import kotlinx.coroutines.Dispatchers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The night [io.github.alien2003.pulsestages.e2e.RealWriteE2eTest] writes, estimated from real data before anything is
 * written.
 *
 * @property candidate the source night.
 * @property estimate the estimate of the app's real estimator.
 * @property startMs the start of the record as the app would write it (`write.startBeforeSource`).
 * @property stages the stages as the app would write them.
 * @property paramsHash the params hash the app would put into the notes.
 */
class TestNight(
    val candidate: NightCandidate,
    val estimate: NightEstimate,
    val startMs: Long,
    val stages: List<HcStage>,
    val paramsHash: String,
) {
    /**
     * The session as the app would write it, but with [clientRecordId] (test namespace), [version] and a title that
     * says it is a short-lived test record (should any other app see it during its lifetime of seconds).
     */
    fun write(
        clientRecordId: String,
        version: Long,
        settings: AppSettings,
    ): EstimatedSessionWrite {
        val title =
            SessionText.title(
                template = settings.write.titleTemplate,
                defaultTemplate = ResourceDefaultTitleProvider(E2e.app).defaultTitleTemplate(),
                estimator = estimate.estimator,
                algoVersion = estimate.algoVersion,
                confidence = estimate.confidence,
            )
        return EstimatedSessionWrite(
            clientRecordId = clientRecordId,
            clientRecordVersion = version,
            startMs = startMs,
            endMs = candidate.endMs,
            startZoneOffsetSec = candidate.startZoneOffsetSec,
            endZoneOffsetSec = candidate.endZoneOffsetSec,
            stages = stages,
            title = (TITLE_PREFIX + title).take(SessionText.MAX_TITLE_LENGTH),
            notes =
                SessionText.notes(
                    estimator = estimate.estimator,
                    mode = estimate.mode,
                    algoVersion = estimate.algoVersion,
                    paramsHash = paramsHash,
                    coverage = estimate.coverage,
                    confidence = estimate.confidence,
                    sourceUuids = candidate.sourceUuids,
                    hrSamples = estimate.hrSampleCount,
                    calibrated = estimate.flags.calibrated,
                ),
        )
    }

    /** Finding and preparing the night. */
    companion object {
        /** Title prefix of the test record. */
        const val TITLE_PREFIX: String = "[PulseStages E2E test record, deleted within a minute] "

        /** First clientRecordVersion of the test record: what the app writes for a first estimate. */
        const val FIRST_VERSION: Long = ALGO_VERSION * 1000L

        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MIN_AGE_DAYS = 30
        private const val MAX_TRIES = 8

        /**
         * The most recent night of the enabled sources that ended at least 30 days ago, that no record of the app
         * overlaps ([own], any namespace), and that the app's estimator estimates; null when there is none (the test is
         * then skipped). Only reads Health Connect.
         */
        suspend fun find(
            repository: HealthRepository,
            settings: AppSettings,
            own: List<OwnRecord>,
            logger: AppLogger,
            nowMs: Long,
        ): TestNight? {
            val candidates = candidates(repository, settings, own, nowMs)
            return if (candidates.isEmpty()) null else firstEstimated(repository, settings, candidates, logger)
        }

        private suspend fun candidates(
            repository: HealthRepository,
            settings: AppSettings,
            own: List<OwnRecord>,
            nowMs: Long,
        ): List<NightCandidate> {
            val ownPackage = E2e.app.packageName
            val cutoff = nowMs - MIN_AGE_DAYS * DAY_MS
            val packages = settings.sources.sourcePackages.filterTo(sortedSetOf()) { it != ownPackage }
            val sessions = if (packages.isEmpty()) emptyList() else repository.readSleepSessions(0L, cutoff, packages)

            // From the start of the record the app would write (one second earlier with write.startBeforeSource).
            fun overlapsNoOwnRecord(night: NightCandidate): Boolean {
                val start = OutputOptions.recordStartMs(night.startMs, settings.write)
                return own.none { it.startMs < night.endMs && it.endMs > start }
            }

            return NightCandidateBuilder(ownPackage, SystemZoneOffsetProvider)
                .build(sessions, settings.sources, settings.write.overwritePolicy, settings.algo)
                .candidates
                .filter { it.endMs <= cutoff }
                .filter(::overlapsNoOwnRecord)
                .sortedWith(compareBy<NightCandidate> { it.mode(settings.algo) != SessionMode.NIGHT }.thenByDescending { it.startMs })
                .take(MAX_TRIES)
        }

        /** The first of [candidates] the app's estimator (bundled model, no personal model, no baseline) estimates. */
        @Suppress("InjectDispatcher") // The suite wires the estimator itself, like the app's WorkModule.
        private suspend fun firstEstimated(
            repository: HealthRepository,
            settings: AppSettings,
            candidates: List<NightCandidate>,
            logger: AppLogger,
        ): TestNight? {
            val granted = repository.grantedPermissions()
            val database = Room.inMemoryDatabaseBuilder(E2e.app, PulseStagesDatabase::class.java).build()
            try {
                val estimator =
                    SleepStagerNightEstimator(
                        BundledHybridModelProvider(AndroidModelAssetSource(E2e.app.assets), logger, Dispatchers.IO),
                        PersonalModelStore(database.personalModelDao(), Clock.systemUTC(), logger),
                    )
                val inputs = NightInputReader(repository, database.nightBaselineDao())
                val paramsHash = estimator.paramsHash(settings.algo, OutputOptions.hashExtra(settings, granted))
                return candidates.firstNotNullOfOrNull { candidate ->
                    val input = inputs.read(NightSpan.of(candidate), settings, granted)
                    val estimate = (estimator.estimate(input, settings.algo) as? EstimationOutcome.Estimated)?.estimate
                    estimate?.let { prepared(candidate, it, settings, paramsHash) }
                }
            } finally {
                database.close()
            }
        }

        /** The night with the stages the app would write, or null when they would not be valid. */
        private fun prepared(
            candidate: NightCandidate,
            estimate: NightEstimate,
            settings: AppSettings,
            paramsHash: String,
        ): TestNight? {
            val sleepWake = estimate.mode == SessionMode.NAP && settings.algo.napMode.outputPolicy == NapOutputPolicy.SLEEP_WAKE
            val mapped =
                if (sleepWake) {
                    StageMapping.toSleepWakeHcStages(estimate.stages, settings.write.awakeAsLight)
                } else {
                    StageMapping.toHcStages(estimate.stages, settings.write.awakeAsLight)
                }
            val epochMs = settings.algo.preprocess.epochSec * SECOND_MS
            // As the app: against the night first, then the record with its first stage extended back to its start.
            if (StageMapping.validate(mapped, candidate.startMs, candidate.endMs, epochMs).isNotEmpty()) return null
            val startMs = OutputOptions.recordStartMs(candidate.startMs, settings.write)
            val stages = StageMapping.startingAt(mapped, startMs)
            return TestNight(candidate, estimate, startMs, stages, paramsHash)
                .takeIf { StageMapping.validate(stages, startMs, candidate.endMs, epochMs).isEmpty() }
        }

        /** [stages] with every type replaced (AWAKE → LIGHT → DEEP → REM → AWAKE, others → LIGHT), adjacent equal ones merged. */
        fun rotated(stages: List<HcStage>): List<HcStage> {
            val merged = mutableListOf<HcStage>()
            stages.forEach { stage ->
                val type =
                    when (stage.type) {
                        SleepSessionRecord.STAGE_TYPE_AWAKE -> SleepSessionRecord.STAGE_TYPE_LIGHT
                        SleepSessionRecord.STAGE_TYPE_LIGHT -> SleepSessionRecord.STAGE_TYPE_DEEP
                        SleepSessionRecord.STAGE_TYPE_DEEP -> SleepSessionRecord.STAGE_TYPE_REM
                        SleepSessionRecord.STAGE_TYPE_REM -> SleepSessionRecord.STAGE_TYPE_AWAKE
                        else -> SleepSessionRecord.STAGE_TYPE_LIGHT
                    }
                val last = merged.lastOrNull()
                if (last != null && last.type == type && last.endMs == stage.startMs) {
                    merged[merged.lastIndex] = last.copy(endMs = stage.endMs)
                } else {
                    merged += HcStage(stage.startMs, stage.endMs, type)
                }
            }
            return merged
        }

        /** [write] as a plain SleepSessionRecord, as the app's mapper builds it (phone as the device). */
        fun record(write: EstimatedSessionWrite): SleepSessionRecord =
            SleepSessionRecord(
                startTime = Instant.ofEpochMilli(write.startMs),
                startZoneOffset = ZoneOffset.ofTotalSeconds(write.startZoneOffsetSec),
                endTime = Instant.ofEpochMilli(write.endMs),
                endZoneOffset = ZoneOffset.ofTotalSeconds(write.endZoneOffsetSec),
                metadata =
                    Metadata.unknownRecordingMethod(
                        clientRecordId = write.clientRecordId,
                        clientRecordVersion = write.clientRecordVersion,
                        device = Device(type = Device.TYPE_PHONE, manufacturer = Build.MANUFACTURER, model = Build.MODEL),
                    ),
                title = write.title,
                notes = write.notes,
                stages =
                    write.stages.map {
                        SleepSessionRecord.Stage(
                            Instant.ofEpochMilli(it.startMs),
                            Instant.ofEpochMilli(it.endMs),
                            it.type,
                        )
                    },
            )

        private const val SECOND_MS = 1000L
    }
}
