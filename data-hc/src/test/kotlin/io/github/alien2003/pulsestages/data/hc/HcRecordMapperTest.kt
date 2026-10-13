package io.github.alien2003.pulsestages.data.hc

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.algo.model.StepsSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.ZoneOffset

class HcRecordMapperTest {
    private val t0 = 1_791_849_600_000L
    private val minute = 60_000L
    private val phone = Device(type = Device.TYPE_PHONE, manufacturer = "OPPO", model = "CPH2765")

    private fun write(stages: List<HcStage>) =
        EstimatedSessionWrite(
            clientRecordId = "ps:0123",
            clientRecordVersion = 1003,
            startMs = t0,
            endMs = t0 + 480 * minute,
            startZoneOffsetSec = 10_800,
            endZoneOffsetSec = 7_200,
            stages = stages,
            title = "Sleep stages from heart rate (estimate)",
            notes = "{\"algo\":\"HYBRID\"}",
        )

    @Test
    fun `our session becomes a SleepSessionRecord with unknown recording method, the phone and explicit offsets`() {
        val stages = listOf(HcStage(t0, t0 + 200 * minute, 4), HcStage(t0 + 200 * minute, t0 + 480 * minute, 5))
        val record = HcRecordMapper.toRecord(write(stages), phone)

        assertEquals(Instant.ofEpochMilli(t0), record.startTime)
        assertEquals(Instant.ofEpochMilli(t0 + 480 * minute), record.endTime)
        assertEquals(ZoneOffset.ofHours(3), record.startZoneOffset)
        assertEquals(ZoneOffset.ofHours(2), record.endZoneOffset)
        assertEquals(Metadata.RECORDING_METHOD_UNKNOWN, record.metadata.recordingMethod)
        assertEquals("ps:0123", record.metadata.clientRecordId)
        assertEquals(1003L, record.metadata.clientRecordVersion)
        assertEquals(phone, record.metadata.device)
        assertEquals("Sleep stages from heart rate (estimate)", record.title)
        assertEquals("{\"algo\":\"HYBRID\"}", record.notes)
        assertEquals(listOf(SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_DEEP), record.stages.map { it.stage })
        assertEquals(Instant.ofEpochMilli(t0 + 200 * minute), record.stages[1].startTime)
    }

    @Test
    fun `invalid stages are rejected by Jetpack validation`() {
        val overlapping = listOf(HcStage(t0, t0 + 300 * minute, 4), HcStage(t0 + 200 * minute, t0 + 480 * minute, 5))
        assertThrows<IllegalArgumentException> { HcRecordMapper.toRecord(write(overlapping), phone) }
        val outside = listOf(HcStage(t0 - minute, t0 + 480 * minute, 4))
        assertThrows<IllegalArgumentException> { HcRecordMapper.toRecord(write(outside), phone) }
    }

    @Test
    fun `a read SleepSessionRecord becomes an HcSleepSession with sorted stages`() {
        val record =
            SleepSessionRecord(
                startTime = Instant.ofEpochMilli(t0),
                startZoneOffset = ZoneOffset.ofHours(3),
                endTime = Instant.ofEpochMilli(t0 + 60 * minute),
                endZoneOffset = null,
                metadata = Metadata.unknownRecordingMethodWithId("uuid-1"),
                title = "OHealth",
                stages =
                    listOf(
                        SleepSessionRecord.Stage(Instant.ofEpochMilli(t0 + 30 * minute), Instant.ofEpochMilli(t0 + 60 * minute), 2),
                        SleepSessionRecord.Stage(Instant.ofEpochMilli(t0), Instant.ofEpochMilli(t0 + 30 * minute), 0),
                    ),
            )
        val session = HcRecordMapper.toSession(record)
        assertEquals("uuid-1", session.uuid)
        assertEquals(t0, session.startMs)
        assertEquals(t0 + 60 * minute, session.endMs)
        assertEquals(10_800, session.startZoneOffsetSec)
        assertNull(session.endZoneOffsetSec)
        assertEquals(listOf(HcStage(t0, t0 + 30 * minute, 0), HcStage(t0 + 30 * minute, t0 + 60 * minute, 2)), session.stages)
        assertEquals("OHealth", session.title)
        assertNull(session.clientRecordId)
    }

    @Test
    fun `heart rate is flattened, clipped to the window and sorted`() {
        val series =
            HeartRateRecord(
                startTime = Instant.ofEpochMilli(t0 - 10 * minute),
                startZoneOffset = ZoneOffset.UTC,
                endTime = Instant.ofEpochMilli(t0 + 10 * minute),
                endZoneOffset = ZoneOffset.UTC,
                samples =
                    listOf(
                        HeartRateRecord.Sample(Instant.ofEpochMilli(t0 + 5 * minute), 61),
                        HeartRateRecord.Sample(Instant.ofEpochMilli(t0 - 5 * minute), 70),
                        HeartRateRecord.Sample(Instant.ofEpochMilli(t0), 60),
                    ),
                metadata = Metadata.unknownRecordingMethod(),
            )
        val later =
            HeartRateRecord(
                startTime = Instant.ofEpochMilli(t0 + 20 * minute),
                startZoneOffset = ZoneOffset.UTC,
                endTime = Instant.ofEpochMilli(t0 + 20 * minute),
                endZoneOffset = ZoneOffset.UTC,
                samples = listOf(HeartRateRecord.Sample(Instant.ofEpochMilli(t0 + 20 * minute), 58)),
                metadata = Metadata.unknownRecordingMethod(),
            )
        assertEquals(
            listOf(HrSample(t0, 60.0), HrSample(t0 + 5 * minute, 61.0)),
            HcRecordMapper.heartRate(listOf(later, series), t0, t0 + 20 * minute),
        )
    }

    @Test
    fun `step records overlapping the window are kept`() {
        fun steps(
            from: Long,
            to: Long,
        ) = StepsRecord(
            Instant.ofEpochMilli(from),
            ZoneOffset.UTC,
            Instant.ofEpochMilli(to),
            ZoneOffset.UTC,
            12,
            Metadata.unknownRecordingMethod(),
        )

        val result =
            HcRecordMapper.steps(
                listOf(
                    steps(t0 - 20 * minute, t0 - 10 * minute),
                    steps(t0 - 5 * minute, t0 + 5 * minute),
                    steps(
                        t0 + 30 * minute,
                        t0 + 40 * minute,
                    ),
                ),
                t0,
                t0 + 30 * minute,
            )
        assertEquals(listOf(StepsSample(t0 - 5 * minute, t0 + 5 * minute, 12)), result)
    }

    @Test
    fun `origin statistics are grouped per package`() {
        fun session(
            id: String,
            pkg: String,
            end: Long,
        ) = HcSleepSession(id, pkg, end - 60 * minute, end, 0, 0, emptyList(), null, null, null, 0, 0)

        val stats =
            HcRecordMapper.originStats(
                listOf(session("a", "b.app", t0), session("b", "a.app", t0 + minute), session("c", "a.app", t0 + 5 * minute)),
            )
        assertEquals(listOf(SleepOriginStats("a.app", 2, t0 + 5 * minute), SleepOriginStats("b.app", 1, t0)), stats)
    }
}
