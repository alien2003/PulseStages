package io.github.alien2003.pulsestages.work.saa

import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.pipeline.SourceSettlement
import io.github.alien2003.pulsestages.data.local.settings.ScheduleSettings

/** A [SyncRequestSender] that counts the requests; [installed] says whether Sleep as Android is there. */
class RecordingSyncRequestSender(
    /** Whether Sleep as Android (with its receiver) is installed. */
    @Volatile var installed: Boolean = true,
) : SyncRequestSender {
    /** Thrown by [send] when set (e.g. a SecurityException of the platform). */
    @Volatile var failure: Throwable? = null

    /** Requests sent. */
    @Volatile var sent: Int = 0
        private set

    /** Calls of [send], sent or not. */
    @Volatile var attempts: Int = 0
        private set

    /** Calls of [isInstalled]. */
    @Volatile var lookups: Int = 0
        private set

    override fun isInstalled(): Boolean {
        lookups++
        return installed
    }

    override fun send(): Boolean {
        attempts++
        failure?.let { throw it }
        if (installed) sent++
        return installed
    }
}

/**
 * Sleep as Android's import of a Health Connect sync, as its code does it, [importDelayMs] after every request (at once
 * when 0; [importDue] runs the imports that are due): the sessions of other apps that start in its 30-day window, in
 * start order, where a session that overlaps an earlier-starting one is dropped (the earliest start wins) and one that
 * overlaps an imported record is skipped (both ends inclusive). An imported record is never updated.
 */
class ImportingSleepAsAndroid(
    private val repository: FakeHealthRepository,
    private val importDelayMs: Long = 0L,
    private val now: () -> Long,
) : SyncRequestSender {
    private val pending = mutableListOf<Long>()

    /** The records Sleep as Android holds, as they were when it imported them. */
    val imported: MutableList<HcSleepSession> = mutableListOf()

    /** Requests received. */
    var requests: Int = 0
        private set

    /** When each request was received. */
    val requestTimes: MutableList<Long> = mutableListOf()

    /** When the next import is due, or null when no request waits for one. */
    val nextImportAtMs: Long? get() = pending.minOrNull()

    override fun isInstalled(): Boolean = true

    override fun send(): Boolean {
        requests++
        requestTimes += now()
        if (importDelayMs == 0L) import() else pending += now() + importDelayMs
        return true
    }

    /** Runs the imports of the requests whose delay has passed. */
    fun importDue() {
        if (pending.removeAll { it <= now() }) import()
    }

    private fun import() {
        val candidates = mutableListOf<HcSleepSession>()
        repository
            .allSessions()
            .filter { it.startMs >= now() - SleepAsAndroid.SYNC_WINDOW_MS }
            .forEach { session -> if (candidates.none { it.overlaps(session) }) candidates += session }
        candidates.filter { candidate -> imported.none { it.overlaps(candidate) } }.forEach { imported += it }
    }

    /** The imported record that overlaps `[startMs, endMs]`. */
    fun recordOf(
        startMs: Long,
        endMs: Long,
    ): HcSleepSession = imported.single { it.startMs <= endMs && startMs <= it.endMs }

    private fun HcSleepSession.overlaps(other: HcSleepSession): Boolean = startMs <= other.endMs && other.startMs <= endMs
}

/** A [SleepAsAndroidFollowUps] that records what is scheduled and cancelled. */
class RecordingFollowUps : SleepAsAndroidFollowUps {
    /**
     * One [schedule] or [scheduleFromWork] call.
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

    /** Every [scheduleFromWork] call, in order. */
    val scheduledFromWork: MutableList<Scheduled> = mutableListOf()

    /** Every call, in order: "schedule", "scheduleFromWork" or "cancel". */
    val calls: MutableList<String> = mutableListOf()

    /** Calls of [cancel]. */
    var cancelled: Int = 0
        private set

    override fun schedule(
        delayMs: Long,
        schedule: ScheduleSettings,
    ) {
        calls += "schedule"
        scheduled += Scheduled(delayMs, schedule)
    }

    override fun scheduleFromWork(
        delayMs: Long,
        schedule: ScheduleSettings,
    ) {
        calls += "scheduleFromWork"
        scheduledFromWork += Scheduled(delayMs, schedule)
    }

    override fun cancel() {
        calls += "cancel"
        cancelled++
    }
}

/**
 * A [SleepAsAndroidNotices] that records the notices and returns [result] (or throws [failure]); [onShow] runs first, e.g.
 * to read what was stored before the notice. [shade] holds the notices as Android would show them.
 */
class RecordingNotices(
    @Volatile var result: NoticeResult = NoticeResult.SHOWN,
    @Volatile var failure: Exception? = null,
) : SleepAsAndroidNotices {
    /** Every notice asked for, shown or not, in order. */
    val shown: MutableList<ChangedNight> = mutableListOf()

    /** Every notice withdrawn, by tag, in order. */
    val withdrawn: MutableList<String> = mutableListOf()

    /** The notices shown now, by tag: a notice cancels those of the nights it replaced and replaces its own tag. */
    val shade: MutableMap<String, ChangedNight> = linkedMapOf()

    /** The tags of the notices in [shade], in the order they were posted. */
    val shownTags: List<String> get() = shade.keys.toList()

    /** Runs at the start of every [show]. */
    @Volatile var onShow: (ChangedNight) -> Unit = {}

    override fun show(change: ChangedNight): NoticeResult {
        onShow(change)
        shown += change
        failure?.let { throw it }
        if (result == NoticeResult.SHOWN) {
            change.replaced.forEach { shade.remove(it) }
            shade[change.night.clientRecordId] = change
        }
        return result
    }

    override fun withdraw(clientRecordId: String) {
        withdrawn += clientRecordId
        shade.remove(clientRecordId)
    }
}

/**
 * The production [SleepAsAndroidSyncRequests] over this harness's settings, sync state, clock, log, [settlement] and
 * ledger ([sentNights]).
 */
fun PipelineHarness.sleepAsAndroidRequests(
    sender: SyncRequestSender = RecordingSyncRequestSender(),
    followUps: SleepAsAndroidFollowUps = RecordingFollowUps(),
    settlement: SourceSettlement = this.settlement,
    sentNights: SentNights = SentNights(ledger.nightDao),
    notices: SleepAsAndroidNotices = RecordingNotices(),
): SleepAsAndroidSyncRequests =
    SleepAsAndroidSyncRequests(settings, syncState, sender, followUps, clock, logger, settlement, sentNights, notices)

/** Turns `write.requestSleepAsAndroidSync` (on by default) on or off. */
suspend fun PipelineHarness.requestSleepAsAndroidSync(on: Boolean = true) {
    settings.set { it.copy(write = it.write.copy(requestSleepAsAndroidSync = on)) }
}
