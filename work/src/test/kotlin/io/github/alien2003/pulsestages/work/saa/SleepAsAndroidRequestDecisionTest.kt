package io.github.alien2003.pulsestages.work.saa

import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.pipeline.Settlement
import io.github.alien2003.pulsestages.data.hc.pipeline.SourceVersion
import io.github.alien2003.pulsestages.data.hc.pipeline.UnsettledReason
import io.github.alien2003.pulsestages.data.hc.pipeline.keyOf
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.local.sync.SleepAsAndroidRequestState
import io.github.alien2003.pulsestages.work.saa.SleepAsAndroidSyncRequests.Action
import io.github.alien2003.pulsestages.work.saa.SleepAsAndroidSyncRequests.Companion.CHECK_WAIT_MS
import io.github.alien2003.pulsestages.work.saa.SleepAsAndroidSyncRequests.Companion.CONFIRM_MS
import io.github.alien2003.pulsestages.work.saa.SleepAsAndroidSyncRequests.Companion.FOLLOW_UP_DELAY_MS
import io.github.alien2003.pulsestages.work.saa.SleepAsAndroidSyncRequests.Companion.MAX_OWED_MS
import io.github.alien2003.pulsestages.work.saa.SleepAsAndroidSyncRequests.Companion.RECHECK_MS
import io.github.alien2003.pulsestages.work.saa.SleepAsAndroidSyncRequests.Companion.decide
import io.github.alien2003.pulsestages.work.saa.SleepAsAndroidSyncRequests.Step
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A night of OHealth that ended at 08:03 and was last changed at 08:20. */
private val NIGHT =
    SourceVersion(
        "aaaaaaaa-0000-0000-0000-000000000001",
        OHEALTH,
        NOON - 9 * HOUR_MS,
        NOON - 237 * MINUTE_MS,
        NOON - 220 * MINUTE_MS,
    )

/** An earlier night, out of the gate's recent sessions but still in scope. */
private val EARLIER =
    SourceVersion(
        "bbbbbbbb-0000-0000-0000-000000000002",
        OHEALTH,
        NOON - 33 * HOUR_MS,
        NOON - 25 * HOUR_MS,
        NOON - 2 * HOUR_MS,
    )

/** A request owed since 10 minutes before noon, with its follow-up. */
private val OWED = SleepAsAndroidRequestState(dueAtMs = NOON, followUp = true, owedSinceMs = NOON - 10 * MINUTE_MS)

private fun settled(vararg versions: SourceVersion): Settlement.Settled =
    Settlement.Settled(
        versions.toList(),
        mapOf(OHEALTH to NOON - MINUTE_MS),
    )

/** [OWED] with a settled streak of [versions] since [sinceMs]. */
private fun streak(
    sinceMs: Long,
    vararg versions: SourceVersion,
): SleepAsAndroidRequestState = OWED.copy(settledKey = settled(*versions).keyOf(), settledSinceMs = sinceMs)

private fun Step.defer(): Action.Defer = assertInstanceOf(Action.Defer::class.java, action, "$this")

/**
 * [SleepAsAndroidSyncRequests.decide]: when the check of an owed request sends it, how long it waits otherwise, and what
 * it logs.
 */
class SleepAsAndroidRequestDecisionTest {
    @Test
    fun `the timing`() {
        assertEquals(10 * MINUTE_MS, FOLLOW_UP_DELAY_MS)
        assertEquals(15 * MINUTE_MS, RECHECK_MS)
        assertEquals(15 * MINUTE_MS, CONFIRM_MS)
        assertEquals(60 * MINUTE_MS, CHECK_WAIT_MS)
        assertEquals(24 * HOUR_MS, MAX_OWED_MS)
    }

    @Test
    fun `a first settled check starts a streak, asks for a late-data pass and waits 15 minutes`() {
        val verdict = settled(NIGHT, EARLIER)

        val step = decide(OWED, verdict, NOON, lateDataPassAtMs = NOON)

        assertEquals(
            Step(
                state = OWED.copy(dueAtMs = NOON + 15 * MINUTE_MS, settledKey = verdict.keyOf(), settledSinceMs = NOON),
                action = Action.Defer(NOON + 15 * MINUTE_MS, "confirming", "the nights look complete; asking in 15 min if nothing changes"),
                requestLateDataPassAtMs = NOON,
            ),
            step,
            "a pass of a run that started at the same moment is not before it, but the streak is new",
        )
    }

    @Test
    fun `a streak still settled after 15 minutes is sent once a pass that started at or after its start completed`() {
        val state = streak(NOON, NIGHT)

        val early = decide(state, settled(NIGHT), NOON + 14 * MINUTE_MS, lateDataPassAtMs = NOON)
        assertEquals(
            Action.Defer(NOON + 15 * MINUTE_MS, "confirming", "the nights look complete; asking in 1 min if nothing changes"),
            early.action,
        )
        assertNull(early.requestLateDataPassAtMs, "the streak goes on: no new request")

        val sent = decide(state, settled(NIGHT), NOON + 15 * MINUTE_MS, lateDataPassAtMs = NOON + 5 * MINUTE_MS)

        assertEquals(Action.Send(fallback = false), sent.action)
        assertEquals(
            state.copy(
                dueAtMs = NOON + 25 * MINUTE_MS,
                followUp = false,
                owedSinceMs = NOON + 15 * MINUTE_MS,
                lastSentAtMs = NOON + 15 * MINUTE_MS,
                sentKey = state.settledKey,
            ),
            sent.state,
            "stored as sent, with the follow-up 10 minutes later; the streak goes on for it",
        )
        assertNull(sent.requestLateDataPassAtMs)

        val followUp = decide(sent.state, settled(NIGHT), NOON + 25 * MINUTE_MS, lateDataPassAtMs = NOON + 5 * MINUTE_MS)
        assertEquals(Action.Send(fallback = false), followUp.action)
        assertNull(followUp.state.dueAtMs, "nothing is owed after the follow-up")
    }

    @Test
    fun `a confirmed streak waits for a pass that started after its start, an hour at most`() {
        val state = streak(NOON, NIGHT)

        for (pass in listOf(null, NOON - MINUTE_MS)) {
            val waiting = decide(state, settled(NIGHT), NOON + 15 * MINUTE_MS, lateDataPassAtMs = pass)
            assertEquals(
                Action.Defer(
                    NOON + 30 * MINUTE_MS,
                    "waiting for a check",
                    "the nights look complete; waiting for the next check of the recent nights (at most 45 min)",
                ),
                waiting.action,
            )
            assertEquals(NOON, waiting.state.settledSinceMs)
            assertEquals(NOON + 30 * MINUTE_MS, waiting.state.dueAtMs)

            val last = decide(state, settled(NIGHT), NOON + 50 * MINUTE_MS, lateDataPassAtMs = pass).defer()
            assertEquals(NOON + 60 * MINUTE_MS, last.atMs, "no later than an hour after the streak began")
            assertEquals("the nights look complete; waiting for the next check of the recent nights (at most 10 min)", last.detail)

            assertEquals(Action.Send(fallback = false), decide(state, settled(NIGHT), NOON + 60 * MINUTE_MS, pass).action)
        }
    }

    @Test
    fun `a session that leaves the window keeps the streak, a new or changed one starts another`() {
        val state = streak(NOON, NIGHT, EARLIER)

        val aged = decide(state, settled(NIGHT), NOON + 5 * MINUTE_MS, lateDataPassAtMs = null)
        assertEquals(NOON, aged.state.settledSinceMs, "a subset of the streak's sessions")
        assertEquals(NIGHT.key, aged.state.settledKey)
        assertNull(aged.changed)
        assertNull(aged.requestLateDataPassAtMs)

        val extended = NIGHT.copy(endMs = NIGHT.endMs + 34 * MINUTE_MS, lastModifiedMs = NOON)
        val next = SourceVersion("cccccccc-0000-0000-0000-000000000003", OHEALTH, NOON - 2 * HOUR_MS, NOON - HOUR_MS, NOON)
        val changed = decide(state, settled(extended, next, EARLIER), NOON + 5 * MINUTE_MS, lateDataPassAtMs = null)

        assertEquals(NOON + 5 * MINUTE_MS, changed.state.settledSinceMs)
        assertEquals(NOON + 5 * MINUTE_MS, changed.requestLateDataPassAtMs)
        assertEquals(NOON + 20 * MINUTE_MS, changed.defer().atMs)
        assertEquals("the nights changed while confirming (after 5 min): aaaaaaaa end +34 min, cccccccc new", changed.changed)

        val rewritten = decide(state, settled(NIGHT.copy(lastModifiedMs = NOON), EARLIER), NOON + 2 * MINUTE_MS, null)
        assertEquals("the nights changed while confirming (after 2 min): aaaaaaaa written again", rewritten.changed)
        assertEquals(NOON + 2 * MINUTE_MS, rewritten.state.settledSinceMs)

        val moved = decide(state, settled(NIGHT.copy(startMs = NIGHT.startMs - 75 * MINUTE_MS)), NOON + MINUTE_MS, null)
        assertEquals("the nights changed while confirming (after 1 min): aaaaaaaa start -1 h 15 min", moved.changed)

        assertNull(decide(OWED, settled(NIGHT), NOON, null).changed, "no streak, nothing changed")
    }

    @Test
    fun `an unsettled check ends the streak and waits 15 minutes, or until the source's data can be there`() {
        val state = streak(NOON - 5 * MINUTE_MS, NIGHT)
        val waiting =
            Settlement.Unsettled(
                versions = listOf(NIGHT),
                reason = UnsettledReason.WAITING_FOR_DATA,
                version = NIGHT,
                nightClientRecordId = "ps:night",
                thresholdMs = NIGHT.endMs + HOUR_MS,
                latestHeartRateMs = NIGHT.endMs - 5 * MINUTE_MS,
            )

        val step = decide(state, waiting, NIGHT.endMs + 10 * MINUTE_MS, lateDataPassAtMs = null)

        assertEquals(
            Action.Defer(
                NIGHT.endMs + HOUR_MS,
                "WAITING_FOR_DATA",
                "no heart rate from $OHEALTH from 60 min after the session's end yet (latest: 5 min before it; " +
                    "session last changed 17 min after its end)",
                "ps:night",
            ),
            step.action,
        )
        assertEquals(state.copy(dueAtMs = NIGHT.endMs + HOUR_MS, settledKey = null, settledSinceMs = null), step.state)
        assertNull(step.requestLateDataPassAtMs)

        val unprocessed = Settlement.Unsettled(listOf(NIGHT), UnsettledReason.NOT_PROCESSED, NIGHT, nightClientRecordId = null)
        assertEquals(
            Action.Defer(
                NOON + 15 * MINUTE_MS,
                "NOT_PROCESSED",
                "$OHEALTH changed a sleep session (ended 3 h 57 min ago) that PulseStages has not processed yet",
            ),
            decide(state, unprocessed, NOON, null).action,
        )
    }

    @Test
    fun `every reason has its own detail`() {
        fun detailOf(
            reason: UnsettledReason,
            version: SourceVersion? = NIGHT,
            ownHeartRate: Boolean = true,
        ): String =
            decide(OWED, Settlement.Unsettled(listOf(NIGHT), reason, version, null, ownHeartRate = ownHeartRate), NOON, null).defer().detail

        assertEquals(
            "a sleep session was deleted; PulseStages has not processed that yet",
            detailOf(UnsettledReason.DELETION_PENDING, null),
        )
        assertEquals(
            "an older PulseStages session of this night is still in Health Connect",
            detailOf(UnsettledReason.LEFTOVER_OUTPUT),
        )
        assertEquals("the written night ends before its source session", detailOf(UnsettledReason.OUTPUT_BEHIND))
        assertEquals("this night has no estimate yet", detailOf(UnsettledReason.AWAITING_ESTIMATE))
        assertEquals(
            "$OHEALTH wrote no heart rate in this session; waiting until it is unchanged for 60 min",
            detailOf(UnsettledReason.WAITING_FOR_DATA, ownHeartRate = false),
        )
        assertEquals(
            "no heart rate from $OHEALTH from 0 min after the session's end yet (latest: none; session last changed 17 min after its end)",
            detailOf(UnsettledReason.WAITING_FOR_DATA),
        )
    }

    @Test
    fun `without background access a stored streak is sent once it is 15 minutes old, without one nothing is scheduled`() {
        val unreadable = Settlement.Unreadable("no background access")

        val none = decide(OWED, unreadable, NOON + 5 * MINUTE_MS, lateDataPassAtMs = null)
        assertEquals(
            Step(
                OWED,
                Action.Wait(
                    "Health Connect cannot be read in the background (no background access); checked when PulseStages next runs while open",
                ),
            ),
            none,
            "the due time stays: the next run in the foreground checks",
        )

        val state = streak(NOON, NIGHT)
        assertEquals(
            Action.Defer(NOON + 15 * MINUTE_MS, "confirming", "the nights look complete; asking in 5 min if nothing changes"),
            decide(state, unreadable, NOON + 10 * MINUTE_MS, null).action,
        )

        val sent = decide(state, unreadable, NOON + 15 * MINUTE_MS, lateDataPassAtMs = null)
        assertEquals(Action.Send(fallback = true), sent.action, "no pass is waited for: none can be checked")
        assertEquals(state.settledKey, sent.state.sentKey)
        assertEquals(NOON, sent.state.settledSinceMs)
    }

    @Test
    fun `a failed check keeps the streak and waits 15 minutes`() {
        val state = streak(NOON - 10 * MINUTE_MS, NIGHT)

        val step = decide(state, Settlement.Failed(HealthDataException.Transient("binder died")), NOON, lateDataPassAtMs = null)

        assertEquals(
            Action.Defer(NOON + 15 * MINUTE_MS, "failed", "could not check whether the nights are complete: binder died"),
            step.action,
        )
        assertEquals(state.copy(dueAtMs = NOON + 15 * MINUTE_MS), step.state)
    }

    @Test
    fun `a session sent with the last request that later starts or ends elsewhere is warned about once`() {
        val sentAt = NOON - 30 * MINUTE_MS
        val state = OWED.copy(lastSentAtMs = sentAt, sentKey = settled(NIGHT, EARLIER).keyOf())
        val extended = NIGHT.copy(endMs = NIGHT.endMs + 34 * MINUTE_MS, lastModifiedMs = NOON)
        val unprocessed = Settlement.Unsettled(listOf(extended, EARLIER), UnsettledReason.NOT_PROCESSED, extended, null)

        val step = decide(state, unprocessed, NOON, lateDataPassAtMs = null)

        assertEquals(
            "Sleep as Android was asked to sync 30 min ago; a session of $OHEALTH then ended 34 min earlier than it does now: " +
                "Sleep as Android may keep the earlier version",
            step.falsified,
        )
        assertNull(step.state.sentKey, "warned once")
        assertNull(decide(step.state, unprocessed, NOON + 15 * MINUTE_MS, null).falsified)

        val earlierStart = settled(NIGHT.copy(startMs = NIGHT.startMs - HOUR_MS), EARLIER)
        assertEquals(
            "Sleep as Android was asked to sync 30 min ago; a session of $OHEALTH then started 1 h later than it does now: " +
                "Sleep as Android may keep the earlier version",
            decide(state, earlierStart, NOON, null).falsified,
        )

        val same = decide(state, settled(NIGHT.copy(lastModifiedMs = NOON), EARLIER), NOON, null)
        assertNull(same.falsified, "written again with the same times")
        assertEquals(state.sentKey, same.state.sentKey)
        val failed = decide(state, Settlement.Failed(HealthDataException.Transient("x")), NOON, null)
        assertNull(failed.falsified, "nothing was read")
        assertEquals(state.sentKey, failed.state.sentKey)
    }
}
