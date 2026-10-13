package io.github.alien2003.pulsestages.data.hc.testutil

import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.HcStage
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.local.settings.OHEALTH_PACKAGE
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File

const val MINUTE_MS: Long = 60_000L
const val HOUR_MS: Long = 60 * MINUTE_MS
const val DAY_MS: Long = 24 * HOUR_MS

/** 2026-10-13T00:00:00Z. */
const val MIDNIGHT: Long = PipelineHarness.DEFAULT_START_MS

/** "Now" in most tests: 2026-10-13T12:00:00Z (the last night ended this morning). */
const val NOON: Long = MIDNIGHT + 12 * HOUR_MS

const val OHEALTH: String = OHEALTH_PACKAGE
const val OTHER_APP: String = "com.example.sleeptracker"
const val THIRD_APP: String = "org.example.thirdtracker"
const val OWN: String = FakeHealthRepository.DEFAULT_OWN_PACKAGE

/** Start of the night that began [daysAgo] days before [MIDNIGHT], at 23:00 UTC. */
fun nightStart(daysAgo: Int): Long = MIDNIGHT - daysAgo * DAY_MS - HOUR_MS

/**
 * Heart rate with a stage-like pattern (for the fake estimator: < 52 DEEP, < 58 LIGHT, < 66 REM, else AWAKE),
 * repeating every 90 minutes after [sessionStartMs]: 15 min LIGHT, 25 min DEEP, 25 min LIGHT, 20 min REM, 5 min AWAKE.
 */
fun cyclingBpm(sessionStartMs: Long): (Long) -> Double =
    { t ->
        val minute = Math.floorMod((t - sessionStartMs) / MINUTE_MS, 90L)
        when {
            minute < 15 -> 55.0
            minute < 40 -> 49.0
            minute < 65 -> 56.0
            minute < 85 -> 62.0
            else -> 72.0
        }
    }

/**
 * Adds a sleep session of [packageName] plus heart rate (every 2 min, from 30 min before to 30 min after) and a few
 * steps, as OHealth would write them.
 */
fun FakeHealthRepository.addNight(
    packageName: String,
    startMs: Long,
    durationMs: Long = 8 * HOUR_MS,
    stages: List<HcStage> = emptyList(),
    startZoneOffsetSec: Int? = 0,
    endZoneOffsetSec: Int? = 0,
    withHeartRate: Boolean = true,
): HcSleepSession {
    val session = addSleepSession(packageName, startMs, startMs + durationMs, stages, startZoneOffsetSec, endZoneOffsetSec)
    if (withHeartRate) addHeartRate(packageName, startMs - 30 * MINUTE_MS, startMs + durationMs + 30 * MINUTE_MS, bpm = cyclingBpm(startMs))
    return session
}

/** Runs [block] with a fresh [PipelineHarness] whose clock is at [now], in virtual time. */
fun harnessTest(
    dir: File,
    now: Long = NOON,
    block: suspend TestScope.(PipelineHarness) -> Unit,
) = runTest {
    val harness = PipelineHarness(backgroundScope, dir, StandardTestDispatcher(testScheduler), startMs = now)
    block(harness)
}
