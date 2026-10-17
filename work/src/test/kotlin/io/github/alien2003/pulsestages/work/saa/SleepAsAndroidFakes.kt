package io.github.alien2003.pulsestages.work.saa

import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.local.settings.ScheduleSettings

/** A [SyncRequestSender] that counts the requests; [installed] says whether Sleep as Android is there. */
class RecordingSyncRequestSender(
    /** Whether Sleep as Android (with its receiver) is installed. */
    @Volatile var installed: Boolean = true,
) : SyncRequestSender {
    /** Thrown by [send] when set (e.g. a SecurityException of the platform). */
    @Volatile var failure: Exception? = null

    /** Requests sent. */
    @Volatile var sent: Int = 0
        private set

    /** Calls of [send], sent or not. */
    @Volatile var attempts: Int = 0
        private set

    override fun send(): Boolean {
        attempts++
        failure?.let { throw it }
        if (installed) sent++
        return installed
    }
}

/** A [SleepAsAndroidFollowUps] that records what is scheduled and cancelled. */
class RecordingFollowUps : SleepAsAndroidFollowUps {
    /**
     * One [schedule] call.
     *
     * @property delayMs the delay.
     * @property schedule the schedule settings whose constraints apply.
     */
    data class Scheduled(
        val delayMs: Long,
        val schedule: ScheduleSettings,
    )

    /** Every [schedule] call, in order. */
    val scheduled: MutableList<Scheduled> = mutableListOf()

    /** Calls of [cancel]. */
    var cancelled: Int = 0
        private set

    override fun schedule(
        delayMs: Long,
        schedule: ScheduleSettings,
    ) {
        scheduled += Scheduled(delayMs, schedule)
    }

    override fun cancel() {
        cancelled++
    }
}

/** The production [SleepAsAndroidSyncRequests] over this harness's settings, sync state, clock and log. */
fun PipelineHarness.sleepAsAndroidRequests(
    sender: SyncRequestSender = RecordingSyncRequestSender(),
    followUps: SleepAsAndroidFollowUps = RecordingFollowUps(),
): SleepAsAndroidSyncRequests = SleepAsAndroidSyncRequests(settings, syncState, sender, followUps, clock, logger)

/** Turns `write.requestSleepAsAndroidSync` (on by default) on or off. */
suspend fun PipelineHarness.requestSleepAsAndroidSync(on: Boolean = true) {
    settings.set { it.copy(write = it.write.copy(requestSleepAsAndroidSync = on)) }
}
