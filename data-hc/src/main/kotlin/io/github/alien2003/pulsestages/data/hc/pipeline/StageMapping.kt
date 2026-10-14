package io.github.alien2003.pulsestages.data.hc.pipeline

import androidx.health.connect.client.records.SleepSessionRecord
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.data.hc.HcStage

/**
 * Mapping of estimated stages to Health Connect stage types and the checks every written hypnogram must pass.
 * AWAKE → STAGE_TYPE_AWAKE (1), LIGHT → STAGE_TYPE_LIGHT (4), DEEP → STAGE_TYPE_DEEP (5), REM → STAGE_TYPE_REM (6),
 * UNKNOWN → STAGE_TYPE_UNKNOWN (0); with `awakeAsLight` AWAKE (including the forced-awake gaps between merged
 * fragments) is written as LIGHT. A nap with the SLEEP_WAKE output ([toSleepWakeHcStages]) gets only AWAKE (1) and
 * SLEEPING (2): LIGHT, DEEP, REM and UNKNOWN are SLEEPING (the source session already says the whole span was sleep),
 * and with `awakeAsLight` AWAKE is SLEEPING too.
 */
object StageMapping {
    /** The stage types PulseStages ever writes. */
    val WRITTEN_TYPES: Set<Int> =
        setOf(
            SleepSessionRecord.STAGE_TYPE_UNKNOWN,
            SleepSessionRecord.STAGE_TYPE_AWAKE,
            SleepSessionRecord.STAGE_TYPE_SLEEPING,
            SleepSessionRecord.STAGE_TYPE_LIGHT,
            SleepSessionRecord.STAGE_TYPE_DEEP,
            SleepSessionRecord.STAGE_TYPE_REM,
        )

    /** Health Connect type of [stage]. */
    fun toHcType(
        stage: SleepStage,
        awakeAsLight: Boolean,
    ): Int =
        when (stage) {
            SleepStage.AWAKE -> if (awakeAsLight) SleepSessionRecord.STAGE_TYPE_LIGHT else SleepSessionRecord.STAGE_TYPE_AWAKE
            SleepStage.LIGHT -> SleepSessionRecord.STAGE_TYPE_LIGHT
            SleepStage.DEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
            SleepStage.REM -> SleepSessionRecord.STAGE_TYPE_REM
            SleepStage.UNKNOWN -> SleepSessionRecord.STAGE_TYPE_UNKNOWN
        }

    /** The [SleepStage] a written Health Connect [type] stands for (types we never write map to UNKNOWN). */
    fun fromHcType(type: Int): SleepStage =
        when (type) {
            SleepSessionRecord.STAGE_TYPE_AWAKE -> SleepStage.AWAKE
            SleepSessionRecord.STAGE_TYPE_LIGHT -> SleepStage.LIGHT
            SleepSessionRecord.STAGE_TYPE_DEEP -> SleepStage.DEEP
            SleepSessionRecord.STAGE_TYPE_REM -> SleepStage.REM
            else -> SleepStage.UNKNOWN
        }

    /** The Health Connect type of [stage] in the SLEEP_WAKE output of a nap: AWAKE (or SLEEPING with [awakeAsLight]) / SLEEPING. */
    fun toSleepWakeHcType(
        stage: SleepStage,
        awakeAsLight: Boolean,
    ): Int = if (stage == SleepStage.AWAKE && !awakeAsLight) SleepSessionRecord.STAGE_TYPE_AWAKE else SleepSessionRecord.STAGE_TYPE_SLEEPING

    /** [stages] mapped to Health Connect types, sorted by start, with adjacent equal types merged. */
    fun toHcStages(
        stages: List<StageInterval>,
        awakeAsLight: Boolean,
    ): List<HcStage> = merge(stages) { toHcType(it, awakeAsLight) }

    /** [stages] in the SLEEP_WAKE output of a nap ([toSleepWakeHcType]), sorted, with adjacent equal types merged. */
    fun toSleepWakeHcStages(
        stages: List<StageInterval>,
        awakeAsLight: Boolean,
    ): List<HcStage> = merge(stages) { toSleepWakeHcType(it, awakeAsLight) }

    private fun merge(
        stages: List<StageInterval>,
        typeOf: (SleepStage) -> Int,
    ): List<HcStage> {
        val merged = mutableListOf<HcStage>()
        stages.sortedWith(compareBy({ it.startMs }, { it.endMs })).forEach { interval ->
            val type = typeOf(interval.stage)
            val last = merged.lastOrNull()
            if (last != null && last.type == type && last.endMs == interval.startMs) {
                merged[merged.lastIndex] = last.copy(endMs = interval.endMs)
            } else {
                merged += HcStage(interval.startMs, interval.endMs, type)
            }
        }
        return merged
    }

    /** [stages] (written with [toHcStages]) as the ledger stores them. */
    fun toLedgerStages(stages: List<HcStage>): List<StageInterval> = stages.map { StageInterval(it.startMs, it.endMs, fromHcType(it.type)) }

    /**
     * Problems of [stages] for the session `[startMs, endMs)`; empty when valid. Valid means: non-empty, sorted,
     * contiguous (no gaps, no overlaps), exactly covering the session, only [WRITTEN_TYPES], and every stage at
     * least [epochMs] long (unless the whole session is shorter than one epoch, then exactly one stage).
     */
    fun validate(
        stages: List<HcStage>,
        startMs: Long,
        endMs: Long,
        epochMs: Long,
    ): List<String> {
        if (stages.isEmpty()) return listOf("no stages")
        val problems = mutableListOf<String>()
        if (stages.first().startMs != startMs) problems += "first stage starts at ${stages.first().startMs}, session at $startMs"
        if (stages.last().endMs != endMs) problems += "last stage ends at ${stages.last().endMs}, session at $endMs"
        val sessionShorterThanEpoch = endMs - startMs < epochMs
        if (sessionShorterThanEpoch && stages.size != 1) problems += "a session shorter than one epoch must have exactly one stage"
        val minLengthMs = if (sessionShorterThanEpoch) 1L else epochMs
        stages.forEachIndexed { index, stage ->
            problems += stageProblems(index, stage, startMs, endMs, minLengthMs)
            val next = stages.getOrNull(index + 1)
            if (next != null && next.startMs != stage.endMs) {
                problems += if (next.startMs < stage.endMs) "stages $index and ${index + 1} overlap" else "gap after stage $index"
            }
        }
        return problems
    }

    private fun stageProblems(
        index: Int,
        stage: HcStage,
        startMs: Long,
        endMs: Long,
        minLengthMs: Long,
    ): List<String> =
        listOfNotNull(
            "stage $index is empty or reversed".takeIf { stage.endMs <= stage.startMs },
            "stage $index is shorter than one epoch".takeIf { stage.endMs > stage.startMs && stage.endMs - stage.startMs < minLengthMs },
            "stage $index has type ${stage.type}".takeIf { stage.type !in WRITTEN_TYPES },
            "stage $index lies outside the session".takeIf { stage.startMs < startMs || stage.endMs > endMs },
        )
}

/**
 * Minutes per written stage type.
 *
 * @property awake AWAKE minutes.
 * @property light LIGHT minutes.
 * @property deep DEEP minutes.
 * @property rem REM minutes.
 * @property unknown UNKNOWN minutes.
 */
data class StageMinutes(
    val awake: Double,
    val light: Double,
    val deep: Double,
    val rem: Double,
    val unknown: Double,
) {
    /** Calculation. */
    companion object {
        private const val MINUTE_MS = 60_000.0

        /** Minutes of [stages] per type. */
        fun of(stages: List<HcStage>): StageMinutes {
            fun minutes(type: Int): Double = stages.filter { it.type == type }.sumOf { it.endMs - it.startMs } / MINUTE_MS
            return StageMinutes(
                awake = minutes(SleepSessionRecord.STAGE_TYPE_AWAKE),
                light = minutes(SleepSessionRecord.STAGE_TYPE_LIGHT),
                deep = minutes(SleepSessionRecord.STAGE_TYPE_DEEP),
                rem = minutes(SleepSessionRecord.STAGE_TYPE_REM),
                unknown = minutes(SleepSessionRecord.STAGE_TYPE_UNKNOWN),
            )
        }
    }
}
