package io.github.alien2003.pulsestages.data.hc

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.algo.model.HrvSample
import io.github.alien2003.pulsestages.algo.model.RespiratoryRateSample
import io.github.alien2003.pulsestages.algo.model.Spo2Sample
import io.github.alien2003.pulsestages.algo.model.StepsSample
import java.time.Instant
import java.time.ZoneOffset

/**
 * Conversions between Health Connect records (connect-client 1.1.0) and this module's plain models. Pure functions
 * (no Android runtime needed). Times are epoch milliseconds: the platform stores milliseconds.
 */
internal object HcRecordMapper {
    /** A read [SleepSessionRecord] as [HcSleepSession] (stages sorted by start). */
    fun toSession(record: SleepSessionRecord): HcSleepSession =
        HcSleepSession(
            uuid = record.metadata.id,
            packageName = record.metadata.dataOrigin.packageName,
            startMs = record.startTime.toEpochMilli(),
            endMs = record.endTime.toEpochMilli(),
            startZoneOffsetSec = record.startZoneOffset?.totalSeconds,
            endZoneOffsetSec = record.endZoneOffset?.totalSeconds,
            stages =
                record.stages
                    .map { HcStage(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.stage) }
                    .sortedWith(compareBy({ it.startMs }, { it.endMs })),
            title = record.title,
            notes = record.notes,
            clientRecordId = record.metadata.clientRecordId,
            clientRecordVersion = record.metadata.clientRecordVersion,
            lastModifiedMs = record.metadata.lastModifiedTime.toEpochMilli(),
        )

    /**
     * Our session as a [SleepSessionRecord]: explicit zone offsets, `Metadata.unknownRecordingMethod` with the
     * deterministic clientRecordId / version and [device] (the phone that computed it). Throws
     * [IllegalArgumentException] when the session or its stages are invalid (Jetpack validation).
     */
    fun toRecord(
        write: EstimatedSessionWrite,
        device: Device,
    ): SleepSessionRecord =
        SleepSessionRecord(
            startTime = Instant.ofEpochMilli(write.startMs),
            startZoneOffset = ZoneOffset.ofTotalSeconds(write.startZoneOffsetSec),
            endTime = Instant.ofEpochMilli(write.endMs),
            endZoneOffset = ZoneOffset.ofTotalSeconds(write.endZoneOffsetSec),
            metadata =
                Metadata.unknownRecordingMethod(
                    clientRecordId = write.clientRecordId,
                    clientRecordVersion = write.clientRecordVersion,
                    device = device,
                ),
            title = write.title,
            notes = write.notes,
            stages =
                write.stages.map { stage ->
                    SleepSessionRecord.Stage(
                        startTime = Instant.ofEpochMilli(stage.startMs),
                        endTime = Instant.ofEpochMilli(stage.endMs),
                        stage = stage.type,
                    )
                },
        )

    /** Samples of [records] with time in `[startMs, endMs)`, sorted by time. */
    fun heartRate(
        records: List<HeartRateRecord>,
        startMs: Long,
        endMs: Long,
    ): List<HrSample> =
        records
            .asSequence()
            .flatMap { it.samples.asSequence() }
            .map { HrSample(it.time.toEpochMilli(), it.beatsPerMinute.toDouble()) }
            .filter { it.timeMs in startMs until endMs }
            .sortedBy { it.timeMs }
            .toList()

    /** Step records overlapping `[startMs, endMs)`, sorted by start. */
    fun steps(
        records: List<StepsRecord>,
        startMs: Long,
        endMs: Long,
    ): List<StepsSample> =
        records
            .map { StepsSample(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.count) }
            .filter { it.endMs > startMs && it.startMs < endMs }
            .sortedWith(compareBy({ it.startMs }, { it.endMs }))

    /** SpO2 measurements with time in `[startMs, endMs)`, sorted by time. */
    fun spo2(
        records: List<OxygenSaturationRecord>,
        startMs: Long,
        endMs: Long,
    ): List<Spo2Sample> =
        records
            .map { Spo2Sample(it.time.toEpochMilli(), it.percentage.value) }
            .filter { it.timeMs in startMs until endMs }
            .sortedBy { it.timeMs }

    /** Respiratory-rate measurements with time in `[startMs, endMs)`, sorted by time. */
    fun respiratoryRate(
        records: List<RespiratoryRateRecord>,
        startMs: Long,
        endMs: Long,
    ): List<RespiratoryRateSample> =
        records
            .map { RespiratoryRateSample(it.time.toEpochMilli(), it.rate) }
            .filter { it.timeMs in startMs until endMs }
            .sortedBy { it.timeMs }

    /** HRV (RMSSD) measurements with time in `[startMs, endMs)`, sorted by time. */
    fun hrv(
        records: List<HeartRateVariabilityRmssdRecord>,
        startMs: Long,
        endMs: Long,
    ): List<HrvSample> =
        records
            .map { HrvSample(it.time.toEpochMilli(), it.heartRateVariabilityMillis) }
            .filter { it.timeMs in startMs until endMs }
            .sortedBy { it.timeMs }

    /** Per-origin statistics of [sessions], ordered by session count (descending) then package name. */
    fun originStats(sessions: List<HcSleepSession>): List<SleepOriginStats> =
        sessions
            .groupBy { it.packageName }
            .map { (packageName, list) -> SleepOriginStats(packageName, list.size, list.maxOf { it.endMs }) }
            .sortedWith(compareByDescending<SleepOriginStats> { it.sessionCount }.thenBy { it.packageName })
}
