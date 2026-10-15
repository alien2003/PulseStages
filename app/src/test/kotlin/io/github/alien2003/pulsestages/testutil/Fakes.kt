package io.github.alien2003.pulsestages.testutil

import io.github.alien2003.pulsestages.data.hc.pipeline.OutputOptions
import io.github.alien2003.pulsestages.data.hc.pipeline.RecomputeScope
import io.github.alien2003.pulsestages.data.local.db.LogDao
import io.github.alien2003.pulsestages.data.local.db.LogEntryEntity
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.data.local.settings.ScheduleSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import io.github.alien2003.pulsestages.platform.AppInfo
import io.github.alien2003.pulsestages.platform.AppInfoResolver
import io.github.alien2003.pulsestages.platform.DocumentAccessException
import io.github.alien2003.pulsestages.platform.DocumentStore
import io.github.alien2003.pulsestages.platform.ShareableFileWriter
import io.github.alien2003.pulsestages.source.StaleNights
import io.github.alien2003.pulsestages.source.StaleNightsSource
import io.github.alien2003.pulsestages.work.schedule.WorkJob
import io.github.alien2003.pulsestages.work.schedule.WorkScheduler
import io.github.alien2003.pulsestages.work.schedule.WorkStatus
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** In-memory documents by uri; [failReads] / [failWrites] simulate inaccessible documents. */
class FakeDocumentStore : DocumentStore {
    /** Document contents by uri. */
    val documents = mutableMapOf<String, String>()

    /** Every read fails. */
    var failReads = false

    /** Every write fails. */
    var failWrites = false

    override suspend fun readText(
        uri: String,
        maxBytes: Int,
    ): String {
        if (failReads) throw DocumentAccessException("read failed")
        val text = documents[uri] ?: throw DocumentAccessException("missing $uri")
        if (text.encodeToByteArray().size > maxBytes) throw DocumentAccessException("too large")
        return text
    }

    override suspend fun writeText(
        uri: String,
        text: String,
    ) {
        if (failWrites) throw DocumentAccessException("write failed")
        documents[uri] = text
    }
}

/** Records shared files and returns fake content uris. */
class FakeShareableFileWriter : ShareableFileWriter {
    /** Written files by name. */
    val files = mutableMapOf<String, String>()

    /** Every write fails. */
    var fail = false

    override suspend fun write(
        fileName: String,
        text: String,
    ): String {
        if (fail) throw DocumentAccessException("cannot write")
        files[fileName] = text
        return "content://test.fileprovider/shared/$fileName"
    }
}

/** App labels by package name; unknown packages resolve to no label and no icon. */
class FakeAppInfoResolver(
    private val labels: Map<String, String> = emptyMap(),
) : AppInfoResolver {
    override suspend fun resolve(packageName: String): AppInfo = AppInfo(labels[packageName], icon = null)
}

/** [LogDao] over an in-memory list; records which level filters were observed. */
class FakeLogDao : LogDao {
    private val rows = MutableStateFlow<List<LogEntryEntity>>(emptyList())
    private var nextId = 1L

    /** Level lists passed to [observeRecentWithLevels]. */
    val observedLevelFilters = mutableListOf<List<LogLevel>>()

    /** Adds an entry. */
    fun add(
        level: LogLevel,
        message: String,
        timestampMs: Long = 1_000L * nextId,
        tag: String = "Test",
        nightKey: String? = null,
    ) {
        rows.value = rows.value + LogEntryEntity(nextId++, timestampMs, level, tag, message, nightKey)
    }

    override suspend fun insertAll(entries: List<LogEntryEntity>) {
        entries.forEach { add(it.level, it.message, it.timestampMs, it.tag, it.nightKey) }
    }

    override suspend fun trimToNewest(keep: Int): Int {
        val before = rows.value.size
        rows.value = rows.value.sortedBy { it.id }.takeLast(keep)
        return before - rows.value.size
    }

    override fun observeRecent(limit: Int): Flow<List<LogEntryEntity>> = rows.map { list -> list.sortedByDescending { it.id }.take(limit) }

    override fun observeRecentWithLevels(
        levels: List<LogLevel>,
        limit: Int,
    ): Flow<List<LogEntryEntity>> {
        observedLevelFilters += levels
        return rows.map { list -> list.filter { it.level in levels }.sortedByDescending { it.id }.take(limit) }
    }

    override fun observeForNight(nightKey: String): Flow<List<LogEntryEntity>> =
        rows.map { list -> list.filter { it.nightKey == nightKey } }

    override suspend fun getAll(): List<LogEntryEntity> = rows.value.sortedBy { it.id }

    override suspend fun count(): Int = rows.value.size

    override suspend fun clear() {
        rows.value = emptyList()
    }
}

/**
 * [WorkScheduler] that records the calls and lets the test drive every job's status ([statuses], [nightStatus]).
 * [keepScheduled] suspends until cancelled, like the real one.
 */
class FakeWorkScheduler : WorkScheduler {
    private val recorded = MutableStateFlow<List<String>>(emptyList())

    /** The calls in order, e.g. "estimateNow", "recomputeAll:ALL", "recomputeNight:ps:x". */
    val calls: List<String> get() = recorded.value

    /** Suspends until [call] was made (calls from coroutines a ViewModel launched may still be queued). */
    suspend fun awaitCall(call: String) {
        recorded.first { call in it }
    }

    private fun record(call: String) {
        recorded.update { it + call }
    }

    /** Schedules passed to [ensureScheduled]. */
    val ensured: MutableList<ScheduleSettings> = mutableListOf()

    /** Number of [keepScheduled] calls. */
    var keepScheduledCalls: Int = 0

    /** The status of each job. */
    val statuses: Map<WorkJob, MutableStateFlow<WorkStatus>> = WorkJob.entries.associateWith { MutableStateFlow(WorkStatus.NOT_SCHEDULED) }

    private val nightStatuses = mutableMapOf<String, MutableStateFlow<WorkStatus>>()

    /** The recompute status of the night [clientRecordId]. */
    fun nightStatus(clientRecordId: String): MutableStateFlow<WorkStatus> =
        nightStatuses.getOrPut(clientRecordId) { MutableStateFlow(WorkStatus.NOT_SCHEDULED) }

    override suspend fun ensureScheduled(schedule: ScheduleSettings) {
        ensured += schedule
    }

    override suspend fun keepScheduled() {
        keepScheduledCalls++
        awaitCancellation()
    }

    override suspend fun realignDaily() {
        record("realignDaily")
    }

    override suspend fun estimateNow() {
        record("estimateNow")
    }

    override fun startBackfill() {
        record("startBackfill")
    }

    override suspend fun cancelBackfill() {
        record("cancelBackfill")
    }

    override fun recomputeAll(scope: RecomputeScope) {
        record("recomputeAll:$scope")
    }

    override suspend fun recomputeNight(clientRecordId: String) {
        record("recomputeNight:$clientRecordId")
    }

    override fun status(job: WorkJob): Flow<WorkStatus> = statuses.getValue(job)

    override fun nightRecomputeStatus(clientRecordId: String): Flow<WorkStatus> = nightStatus(clientRecordId)
}

/**
 * [StaleNightsSource] over [settings]: the params hash follows the algorithm and the output options of the write
 * settings ([OutputOptions.hashExtra]), and [staleWhenChanged]
 * nights are stale whenever they differ from the defaults (like nights estimated with the defaults).
 */
class FakeStaleNightsSource(
    private val settings: SettingsRepository,
    private val staleWhenChanged: Int = 3,
) : StaleNightsSource {
    /** Number of [recomputeStale] calls. */
    var recomputes: Int = 0

    override fun staleNights(): Flow<StaleNights> =
        settings.settings.map { s ->
            val key = s.algo to OutputOptions.hashExtra(s.write)
            val defaults = AppSettings().let { it.algo to OutputOptions.hashExtra(it.write) }
            StaleNights(paramsHash = key.hashCode().toString(), count = if (key == defaults) 0 else staleWhenChanged)
        }

    override fun recomputeStale() {
        recomputes++
    }
}
