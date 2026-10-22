package io.github.alien2003.pulsestages.work.saa

import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.DAY_MS
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MIDNIGHT
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OWN
import io.github.alien2003.pulsestages.data.hc.testutil.cyclingBpm
import io.github.alien2003.pulsestages.data.local.settings.ScheduleSettings
import io.github.alien2003.pulsestages.work.run.EstimateNewNightsRunner
import io.github.alien2003.pulsestages.work.run.RecomputeRequest
import io.github.alien2003.pulsestages.work.run.RecomputeRunner
import org.junit.jupiter.api.Assertions.assertEquals

// The simulated phone and OHealth's timelines of the Sleep as Android gate scenarios, shared by
// SleepAsAndroidGateScenarioTest and MergeGapGateReplayTest (docs/prereg/prereg-merge-gap.md section 9.1).

/** [hours]:[minutes]:[seconds] on the day of [MIDNIGHT] plus [day] days. */
internal fun time(
    hours: Int,
    minutes: Int,
    day: Int = 0,
    seconds: Int = 0,
): Long = MIDNIGHT + day * DAY_MS + hours * HOUR_MS + minutes * MINUTE_MS + seconds * 1_000L

/** Every [stepMs] from [fromMs] up to [untilMs]. */
internal fun every(
    stepMs: Long,
    fromMs: Long,
    untilMs: Long,
): MutableList<Long> = generateSequence(fromMs) { it + stepMs }.takeWhile { it <= untilMs }.toMutableList()

/** WorkManager's one pending request work, as its due time: every schedule replaces it (the running work has finished). */
internal class SimulatedWork(
    private val now: () -> Long,
) : SleepAsAndroidFollowUps {
    var dueAtMs: Long? = null

    override fun schedule(
        delayMs: Long,
        schedule: ScheduleSettings,
    ) {
        dueAtMs = now() + delayMs
    }

    override fun scheduleFromWork(
        delayMs: Long,
        schedule: ScheduleSettings,
    ) {
        dueAtMs = now() + delayMs
    }

    override fun cancel() {
        dueAtMs = null
    }
}

/**
 * The phone: PulseStages' scheduled runs at [runTimes] ([EstimateNewNightsRunner], in the background with background
 * access; with [inApp], "Estimate new" in the app instead, while the app is otherwise in the background), its request
 * work and its notices of changed nights ([notices]), and Sleep as Android, which imports 10 s after every request
 * ([ImportingSleepAsAndroid]; observed with Sleep as Android 20261006). OHealth is the test, which writes to [h]'s Health
 * Connect between the steps, and may also schedule writes in [writes] (time -> what OHealth writes then): each runs when
 * the clock reaches its time, after the runs, work and imports due at that time, also inside a timeline's own steps.
 */
internal class Phone(
    val h: PipelineHarness,
    private val runTimes: MutableList<Long>,
    private val inApp: Boolean = false,
    writes: List<Pair<Long, () -> Unit>> = emptyList(),
) {
    private val writes: MutableList<Pair<Long, () -> Unit>> = writes.sortedBy { it.first }.toMutableList()

    val saa = ImportingSleepAsAndroid(h.repository, importDelayMs = 10_000L) { h.clock.millis() }
    val work = SimulatedWork { h.clock.millis() }
    val notices = RecordingNotices()
    val requests = h.sleepAsAndroidRequests(saa, work, notices = notices)
    private val runner = EstimateNewNightsRunner(h.sync, h.personalization, requests, h.logger)
    private val recomputer = RecomputeRunner(h.recomputer, h.health, requests, h.logger)

    /** "Recompute" on the night [clientRecordId] in the app, now. */
    suspend fun recompute(clientRecordId: String) {
        recomputer.run(RecomputeRequest.Nights(listOf(clientRecordId), h.clock.millis()), inBackground = false)
    }

    /**
     * Lets the time pass until [untilMs]: Sleep as Android's imports, the runs and the work at their times, in order (in
     * this order at the same time).
     */
    suspend fun advanceTo(untilMs: Long) {
        while (true) {
            val import = saa.nextImportAtMs?.takeIf { it <= untilMs }
            val run = runTimes.firstOrNull()?.takeIf { it <= untilMs }
            val work = work.dueAtMs?.takeIf { it <= untilMs }
            val write = writes.firstOrNull()?.first?.takeIf { it <= untilMs }
            val next = listOfNotNull(import, run, work, write).minOrNull() ?: break
            h.clock.nowMs = next
            when (next) {
                import -> {
                    saa.importDue()
                }

                run -> {
                    runTimes.removeAt(0)
                    if (inApp) {
                        h.repository.inBackground = false
                        runner.run(inBackground = false, userRequested = true)
                        h.repository.inBackground = true
                    } else {
                        runner.run(inBackground = true)
                    }
                }

                work -> {
                    this.work.dueAtMs = null
                    requests.onWork()
                }

                else -> {
                    writes.removeAt(0).second()
                }
            }
        }
        h.clock.nowMs = untilMs
    }

    /** Sleep as Android holds PulseStages' record of the night from [startMs] to [wakeMs], as it is now in Health Connect. */
    fun assertImportedFinal(
        startMs: Long,
        wakeMs: Long,
    ) {
        val record = saa.recordOf(startMs, wakeMs)
        assertEquals(OWN, record.packageName, "PulseStages' night, with its stages: ${saa.imported}")
        assertEquals(wakeMs, record.endMs, "the final version")
        assertEquals(startMs - 1_000L, record.startMs, "one second before its source (write.startBeforeSource)")
        val current = h.repository.ownSessions().single { it.startMs == record.startMs }
        assertEquals(current.clientRecordVersion, record.clientRecordVersion, "no later version of it was written")
    }
}

/** OHealth's heart rate with a sleep-like pattern for the night from [startMs]. */
internal fun PipelineHarness.heartRate(
    startMs: Long,
    fromMs: Long,
    toMs: Long,
) = repository.addHeartRate(OHEALTH, fromMs, toMs, bpm = cyclingBpm(startMs))

/**
 * A walk as OHealth writes it: a one-minute step record of [count] steps every 5 minutes from [fromMs] until before
 * [untilMs] (it writes a record only for a minute with steps).
 */
internal fun PipelineHarness.walk(
    fromMs: Long,
    untilMs: Long,
    count: Long = 40,
) {
    (fromMs until untilMs step 5 * MINUTE_MS).forEach { repository.addSteps(OHEALTH, it, it + MINUTE_MS, count) }
}

/** OHealth's steps of the evening before the night of [day], 21:00–22:00: it writes fine-grained steps. */
internal fun PipelineHarness.eveningWalk(day: Int = 0) = walk(time(21, 0, day - 1), time(22, 0, day - 1))

/**
 * 18 Oct 2026, after PulseStages' log on the development phone: at 07:30 OHealth publishes the night cut at 07:29 (in
 * two fragments), at 09:36 heart rate to 09:35 and at 09:37 an intermediate version ending at 08:15 ([beforeIntermediate]
 * runs just before it), at 12:16 the final version ending at the wake, 12:15, written again with unchanged times at
 * 12:33, and heart rate to 13:19 at 13:19. With [walks], OHealth also writes one-minute steps: the evening before, and
 * from the wake on with the heart rate at 13:19.
 */
internal suspend fun Phone.replay18Oct(
    walks: Boolean = false,
    beforeIntermediate: suspend () -> Unit = {},
): Long {
    val start = time(4, 21)
    if (walks) h.eveningWalk()
    advanceTo(time(7, 30))
    h.repository.addSleepSession(OHEALTH, start, time(6, 8))
    val last = h.repository.addSleepSession(OHEALTH, time(6, 31), time(7, 29))
    h.heartRate(start, time(3, 51), time(7, 29))
    advanceTo(time(9, 36))
    h.heartRate(start, time(7, 29), time(9, 36))
    advanceTo(time(9, 37))
    beforeIntermediate()
    h.repository.updateSleepSession(last.uuid) { it.copy(endMs = time(8, 15)) }
    advanceTo(time(12, 16))
    h.heartRate(start, time(9, 37), time(12, 16))
    h.repository.updateSleepSession(last.uuid) { it.copy(endMs = time(12, 15)) }
    advanceTo(time(12, 33))
    h.repository.updateSleepSession(last.uuid) { it }
    advanceTo(time(13, 19))
    h.heartRate(start, time(12, 17), time(13, 19))
    if (walks) h.walk(time(12, 20), time(13, 19))
    return start
}

/**
 * 19 Oct 2026 on [day], after PulseStages' log on the development phone: OHealth publishes the night cut at 07:29 at
 * [provisionalAtMs], the final version ending at the wake (08:03) at 08:05 with heart rate to 07:58, writes it again with
 * unchanged times at 08:20, and its heart rate reaches 08:52 at 08:54 and 09:10 at 09:12. With [walks], OHealth also
 * writes one-minute steps: the evening before, and from the wake on with the heart rate at 08:54 and 09:12.
 */
internal suspend fun Phone.replay19Oct(
    day: Int = 0,
    provisionalAtMs: Long = time(7, 30, day),
    walks: Boolean = false,
): HcSleepSession {
    val start = time(2, 20, day)
    if (walks) h.eveningWalk(day)
    advanceTo(provisionalAtMs)
    val source = h.repository.addSleepSession(OHEALTH, start, time(7, 29, day))
    h.heartRate(start, time(1, 50, day), time(7, 29, day))
    advanceTo(time(8, 5, day))
    h.heartRate(start, time(7, 30, day), time(8, 0, day))
    h.repository.updateSleepSession(source.uuid) { it.copy(endMs = time(8, 3, day)) }
    advanceTo(time(8, 20, day))
    h.repository.updateSleepSession(source.uuid) { it }
    advanceTo(time(8, 54, day))
    h.heartRate(start, time(8, 0, day), time(8, 54, day))
    if (walks) h.walk(time(8, 10, day), time(8, 54, day))
    advanceTo(time(9, 12, day))
    h.heartRate(start, time(8, 54, day), time(9, 12, day))
    if (walks) h.walk(time(8, 55, day), time(9, 12, day))
    return source
}

/**
 * A sleep-in: OHealth publishes the night (from 00:40) cut at 07:29 at 07:30 while the user still sleeps, then uploads
 * heart rate alone (the watch's heart-rate-only report) at 08:45 and 09:50, past the end + margin, without extending the
 * session; the user turns over in bed at 08:50 (2 steps). [beforeFinal] runs at 10:40, before the final version: the user
 * woke at 10:30, OHealth publishes the final version at 10:40, and heart rate and the steps of a walk an hour after the
 * wake at 11:35. Returns the night's start.
 */
internal suspend fun Phone.replaySleepIn(beforeFinal: suspend () -> Unit = {}): Long {
    val start = time(0, 40)
    h.eveningWalk()
    val source = h.repository.addSleepSession(OHEALTH, start, time(7, 29))
    h.heartRate(start, time(0, 10), time(7, 29))
    advanceTo(time(8, 45))
    h.heartRate(start, time(7, 29), time(8, 45))
    advanceTo(time(8, 50))
    h.repository.addSteps(OHEALTH, time(8, 50), time(8, 51), 2)
    advanceTo(time(9, 50))
    h.heartRate(start, time(8, 45), time(9, 50))
    advanceTo(time(10, 40))
    beforeFinal()
    h.heartRate(start, time(9, 50), time(10, 40))
    h.repository.updateSleepSession(source.uuid) { it.copy(endMs = time(10, 30)) }
    advanceTo(time(11, 35))
    h.heartRate(start, time(10, 40), time(11, 35))
    h.walk(time(10, 35), time(11, 35))
    return start
}

/**
 * Hourly checks: OHealth publishes the night (from 02:20) cut at 07:29, the final version (08:03) at 08:05 with heart rate
 * only to 07:29, and the rest of its heart rate at 09:12. Returns the night's start.
 */
internal suspend fun Phone.replayHourlyChecks(): Long {
    val start = time(2, 20)
    val source = h.repository.addSleepSession(OHEALTH, start, time(7, 29))
    h.heartRate(start, time(1, 50), time(7, 29))
    advanceTo(time(8, 5))
    h.repository.updateSleepSession(source.uuid) { it.copy(endMs = time(8, 3)) }
    advanceTo(time(9, 12))
    h.heartRate(start, time(7, 29), time(9, 12))
    return start
}
