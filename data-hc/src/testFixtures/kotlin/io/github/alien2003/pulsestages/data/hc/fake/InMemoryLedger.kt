package io.github.alien2003.pulsestages.data.hc.fake

import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.data.local.db.BackfillStateDao
import io.github.alien2003.pulsestages.data.local.db.BackfillStateEntity
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightDao
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.HomeCounters
import io.github.alien2003.pulsestages.data.local.db.NightBaselineDao
import io.github.alien2003.pulsestages.data.local.db.NightBaselineEntity
import io.github.alien2003.pulsestages.data.local.db.NightSourceEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.NightSummary
import io.github.alien2003.pulsestages.data.local.db.PersonalModelDao
import io.github.alien2003.pulsestages.data.local.db.PersonalModelEntity
import io.github.alien2003.pulsestages.data.local.db.SourceOutcome
import io.github.alien2003.pulsestages.data.local.db.SourceSessionDao
import io.github.alien2003.pulsestages.data.local.db.SourceSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * The Room ledger of :data-local emulated in memory, for plain-JVM tests of code that uses its DAOs. The DAO
 * classes are the real abstract DAOs; only their generated SQL parts are replaced by equivalent Kotlin (the SQL itself
 * is tested in :data-local on Robolectric). The `@Transaction` methods of the DAOs run their real bodies.
 */
class InMemoryLedger {
    private val lock = Any()
    private val version = MutableStateFlow(0L)
    private val nights = LinkedHashMap<Long, EstimatedNightEntity>()
    private val links = LinkedHashSet<NightSourceEntity>()
    private val sources = LinkedHashMap<String, SourceSessionEntity>()
    private val baselines = LinkedHashMap<String, NightBaselineEntity>()
    private val models = LinkedHashMap<Long, PersonalModelEntity>()
    private var backfill: BackfillStateEntity? = null
    private var nextNightId = 1L
    private var nextModelId = 1L

    /** `estimated_nights` + `night_sources`. */
    val nightDao: EstimatedNightDao = Nights()

    /** `source_sessions`. */
    val sourceDao: SourceSessionDao = Sources()

    /** `night_baselines`. */
    val baselineDao: NightBaselineDao = Baselines()

    /** `backfill_state`. */
    val backfillDao: BackfillStateDao = Backfill()

    /** `personal_models`. */
    val personalModelDao: PersonalModelDao = PersonalModels()

    /** Snapshot of every personal model row, by id. */
    fun allModels(): List<PersonalModelEntity> = read { models.values.sortedBy { it.id } }

    /** Snapshot of every night, by local id. */
    fun allNights(): List<EstimatedNightEntity> = read { nights.values.sortedBy { it.id } }

    /** Snapshot of every source session row, sorted by start. */
    fun allSources(): List<SourceSessionEntity> = read { sources.values.sortedWith(compareBy({ it.startMs }, { it.uuid })) }

    /** Snapshot of every baseline row. */
    fun allBaselines(): List<NightBaselineEntity> = read { baselines.values.toList() }

    /** Snapshot of the links. */
    fun allLinks(): List<NightSourceEntity> = read { links.toList() }

    /** Replaces a night row as-is (test setup, e.g. an old row with another algorithm version). */
    fun putNight(night: EstimatedNightEntity) {
        write {
            val existing = nights.values.firstOrNull { it.clientRecordId == night.clientRecordId }
            val id = existing?.id ?: nextNightId++
            nights[id] = night.copy(id = id)
            links.removeAll { it.clientRecordId == night.clientRecordId }
            night.sourceUuids.forEach { links += NightSourceEntity(night.clientRecordId, it) }
        }
    }

    private fun <T> read(block: () -> T): T = synchronized(lock) { block() }

    private fun <T> write(block: () -> T): T =
        synchronized(lock) {
            val result = block()
            version.value = version.value + 1
            result
        }

    private fun <T> observeQuery(query: () -> T): Flow<T> = version.map { read(query) }

    private fun EstimatedNightEntity.toSummary(): NightSummary =
        NightSummary(
            id = id,
            clientRecordId = clientRecordId,
            sourcePackage = sourcePackage,
            sessionStartMs = sessionStartMs,
            sessionEndMs = sessionEndMs,
            startZoneOffsetSec = startZoneOffsetSec,
            endZoneOffsetSec = endZoneOffsetSec,
            status = status,
            statusReason = statusReason,
            estimator = estimator,
            algoVersion = algoVersion,
            paramsHash = paramsHash,
            coverage = coverage,
            confidence = confidence,
            awakeMin = awakeMin,
            lightMin = lightMin,
            deepMin = deepMin,
            remMin = remMin,
            unknownMin = unknownMin,
            elevatedHr = elevatedHr,
            lowConfidence = lowConfidence,
            calibrated = calibrated,
            rulesFallback = rulesFallback,
            updatedAt = updatedAt,
            mode = mode,
            sleepWakeOnly = sleepWakeOnly,
        )

    private val newestFirst = compareByDescending<EstimatedNightEntity> { it.sessionStartMs }.thenByDescending { it.id }
    private val oldestFirst = compareBy<EstimatedNightEntity>({ it.sessionStartMs }, { it.id })

    private fun isStale(
        night: EstimatedNightEntity,
        algoVersion: Int,
        paramsHash: String,
    ): Boolean = night.isStale(algoVersion, paramsHash)

    @Suppress("TooManyFunctions") // Implements every query of EstimatedNightDao.
    private inner class Nights : EstimatedNightDao() {
        override suspend fun findByClientRecordId(clientRecordId: String): EstimatedNightEntity? =
            read { nights.values.firstOrNull { it.clientRecordId == clientRecordId } }

        override suspend fun findById(id: Long): EstimatedNightEntity? = read { nights[id] }

        override fun observeById(id: Long): Flow<EstimatedNightEntity?> = observeQuery { nights[id] }

        override fun observeByClientRecordId(clientRecordId: String): Flow<EstimatedNightEntity?> =
            observeQuery { nights.values.firstOrNull { it.clientRecordId == clientRecordId } }

        override fun observeSummaries(): Flow<List<NightSummary>> =
            observeQuery { nights.values.sortedWith(newestFirst).map { it.toSummary() } }

        override fun observeSummariesWithStatus(statuses: List<NightStatus>): Flow<List<NightSummary>> =
            observeQuery {
                nights.values
                    .filter { it.status in statuses }
                    .sortedWith(newestFirst)
                    .map { it.toSummary() }
            }

        override suspend fun findOverlapping(
            startMs: Long,
            endMs: Long,
            statuses: List<NightStatus>,
        ): List<EstimatedNightEntity> =
            read {
                nights.values
                    .filter { it.sessionStartMs < endMs && it.sessionEndMs > startMs && it.status in statuses }
                    .sortedWith(oldestFirst)
            }

        override suspend fun findStartingIn(
            startMs: Long,
            endMs: Long,
        ): List<EstimatedNightEntity> = read { nights.values.filter { it.sessionStartMs in startMs until endMs }.sortedWith(oldestFirst) }

        override suspend fun sourceUuidsOf(clientRecordId: String): List<String> =
            read { links.filter { it.clientRecordId == clientRecordId }.map { it.sourceUuid }.sorted() }

        override suspend fun findStale(
            algoVersion: Int,
            paramsHash: String,
        ): List<EstimatedNightEntity> = read { nights.values.filter { isStale(it, algoVersion, paramsHash) }.sortedWith(oldestFirst) }

        override fun observeStaleCount(
            algoVersion: Int,
            paramsHash: String,
        ): Flow<Int> = observeQuery { nights.values.count { isStale(it, algoVersion, paramsHash) } }

        override suspend fun countWithStatus(status: NightStatus): Int = read { nights.values.count { it.status == status } }

        override suspend fun countWithStatusAndMode(
            status: NightStatus,
            mode: SessionMode,
        ): Int = read { nights.values.count { it.status == status && it.mode == mode } }

        override suspend fun clientRecordIdsWithOutput(): List<String> =
            read {
                nights.values
                    .filter { it.hcRecordId != null }
                    .sortedWith(oldestFirst)
                    .map { it.clientRecordId }
            }

        override suspend fun findBySourceUuidsChunk(sourceUuids: List<String>): List<EstimatedNightEntity> =
            read {
                val ids = links.filter { it.sourceUuid in sourceUuids }.map { it.clientRecordId }.toSet()
                nights.values.filter { it.clientRecordId in ids }
            }

        override suspend fun insertNight(night: EstimatedNightEntity): Long =
            write {
                check(nights.values.none { it.clientRecordId == night.clientRecordId }) {
                    "UNIQUE constraint failed: estimated_nights.clientRecordId (${night.clientRecordId})"
                }
                val id = nextNightId++
                nights[id] = night.copy(id = id)
                id
            }

        override suspend fun updateNight(night: EstimatedNightEntity) {
            write { if (nights.containsKey(night.id)) nights[night.id] = night }
        }

        override suspend fun insertSources(sources: List<NightSourceEntity>) {
            write { links.addAll(sources) }
        }

        override suspend fun deleteSourcesOf(clientRecordId: String) {
            write { links.removeAll { it.clientRecordId == clientRecordId } }
        }

        override suspend fun upsertSourceSessions(sessions: List<SourceSessionEntity>) {
            write { sessions.forEach { sources[it.uuid] = it } }
        }

        override suspend fun upsertBaseline(baseline: NightBaselineEntity) {
            write { baselines[baseline.clientRecordId] = baseline }
        }

        override suspend fun deleteBaselinesChunk(clientRecordIds: List<String>) {
            write { clientRecordIds.forEach { baselines.remove(it) } }
        }

        override suspend fun markInputChecked(
            clientRecordId: String,
            fingerprint: String?,
            checkedAtMs: Long,
        ): Int =
            write {
                val entry =
                    nights.entries.firstOrNull { it.value.clientRecordId == clientRecordId && it.value.status == NightStatus.WRITTEN }
                entry?.setValue(entry.value.copy(inputFingerprint = fingerprint, inputCheckedAt = checkedAtMs))
                if (entry == null) 0 else 1
            }

        override suspend fun setStatusChunk(
            clientRecordIds: List<String>,
            status: NightStatus,
            reason: String?,
            clearOutput: Boolean,
            now: Long,
        ): Int =
            write {
                var changed = 0
                nights.entries.forEach { entry ->
                    val night = entry.value
                    if (night.clientRecordId in clientRecordIds) {
                        entry.setValue(
                            night.copy(
                                status = status,
                                statusReason = reason,
                                updatedAt = now,
                                hcRecordId = if (clearOutput) null else night.hcRecordId,
                            ),
                        )
                        changed++
                    }
                }
                changed
            }
    }

    private inner class Sources : SourceSessionDao() {
        override suspend fun upsertAll(sessions: List<SourceSessionEntity>) {
            write { sessions.forEach { sources[it.uuid] = it } }
        }

        override suspend fun findByUuid(uuid: String): SourceSessionEntity? = read { sources[uuid] }

        override suspend fun findByNight(clientRecordId: String): List<SourceSessionEntity> =
            read { sources.values.filter { it.nightClientRecordId == clientRecordId }.sortedWith(compareBy({ it.startMs }, { it.uuid })) }

        override fun observeAll(): Flow<List<SourceSessionEntity>> =
            observeQuery { sources.values.sortedWith(compareByDescending<SourceSessionEntity> { it.startMs }.thenBy { it.uuid }) }

        override fun observeHomeCounters(
            algoVersion: Int,
            paramsHash: String,
        ): Flow<HomeCounters> =
            observeQuery {
                val rows = sources.values
                HomeCounters(
                    found = rows.count { it.outcome != SourceOutcome.OWN },
                    estimated = rows.count { it.outcome == SourceOutcome.ESTIMATED },
                    skipped = rows.count { it.outcome in SKIPPED_OUTCOMES },
                    failed = rows.count { it.outcome == SourceOutcome.FAILED },
                    stale = nights.values.count { isStale(it, algoVersion, paramsHash) },
                    nights = nights.values.count { it.status == NightStatus.WRITTEN && it.mode == SessionMode.NIGHT },
                    naps = nights.values.count { it.status == NightStatus.WRITTEN && it.mode == SessionMode.NAP },
                )
            }

        override suspend fun findByUuidsChunk(uuids: List<String>): List<SourceSessionEntity> = read { uuids.mapNotNull { sources[it] } }

        override suspend fun markDeletedChunk(
            uuids: List<String>,
            reason: String?,
            now: Long,
        ): Int =
            write {
                var changed = 0
                uuids.forEach { uuid ->
                    val row = sources[uuid]
                    if (row != null) {
                        sources[uuid] = row.copy(outcome = SourceOutcome.DELETED, reason = reason, lastSeenAt = now)
                        changed++
                    }
                }
                changed
            }
    }

    private inner class Baselines : NightBaselineDao() {
        override suspend fun upsert(baseline: NightBaselineEntity) {
            write { baselines[baseline.clientRecordId] = baseline }
        }

        override suspend fun delete(clientRecordId: String) {
            write { baselines.remove(clientRecordId) }
        }

        override suspend fun find(clientRecordId: String): NightBaselineEntity? = read { baselines[clientRecordId] }

        override suspend fun recentNadirs(
            beforeMs: Long,
            limit: Int,
            minDurationMs: Long,
        ): List<Double> =
            read {
                // LEFT JOIN estimated_nights: the session length comes from the night row (none: unknown length).
                val durations = nights.values.associate { it.clientRecordId to it.sessionEndMs - it.sessionStartMs }
                baselines.values
                    .filter { it.sessionEndMs < beforeMs }
                    .filter { row -> minDurationMs <= 0 || (durations[row.clientRecordId] ?: Long.MIN_VALUE) >= minDurationMs }
                    .sortedWith(compareByDescending<NightBaselineEntity> { it.sessionEndMs }.thenBy { it.clientRecordId })
                    .take(limit)
                    .map { it.nadirBpm }
            }
    }

    private inner class Backfill : BackfillStateDao() {
        override suspend fun get(): BackfillStateEntity? = read { backfill }

        override fun observe(): Flow<BackfillStateEntity?> = observeQuery { backfill }

        override suspend fun clear() {
            write { backfill = null }
        }

        override suspend fun upsertRow(state: BackfillStateEntity) {
            write { backfill = state }
        }
    }

    private inner class PersonalModels : PersonalModelDao() {
        private val newestFirst = compareByDescending<PersonalModelEntity> { it.createdAt }.thenByDescending { it.id }

        override suspend fun deactivateAll() {
            write { models.replaceAll { _, model -> model.copy(active = false) } }
        }

        override suspend fun getActive(): PersonalModelEntity? = read { activeModel() }

        override fun observeActive(): Flow<PersonalModelEntity?> = observeQuery { activeModel() }

        override fun observeAll(): Flow<List<PersonalModelEntity>> = observeQuery { models.values.sortedWith(newestFirst) }

        override suspend fun findById(id: Long): PersonalModelEntity? = read { models[id] }

        override suspend fun delete(id: Long) {
            write { models.remove(id) }
        }

        override suspend fun insertRow(model: PersonalModelEntity): Long =
            write {
                val id = nextModelId++
                models[id] = model.copy(id = id)
                id
            }

        override suspend fun setActive(id: Long): Int =
            write {
                val model = models[id]
                if (model == null) {
                    0
                } else {
                    models[id] = model.copy(active = true)
                    1
                }
            }

        private fun activeModel(): PersonalModelEntity? = models.values.filter { it.active }.maxByOrNull { it.id }
    }

    private companion object {
        val SKIPPED_OUTCOMES: Set<SourceOutcome> =
            setOf(
                SourceOutcome.NAP,
                SourceOutcome.HAS_STAGES,
                SourceOutcome.LOWER_PRIORITY_DUPLICATE,
                SourceOutcome.NOT_ESTIMATED,
                SourceOutcome.FAILED,
            )
    }
}
