package io.github.alien2003.pulsestages.work.saa

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OWN
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.data.local.sync.SyncStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * The gate end to end: PulseStages' runs and request work over the fake Health Connect while OHealth publishes a night as
 * it did on the development phone, and a Sleep as Android that imports on every request and never updates a record. It
 * must end up with PulseStages' record of the final version of the night, whatever happened in between.
 */
class SleepAsAndroidGateScenarioTest {
    @TempDir
    lateinit var dir: File

    private fun scenario(
        start: Long,
        block: suspend (PipelineHarness) -> Unit,
    ) = harnessTest(dir, now = start) { h ->
        h.settings.set { it.copy(write = it.write.copy(startBeforeSource = true)) }
        block(h)
    }

    /**
     * With [walks], OHealth also writes one-minute steps: the provisional version, which heart rate alone lets through at
     * 09:36:30, then waits for a walk, and the requests go out at the same times.
     */
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `18 Oct - Sleep as Android gets the night once its final version is complete`(walks: Boolean) =
        scenario(time(7, 30)) { h ->
            // The runs come every 15 minutes; one comes at 09:36:30, between the heart rate and the intermediate version.
            val phone = Phone(h, every(15 * MINUTE_MS, time(7, 36, seconds = 30), time(16, 0)))

            val start =
                phone.replay18Oct(walks) {
                    val request =
                        h.syncState.state
                            .first()
                            .sleepAsAndroidRequest
                    if (walks) {
                        assertNull(request.settledSinceMs, "the provisional version waits for a walk: the user still sleeps")
                    } else {
                        assertEquals(time(9, 36, seconds = 30), request.settledSinceMs, "the provisional version looks complete")
                    }
                }
            phone.advanceTo(time(16, 0))

            phone.assertImportedFinal(start, time(12, 15))
            assertEquals(
                listOf(time(13, 45), time(13, 55)),
                phone.saa.requestTimes,
                "not before heart rate an hour after the wake; the request and its follow-up",
            )
            assertEquals(emptyList<ChangedNight>(), phone.notices.shown, "nothing changed after the request")
        }

    /** With [walks], OHealth also writes one-minute steps, and the requests go out at the same times. */
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `19 Oct - Sleep as Android gets the night once heart rate an hour after the wake has arrived`(walks: Boolean) =
        scenario(time(7, 30)) { h ->
            val phone = Phone(h, every(15 * MINUTE_MS, time(7, 55), time(12, 0)))

            val source = phone.replay19Oct(walks = walks)
            phone.advanceTo(time(12, 0))

            phone.assertImportedFinal(source.startMs, time(8, 3))
            assertEquals(listOf(time(9, 33), time(9, 43)), phone.saa.requestTimes, "after 09:12")
            assertEquals(emptyList<ChangedNight>(), phone.notices.shown, "nothing changed after the request")
        }

    /**
     * A day in bed: Sleep as Android imports the night of 19 Oct at 09:33, then the user sleeps again until 10:20, and
     * OHealth publishes that at 10:25: it extends the session, or, with [fragment], adds one from 08:28, 25 min after its
     * end, which PulseStages merges into a new night. Sleep as Android keeps its earlier version, so the user is notified,
     * once. After the user deletes it in Sleep as Android and recomputes the night, Sleep as Android imports the final
     * night, and nothing else is notified.
     */
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a day in bed - a night that changes after the request is notified once, and the repair brings the final night`(fragment: Boolean) =
        scenario(time(7, 30)) { h ->
            val phone = Phone(h, every(15 * MINUTE_MS, time(7, 55), time(16, 0)))
            val source = phone.replay19Oct()
            phone.advanceTo(time(10, 25))
            assertEquals(time(8, 3), phone.saa.recordOf(source.startMs, time(8, 3)).endMs, "imported at 09:33")
            val sent =
                h.ledger.nightDao
                    .findBySourceUuids(listOf(source.uuid))
                    .single()

            val wake = time(10, 20)
            h.heartRate(source.startMs, time(9, 12), time(10, 25))
            if (fragment) {
                h.repository.addSleepSession(OHEALTH, time(8, 28), wake)
            } else {
                h.repository.updateSleepSession(source.uuid) { it.copy(endMs = wake) }
            }
            phone.advanceTo(time(11, 35))
            h.heartRate(source.startMs, time(10, 25), time(11, 35))
            phone.advanceTo(time(13, 0))

            val notice = phone.notices.shown.single()
            assertEquals(time(8, 3), notice.sentEndMs)
            assertEquals(wake, notice.night.sessionEndMs)
            assertEquals(if (fragment) setOf(sent.clientRecordId) else emptySet(), notice.replaced)
            assertTrue(phone.saa.requestTimes.size > 2, "asked again for the new version: ${phone.saa.requestTimes}")
            assertEquals(time(8, 3), phone.saa.recordOf(source.startMs, wake).endMs, "Sleep as Android keeps the earlier version")

            // The repair: the user deletes the night in Sleep as Android and taps Recompute on it.
            phone.saa.imported.clear()
            phone.recompute(notice.night.clientRecordId)
            phone.advanceTo(time(16, 0))

            phone.assertImportedFinal(source.startMs, wake)
            assertEquals(1, phone.notices.shown.size, "no second notice")
        }

    /**
     * A day in bed in two parts that Sleep as Android imported apart: after the night of 19 Oct the user sleeps again from
     * 08:40, and Sleep as Android imports that session too; at 12:30 OHealth moves the end of the night to 08:15, 25 min
     * before it, and PulseStages merges the two. With [secondNight] the session lasts to 10:15 and is a night of
     * PulseStages of its own, sent with the night in one request; otherwise it lasts to 09:10, under "Minimum session
     * length", and Sleep as Android holds OHealth's session. Sleep as Android skips a night that overlaps any record it
     * holds, so the repair the notification gives deletes every record between the start and the end of the merged
     * night; deleting only the record of the night would leave the merged night out.
     */
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a day in bed - a merge with a session Sleep as Android imported is repaired by deleting every record it covers`(
        secondNight: Boolean,
    ) = scenario(time(7, 30)) { h ->
        val phone = Phone(h, every(15 * MINUTE_MS, time(7, 55), time(17, 0)))
        val source = phone.replay19Oct()
        val secondEnd = if (secondNight) time(10, 15) else time(9, 10)
        phone.advanceTo(if (secondNight) time(10, 25) else time(9, 12))
        h.repository.addSleepSession(OHEALTH, time(8, 40), secondEnd)
        phone.advanceTo(time(10, 25))
        h.heartRate(source.startMs, time(9, 12), time(10, 25))
        phone.advanceTo(time(11, 35))
        h.heartRate(source.startMs, time(10, 25), time(11, 35))
        phone.advanceTo(time(12, 30))
        assertEquals(2, phone.saa.imported.size, "the night and the later session: ${phone.saa.imported}")
        assertEquals(emptyList<ChangedNight>(), phone.notices.shown)

        h.repository.updateSleepSession(source.uuid) { it.copy(endMs = time(8, 15)) }
        phone.advanceTo(time(13, 0))
        h.heartRate(source.startMs, time(11, 35), time(13, 0))
        phone.advanceTo(time(14, 0))

        val notice = phone.notices.shown.single()
        assertEquals(source.startMs to secondEnd, notice.night.sessionStartMs to notice.night.sessionEndMs, "merged")
        assertEquals(if (secondNight) 2 else 1, notice.sentSpans.size, "the nights of the request it covers")
        assertEquals(source.startMs to secondEnd, notice.repairStartMs to notice.repairEndMs)
        assertEquals(2, phone.saa.imported.size, "Sleep as Android skipped the merged night")

        // The repair as the notification gives it: every record between the two times, then Recompute.
        phone.saa.imported.removeAll { it.startMs <= notice.repairEndMs && notice.repairStartMs <= it.endMs }
        phone.recompute(notice.night.clientRecordId)
        phone.advanceTo(time(17, 0))

        phone.assertImportedFinal(source.startMs, secondEnd)
        assertEquals(1, phone.notices.shown.size, "no second notice")
    }

    /**
     * A sleep-in: OHealth publishes the night cut at 07:29 at 07:30 while the user still sleeps, then uploads heart rate
     * alone (the watch's heart-rate-only report) at 08:45 and 09:50, past the end + margin, without extending the session;
     * the user turns over in bed at 08:50 (2 steps). The user wakes at 10:30; OHealth publishes the final version at 10:40,
     * and heart rate and the steps of a walk an hour after the wake at 11:35. Heart rate alone would have sent the
     * provisional version at about 09:00.
     */
    @Test
    fun `a sleep-in - heart rate alone past the provisional end sends nothing, the walk after the final version does`() =
        scenario(time(7, 30)) { h ->
            val phone = Phone(h, every(15 * MINUTE_MS, time(7, 36, seconds = 30), time(14, 0)))
            val start =
                phone.replaySleepIn {
                    assertEquals(0, phone.saa.requests, "nothing sent while the user sleeps: ${phone.saa.requestTimes}")
                    assertTrue(
                        h.logger.lines.any {
                            it.level == LogLevel.INFO &&
                                it.message.startsWith(
                                    "not asked yet: no minute of 5 or more steps from $OHEALTH from 60 min after the session's end yet " +
                                        "(heart rate reaches 74 min after it; session last changed 1 min after its end)",
                                )
                        },
                        "${h.logger.lines.map { it.message }}",
                    )
                }
            phone.advanceTo(time(14, 0))

            phone.assertImportedFinal(start, time(10, 30))
            assertEquals(
                listOf(time(12, 0), time(12, 10)),
                phone.saa.requestTimes,
                "not before the walk an hour after the wake; the request and its follow-up",
            )
        }

    @Test
    fun `hourly checks - complete nights wait for the late-data pass that rewrites them with the heart rate that settled them`() =
        scenario(time(7, 30)) { h ->
            // The default check interval: runs at 07:55, 08:55, ... OHealth publishes the night cut at 07:29, the final
            // version (08:03) at 08:05 with heart rate only to 07:29, and the rest of its heart rate at 09:12.
            val phone = Phone(h, every(HOUR_MS, time(7, 55), time(12, 55)))
            val start = phone.replayHourlyChecks()
            phone.advanceTo(time(13, 0))

            phone.assertImportedFinal(start, time(8, 3))
            assertTrue(phone.saa.requestTimes.all { it > time(9, 55) }, "after the run at 09:55 rewrote it: ${phone.saa.requestTimes}")
        }

    /**
     * The morning batch of OHealth brings the previous night's late heart rate (and may write that session again,
     * [sessionWrittenAgain]) seconds before the new night's provisional version; a run between them rewrites the previous
     * night. The write starts the settled streak again even when no session changed.
     */
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `the morning race - a rewrite of the previous night seconds before the new provisional night sends nothing`(
        sessionWrittenAgain: Boolean,
    ) = scenario(time(7, 5, day = -1)) { h ->
        // Runs every 15 minutes, none in the last hour before 07:30:15 (Doze): that run makes the late-data pass.
        val runs = every(15 * MINUTE_MS, time(7, 30, day = -1, seconds = 15), time(6, 15, seconds = 15))
        val phone = Phone(h, (runs + every(15 * MINUTE_MS, time(7, 30, seconds = 15), time(11, 0))).toMutableList())
        // The previous night, published at 07:05 with its heart rate except for 02:00-03:00, and complete.
        val previousStart = time(23, 0, day = -2)
        val previous = h.repository.addSleepSession(OHEALTH, previousStart, time(7, 0, day = -1))
        h.heartRate(previousStart, previousStart - 30 * MINUTE_MS, time(2, 0, day = -1))
        h.heartRate(previousStart, time(3, 0, day = -1), time(7, 5, day = -1))
        phone.advanceTo(time(8, 30, day = -1))
        h.heartRate(previousStart, time(7, 5, day = -1), time(8, 30, day = -1))
        phone.advanceTo(time(7, 30))
        phone.assertImportedFinal(previousStart, time(7, 0, day = -1))
        val before = phone.saa.requests

        // The morning batch: the previous night's late heart rate (and the session written again), then a run that
        // rewrites it, then 5 s later, before a request would be imported, the new night's provisional version.
        h.heartRate(previousStart, time(2, 0, day = -1), time(3, 0, day = -1))
        if (sessionWrittenAgain) h.repository.updateSleepSession(previous.uuid) { it }
        val source = phone.replay19Oct(provisionalAtMs = time(7, 30, seconds = 20))
        phone.advanceTo(time(11, 0))

        assertTrue(
            phone.saa.requestTimes
                .drop(before)
                .all { it > time(9, 12) },
            "${phone.saa.requestTimes}",
        )
        phone.assertImportedFinal(source.startMs, time(8, 3))
        assertEquals(time(7, 0, day = -1), phone.saa.recordOf(previousStart, time(7, 0, day = -1)).endMs, "never updated")
    }

    @Test
    fun `daily and 24-hour checks 12 hours apart - the final version published between runs is sent after the next run`() =
        scenario(time(7, 30)) { h ->
            h.settings.set { it.copy(schedule = it.schedule.copy(periodicMinutes = 1440)) }
            // The daily run at 09:00 and the periodic one, every 24 hours, at 21:00: the final version is processed
            // within 12 hours of its publication.
            val phone = Phone(h, mutableListOf(time(9, 0), time(21, 0), time(9, 0, day = 1)))

            val start = phone.replay18Oct()
            phone.advanceTo(time(20, 59))
            assertEquals(0, phone.saa.requests, "the final version waits for a run all day")
            phone.advanceTo(time(10, 0, day = 1))

            phone.assertImportedFinal(start, time(12, 15))
            assertEquals(2, phone.saa.requests)
            assertTrue(phone.saa.requestTimes.all { it > time(21, 0) })
        }

    /**
     * A documented limit (health-connect.md §8.3): with only one check every 24 hours the final version leaves the
     * 12-hour scope before a run has processed it, and the request owed for the provisional version then goes out with
     * PulseStages' record of that version, which Sleep as Android keeps.
     */
    @Test
    fun `checks 24 hours apart - a session that leaves the 12-hour scope unprocessed is sent cut short`() =
        scenario(time(7, 30)) { h ->
            h.settings.set { it.copy(schedule = it.schedule.copy(periodicMinutes = 1440)) }
            val phone = Phone(h, mutableListOf(time(9, 0), time(9, 0, day = 1, seconds = 30)))

            val start = phone.replay18Oct()
            phone.advanceTo(time(11, 0, day = 1))

            val record = phone.saa.recordOf(start, time(12, 15))
            assertEquals(OWN, record.packageName)
            assertEquals(time(7, 29), record.endMs, "PulseStages' record of the provisional version")
            val first = phone.saa.requestTimes.first()
            assertTrue(first > time(0, 33, day = 1) && first < time(9, 0, day = 1), "once 12 h after the final's last change: $first")
            assertEquals(
                time(12, 15),
                h.repository
                    .ownSessions()
                    .single()
                    .endMs,
                "the next run's record is final, too late",
            )
        }

    @Test
    fun `without background access, a night checked by a daily Estimate new is sent after the next day's, not dropped`() =
        scenario(time(7, 30)) { h ->
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            h.repository.inBackground = true
            // The user opens the app every morning at about 09:00; nothing reads Health Connect in between.
            val phone = Phone(h, mutableListOf(time(9, 0), time(9, 0, day = 1, seconds = 30)), inApp = true)

            val start = phone.replay18Oct()
            phone.advanceTo(time(11, 0, day = 1))

            phone.assertImportedFinal(start, time(12, 15))
            assertTrue(phone.saa.requestTimes.all { it > time(9, 0, day = 1) }, "${phone.saa.requestTimes}")
            assertEquals(2, phone.saa.requests, "the request after the second day's run, and its follow-up")
            val sent = h.logger.lines.first { it.level == LogLevel.INFO && it.message.startsWith("asked Sleep as Android to sync (") }
            assertEquals(
                "asked Sleep as Android to sync (owed for 15 min; without a new check: no background access; follow-up in 10 min)",
                sent.message,
                "by the work, on the streak that the run in the app began",
            )
        }

    @Test
    fun `a follow-up that the previous release left pending is checked like any request`() =
        harnessTest(dir, now = time(7, 20)) { h ->
            // 0.4.0 recomputed the previous night at 07:20, asked Sleep as Android at once and left its follow-up due at
            // 07:30; then PulseStages is updated.
            val file = File(dir, "${SyncStateStore.FILE_NAME}.preferences_pb")
            val storeJob = Job(coroutineContext[Job])
            SyncStateStore.createDataStore(CoroutineScope(coroutineContext + storeJob)) { file }.edit {
                it[longPreferencesKey("sleep_as_android_follow_up_due_at")] = time(7, 30)
            }
            storeJob.cancelAndJoin()
            h.settings.set { it.copy(write = it.write.copy(startBeforeSource = true)) }
            val phone = Phone(h, every(15 * MINUTE_MS, time(7, 40), time(12, 0)))
            val previousStart = time(23, 0, day = -2)
            h.repository.addSleepSession(OHEALTH, previousStart, time(7, 0, day = -1))
            h.heartRate(previousStart, previousStart - 30 * MINUTE_MS, time(7, 30, day = -1))
            h.sync.run(inBackground = false)
            phone.saa.send()
            phone.work.dueAtMs = time(7, 30)

            // OHealth publishes the new night at 07:29, a minute before 0.4.0's follow-up would have imported it.
            val source = phone.replay19Oct(provisionalAtMs = time(7, 29))
            phone.advanceTo(time(12, 0))

            phone.assertImportedFinal(source.startMs, time(8, 3))
            assertTrue(
                phone.saa.requestTimes
                    .drop(1)
                    .all { it > time(9, 12) },
                "${phone.saa.requestTimes}",
            )
            assertTrue(
                h.logger.lines.any { it.message.startsWith("not asked yet: $OHEALTH changed a sleep session") },
                "0.4.0's follow-up was checked at 07:30 and held: the new night was not processed yet",
            )
        }
}
