package io.github.alien2003.pulsestages.data.hc.fake

import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.algo.model.HrvSample
import io.github.alien2003.pulsestages.algo.model.RespiratoryRateSample
import io.github.alien2003.pulsestages.algo.model.Spo2Sample
import io.github.alien2003.pulsestages.algo.model.StepsSample
import io.github.alien2003.pulsestages.data.hc.EstimatedSessionWrite
import io.github.alien2003.pulsestages.data.hc.HcAvailability
import io.github.alien2003.pulsestages.data.hc.HcFeature
import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.HcStage
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.OwnSessionStore
import io.github.alien2003.pulsestages.data.hc.SleepChanges
import io.github.alien2003.pulsestages.data.hc.SleepOriginStats
import io.github.alien2003.pulsestages.data.hc.StoredOwnSession
import io.github.alien2003.pulsestages.data.hc.VerifiedUpsert
import io.github.alien2003.pulsestages.data.hc.WriteResult
import java.util.UUID
import kotlin.math.abs

/**
 * In-memory [HealthRepository] that emulates the Health Connect semantics PulseStages relies on (verified facts in
 * research/hc-api.md):
 * - reads filter by record START time; heart-rate series and step records are looked up with 24 h of left padding
 *   and clipped to the window, exactly like [io.github.alien2003.pulsestages.data.hc.HealthConnectRepository];
 * - origin filtering (`packages == null` = any origin, an empty set = nothing);
 * - our writes are upserts scoped to (our package, clientRecordId) with deterministic ids
 *   (`nameUUIDFromBytes(package + record type + clientRecordId)`) and the platform rule "replace iff new version ≥
 *   stored version" ([UpsertRule.GREATER_OR_EQUAL]; [UpsertRule.STRICTLY_GREATER] emulates a stricter provider);
 *   lower versions are ignored silently; writes go through the same [VerifiedUpsert] algorithm as the real
 *   repository (read back, delete-then-insert fallback);
 * - deletes only ever touch our own records; unknown ids are ignored;
 * - a change log with tokens per package set: pages of [changesPageSize] entries, `hasMore`, the same token when
 *   nothing changed, expiry after [tokenLifetimeMs] (or [expireAllTokens]) → `tokenExpired`;
 * - permissions: revoking a required permission makes the corresponding calls throw PermissionDenied; optional
 *   channels return empty lists; without READ_HEALTH_DATA_HISTORY other apps' records starting before
 *   [historyStartMs] are invisible; in [inBackground] mode without READ_HEALTH_DATA_IN_BACKGROUND only reads
 *   filtered to exactly our own package work as asked; with [BackgroundRule.THROWS] every other read (any origin, or
 *   another package) and the Changes API throw PermissionDenied, like the platform's `SecurityException`
 *   (android15-release and later: `maybeEnforceOnlyCallingPackageDataRequested`); with [BackgroundRule.SELF_ONLY]
 *   those reads silently return our own records only and only `getChanges` throws (android14-release:
 *   `enforceSelfRead`, `RecordHelper.getReadTableWhereClause`);
 * - [availability] other than AVAILABLE makes every call throw Unavailable;
 * - error injection ([failOn], [quotaAfter]) for quota / transient / any [HealthDataException].
 *
 * Not thread-safe beyond simple `synchronized` blocks; meant for single-threaded tests (`runTest`).
 */
@Suppress("TooManyFunctions") // A fake of a wide interface plus its test-setup API.
class FakeHealthRepository(
    /** Our own package (data origin of written sessions). */
    val ownPackage: String = DEFAULT_OWN_PACKAGE,
    /** Current time in epoch ms (start-time-in-the-future check, token expiry, lastModified). */
    private val now: () -> Long,
) : HealthRepository {
    /** Every Health Connect operation the fake counts and can fail. */
    enum class Operation {
        /** [grantedPermissions]. */
        GRANTED_PERMISSIONS,

        /** [readSleepSessions] and [oldestSleepSessionStartMs] (both read sleep sessions). */
        READ_SLEEP_SESSIONS,

        /** [readHeartRate]. */
        READ_HEART_RATE,

        /** [readSteps]. */
        READ_STEPS,

        /** [readSpo2]. */
        READ_SPO2,

        /** [readRespiratoryRate]. */
        READ_RESPIRATORY_RATE,

        /** [readHrv]. */
        READ_HRV,

        /** [sleepChangesToken]. */
        CHANGES_TOKEN,

        /** [sleepChanges]. */
        CHANGES,

        /** [readOwnSessions]. */
        READ_OWN_SESSIONS,

        /** [writeEstimatedSession]. */
        WRITE,

        /** One chunk (≤ 100 ids) of [deleteOwnSessions]. */
        DELETE,

        /** [scanSleepOrigins]. */
        SCAN_ORIGINS,
    }

    /** What a read of other apps' data does in the background without READ_HEALTH_DATA_IN_BACKGROUND. */
    enum class BackgroundRule {
        /** Throws PermissionDenied (Health Connect built from android15-release and later). */
        THROWS,

        /**
         * Silently reads our own records only, as if the origin filter were our package; `getChanges` still throws
         * (Health Connect built from android14-release).
         */
        SELF_ONLY,
    }

    /** When an insert with an existing clientRecordId replaces the stored record. */
    enum class UpsertRule {
        /** Android 14+ platform: replace iff `newVersion >= storedVersion`. */
        GREATER_OR_EQUAL,

        /** Only a strictly higher version replaces (what the documentation literally promises). */
        STRICTLY_GREATER,
    }

    /** Availability; anything but AVAILABLE makes every suspend call throw [HealthDataException.Unavailable]. */
    var availability: HcAvailability = HcAvailability.AVAILABLE

    /** Granted permissions (everything by default). */
    val granted: MutableSet<String> =
        (HealthPermissions.REQUIRED + HealthPermissions.OPTIONAL + HealthPermissions.SPECIAL).toMutableSet()

    /** Available optional features (all by default). */
    val features: MutableSet<HcFeature> = HcFeature.entries.toMutableSet()

    /** The app is not in the foreground right now (a WorkManager run, or a foreground run that lost the foreground). */
    var inBackground: Boolean = false

    /** How background reads without READ_HEALTH_DATA_IN_BACKGROUND behave. */
    var backgroundRule: BackgroundRule = BackgroundRule.THROWS

    /** Without READ_HEALTH_DATA_HISTORY other apps' records starting before this are invisible; null = no limit. */
    var historyStartMs: Long? = null

    /** Upsert rule of our writes. */
    var upsertRule: UpsertRule = UpsertRule.GREATER_OR_EQUAL

    /** Change-log entries per [sleepChanges] page. */
    var changesPageSize: Int = DEFAULT_CHANGES_PAGE_SIZE

    /** Lifetime of a changes token after it was issued. */
    var tokenLifetimeMs: Long = TOKEN_LIFETIME_MS

    private val lock = Any()
    private val sessions = LinkedHashMap<String, HcSleepSession>()
    private val heartRateSeries = mutableListOf<HeartRateSeries>()
    private val stepRecords = mutableListOf<Owned<StepsSample>>()
    private val spo2 = InstantChannel<Spo2Sample>(Operation.READ_SPO2, HealthPermissions.READ_OXYGEN_SATURATION)
    private val respiratoryRate =
        InstantChannel<RespiratoryRateSample>(Operation.READ_RESPIRATORY_RATE, HealthPermissions.READ_RESPIRATORY_RATE)
    private val hrv = InstantChannel<HrvSample>(Operation.READ_HRV, HealthPermissions.READ_HEART_RATE_VARIABILITY)
    private val changeLog = mutableListOf<ChangeEntry>()
    private val tokens = mutableMapOf<String, TokenState>()
    private val failures = mutableListOf<InjectedFailure>()
    private val callLog = mutableListOf<Operation>()
    private var sequence = 0L
    private var tokenCounter = 0
    private var sessionCounter = 0

    /** Every operation called so far, in order. */
    val calls: List<Operation> get() = synchronized(lock) { callLog.toList() }

    /** Calls of [oldestSleepSessionStartMs] so far (each one is also a [Operation.READ_SLEEP_SESSIONS] call). */
    var oldestSessionLookups: Int = 0
        private set

    /** Number of calls of [operation]. */
    fun callCount(operation: Operation): Int = calls.count { it == operation }

    /** Forgets the recorded calls. */
    fun clearCalls() {
        synchronized(lock) { callLog.clear() }
    }

    // ---------------------------------------------------------------------------------------------- test setup

    /**
     * Adds a sleep session written by [packageName] (an UpsertionChange for the change log). The uuid is
     * deterministic (`nameUUIDFromBytes(package#n)`) unless given.
     */
    @Suppress("LongParameterList") // Mirrors the record fields a test may want to set.
    fun addSleepSession(
        packageName: String,
        startMs: Long,
        endMs: Long,
        stages: List<HcStage> = emptyList(),
        startZoneOffsetSec: Int? = 0,
        endZoneOffsetSec: Int? = 0,
        uuid: String? = null,
    ): HcSleepSession =
        synchronized(lock) {
            require(endMs > startMs) { "session end must be after start" }
            val id = uuid ?: UUID.nameUUIDFromBytes("$packageName#${++sessionCounter}".toByteArray()).toString()
            val session =
                HcSleepSession(
                    uuid = id,
                    packageName = packageName,
                    startMs = startMs,
                    endMs = endMs,
                    startZoneOffsetSec = startZoneOffsetSec,
                    endZoneOffsetSec = endZoneOffsetSec,
                    stages = stages,
                    title = null,
                    notes = null,
                    clientRecordId = null,
                    clientRecordVersion = 0,
                    lastModifiedMs = now(),
                )
            sessions[id] = session
            log(id, packageName, deleted = false)
            session
        }

    /** Changes another app's session in place (same uuid; an UpsertionChange). */
    fun updateSleepSession(
        uuid: String,
        transform: (HcSleepSession) -> HcSleepSession,
    ): HcSleepSession =
        synchronized(lock) {
            val current = checkNotNull(sessions[uuid]) { "no session $uuid" }
            val updated = transform(current).copy(uuid = uuid, packageName = current.packageName, lastModifiedMs = now())
            sessions[uuid] = updated
            log(uuid, updated.packageName, deleted = false)
            updated
        }

    /** Deletes a session as its app or the user would (a DeletionChange). */
    fun deleteSleepSession(uuid: String) {
        synchronized(lock) {
            val removed = sessions.remove(uuid) ?: return
            log(uuid, removed.packageName, deleted = true)
        }
    }

    /** Adds one heart-rate series record of [packageName] spanning `[startMs, endMs]` (defaults: first/last sample). */
    fun addHeartRateSeries(
        packageName: String,
        samples: List<HrSample>,
        startMs: Long = samples.minOf { it.timeMs },
        endMs: Long = samples.maxOf { it.timeMs },
    ) {
        synchronized(lock) { heartRateSeries += HeartRateSeries(packageName, startMs, endMs, samples.sortedBy { it.timeMs }) }
    }

    /**
     * Adds heart rate every [everyMs] in `[fromMs, toMs)` as one-sample records (like OHealth); [bpm] gives the value
     * at a time.
     */
    fun addHeartRate(
        packageName: String,
        fromMs: Long,
        toMs: Long,
        everyMs: Long = DEFAULT_HR_INTERVAL_MS,
        bpm: (Long) -> Double = { DEFAULT_BPM },
    ) {
        synchronized(lock) {
            var t = fromMs
            while (t < toMs) {
                heartRateSeries += HeartRateSeries(packageName, t, t, listOf(HrSample(t, bpm(t))))
                t += everyMs
            }
        }
    }

    /**
     * Deletes the heart-rate records of [packageName] that start in `[fromMs, toMs)` (as the source app does while it
     * replaces its records).
     */
    fun removeHeartRate(
        packageName: String,
        fromMs: Long,
        toMs: Long,
    ) {
        synchronized(lock) { heartRateSeries.removeAll { it.packageName == packageName && it.startMs >= fromMs && it.startMs < toMs } }
    }

    /** Adds a step record. */
    fun addSteps(
        packageName: String,
        startMs: Long,
        endMs: Long,
        count: Long,
    ) {
        synchronized(lock) { stepRecords += Owned(packageName, startMs, StepsSample(startMs, endMs, count)) }
    }

    /** Adds an SpO2 measurement. */
    fun addSpo2(
        packageName: String,
        timeMs: Long,
        percent: Double,
    ) {
        synchronized(lock) { spo2.records += Owned(packageName, timeMs, Spo2Sample(timeMs, percent)) }
    }

    /** Adds a respiratory-rate measurement. */
    fun addRespiratoryRate(
        packageName: String,
        timeMs: Long,
        rate: Double,
    ) {
        synchronized(lock) { respiratoryRate.records += Owned(packageName, timeMs, RespiratoryRateSample(timeMs, rate)) }
    }

    /** Adds an HRV measurement. */
    fun addHrv(
        packageName: String,
        timeMs: Long,
        rmssdMs: Double,
    ) {
        synchronized(lock) { hrv.records += Owned(packageName, timeMs, HrvSample(timeMs, rmssdMs)) }
    }

    /**
     * Stores one of OUR sessions directly, bypassing the version rule (e.g. a record left by a previous install of
     * the app with a higher version).
     */
    fun preloadOwnSession(write: EstimatedSessionWrite): HcSleepSession =
        synchronized(lock) {
            val session = write.toStored()
            sessions[session.uuid] = session
            log(session.uuid, ownPackage, deleted = false)
            session
        }

    /** Grants [permission]. */
    fun grant(permission: String) {
        synchronized(lock) { granted += permission }
    }

    /** Revokes [permission]. */
    fun revoke(permission: String) {
        synchronized(lock) { granted -= permission }
    }

    /** Makes every issued changes token invalid (as after 30+ days without syncing). */
    fun expireAllTokens() {
        synchronized(lock) { tokens.clear() }
    }

    /**
     * Makes the next calls of [operation] (any operation when null) throw [error]: the first [afterCalls] matching
     * calls succeed, then [times] calls fail.
     */
    fun failOn(
        operation: Operation?,
        error: HealthDataException,
        times: Int = 1,
        afterCalls: Int = 0,
    ) {
        synchronized(lock) { failures += InjectedFailure(operation, error, times, afterCalls) }
    }

    /** After [calls] more successful calls of any operation, every call fails with a quota error. */
    fun quotaAfter(calls: Int) {
        failOn(null, HealthDataException.QuotaExceeded("fake quota exhausted"), times = Int.MAX_VALUE, afterCalls = calls)
    }

    /** Removes every injected failure. */
    fun clearFailures() {
        synchronized(lock) { failures.clear() }
    }

    // ------------------------------------------------------------------------------------------------ inspection

    /** Our own sessions, sorted by start. */
    fun ownSessions(): List<HcSleepSession> =
        synchronized(lock) {
            sessions.values.filter { it.packageName == ownPackage }.sortedBy { it.startMs }
        }

    /** Every stored session of every app, sorted by start. */
    fun allSessions(): List<HcSleepSession> = synchronized(lock) { sessions.values.sortedBy { it.startMs } }

    /** The session with [uuid], or null. */
    fun session(uuid: String): HcSleepSession? = synchronized(lock) { sessions[uuid] }

    /** The id Health Connect gives our record with [clientRecordId]. */
    fun ownRecordId(clientRecordId: String): String =
        UUID.nameUUIDFromBytes("$ownPackage|SleepSessionRecord|$clientRecordId".toByteArray()).toString()

    // ---------------------------------------------------------------------------------------- HealthRepository

    override fun availability(): HcAvailability = availability

    override suspend fun grantedPermissions(): Set<String> {
        enter(Operation.GRANTED_PERMISSIONS)
        return synchronized(lock) { granted.toSet() }
    }

    override fun isFeatureAvailable(feature: HcFeature): Boolean = availability == HcAvailability.AVAILABLE && feature in features

    override suspend fun readSleepSessions(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HcSleepSession> {
        enter(Operation.READ_SLEEP_SESSIONS)
        if (packages != setOf(ownPackage) || HealthPermissions.WRITE_SLEEP !in granted) requirePermission(HealthPermissions.READ_SLEEP)
        return querySessions(startMs, endMs, readablePackages(packages))
    }

    override suspend fun oldestSleepSessionStartMs(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): Long? {
        enter(Operation.READ_SLEEP_SESSIONS)
        synchronized(lock) { oldestSessionLookups++ }
        if (packages != setOf(ownPackage) || HealthPermissions.WRITE_SLEEP !in granted) requirePermission(HealthPermissions.READ_SLEEP)
        return querySessions(startMs, endMs, readablePackages(packages)).firstOrNull()?.startMs
    }

    override suspend fun readHeartRate(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HrSample> {
        enter(Operation.READ_HEART_RATE)
        requirePermission(HealthPermissions.READ_HEART_RATE)
        if (startMs >= endMs || packages?.isEmpty() == true) return emptyList()
        val readable = readablePackages(packages)
        return synchronized(lock) {
            heartRateSeries
                .filter { it.startMs in (startMs - LEFT_PADDING_MS) until endMs }
                .filter { readable == null || it.packageName in readable }
                .filter { isVisible(it.packageName, it.startMs) }
                .flatMap { it.samples }
                .filter { it.timeMs in startMs until endMs }
                .sortedBy { it.timeMs }
        }
    }

    override suspend fun readSteps(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<StepsSample> {
        enter(Operation.READ_STEPS)
        requirePermission(HealthPermissions.READ_STEPS)
        if (startMs >= endMs || packages?.isEmpty() == true) return emptyList()
        val readable = readablePackages(packages)
        return synchronized(lock) {
            stepRecords
                .filter { it.startMs in (startMs - LEFT_PADDING_MS) until endMs }
                .filter { readable == null || it.packageName in readable }
                .filter { isVisible(it.packageName, it.startMs) }
                .map { it.value }
                .filter { it.endMs > startMs && it.startMs < endMs }
                .sortedBy { it.startMs }
        }
    }

    override suspend fun readSpo2(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<Spo2Sample> = readInstant(spo2, startMs, endMs, packages)

    override suspend fun readRespiratoryRate(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<RespiratoryRateSample> = readInstant(respiratoryRate, startMs, endMs, packages)

    override suspend fun readHrv(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HrvSample> = readInstant(hrv, startMs, endMs, packages)

    override suspend fun sleepChangesToken(packages: Set<String>): String {
        enter(Operation.CHANGES_TOKEN)
        requirePermission(HealthPermissions.READ_SLEEP)
        if (backgroundRule == BackgroundRule.THROWS) requireChangesAccess()
        if (packages.isEmpty()) throw HealthDataException.InvalidData("a changes token needs at least one package")
        return synchronized(lock) { issueToken(sequence, packages) }
    }

    override suspend fun sleepChanges(token: String): SleepChanges {
        enter(Operation.CHANGES)
        requireChangesAccess()
        return synchronized(lock) {
            val state = tokens[token]
            if (state == null || now() - state.issuedAtMs > tokenLifetimeMs) {
                return@synchronized SleepChanges(emptyList(), emptyList(), nextToken = "", hasMore = false, tokenExpired = true)
            }
            val pending = changeLog.filter { it.sequence > state.position && it.packageName in state.packages }
            val page = pending.take(changesPageSize)
            val next = if (page.isEmpty()) token else issueToken(page.last().sequence, state.packages)
            SleepChanges(
                upserted =
                    page
                        .filterNot { it.deleted }
                        .mapNotNull { sessions[it.uuid] }
                        .filter { isVisible(it.packageName, it.startMs) }
                        .distinctBy { it.uuid },
                deletedIds = page.filter { it.deleted }.map { it.uuid }.distinct(),
                nextToken = next,
                hasMore = pending.size > page.size,
                tokenExpired = false,
            )
        }
    }

    override suspend fun readOwnSessions(
        startMs: Long,
        endMs: Long,
    ): List<HcSleepSession> {
        enter(Operation.READ_OWN_SESSIONS)
        if (HealthPermissions.WRITE_SLEEP !in granted) requirePermission(HealthPermissions.READ_SLEEP)
        return querySessions(startMs, endMs, setOf(ownPackage))
    }

    override suspend fun writeEstimatedSession(write: EstimatedSessionWrite): WriteResult {
        enter(Operation.WRITE)
        requirePermission(HealthPermissions.WRITE_SLEEP)
        return VerifiedUpsert.write(store, write)
    }

    override suspend fun deleteOwnSessions(clientRecordIds: List<String>) {
        val ids = clientRecordIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return
        ids.chunked(DELETE_CHUNK_SIZE).forEach { chunk ->
            enter(Operation.DELETE)
            requirePermission(HealthPermissions.WRITE_SLEEP)
            synchronized(lock) { chunk.forEach(::deleteOwn) }
        }
    }

    override suspend fun scanSleepOrigins(
        startMs: Long,
        endMs: Long,
    ): List<SleepOriginStats> {
        enter(Operation.SCAN_ORIGINS)
        requirePermission(HealthPermissions.READ_SLEEP)
        return querySessions(startMs, endMs, readablePackages(packages = null))
            .groupBy { it.packageName }
            .map { (packageName, list) -> SleepOriginStats(packageName, list.size, list.maxOf { it.endMs }) }
            .sortedWith(compareByDescending<SleepOriginStats> { it.sessionCount }.thenBy { it.packageName })
    }

    // ------------------------------------------------------------------------------------------------ internals

    /** The platform-like store behind [writeEstimatedSession] (no extra calls are counted for its read-backs). */
    private val store =
        object : OwnSessionStore {
            override suspend fun insert(write: EstimatedSessionWrite): String = synchronized(lock) { insertOwn(write) }

            override suspend fun findOwn(
                clientRecordId: String,
                startMs: Long,
            ): StoredOwnSession? =
                synchronized(lock) {
                    sessions.values
                        .firstOrNull {
                            it.packageName == ownPackage && it.clientRecordId == clientRecordId &&
                                abs(it.startMs - startMs) <= LOOKUP_MARGIN_MS
                        }?.let { StoredOwnSession(it.uuid, it.clientRecordVersion, it.stages.size) }
                }

            override suspend fun deleteOwn(clientRecordIds: List<String>) {
                synchronized(lock) { clientRecordIds.forEach(::deleteOwn) }
            }
        }

    private fun insertOwn(write: EstimatedSessionWrite): String {
        validate(write)
        val id = ownRecordId(write.clientRecordId)
        val stored = sessions[id]
        val replaces =
            stored == null ||
                when (upsertRule) {
                    UpsertRule.GREATER_OR_EQUAL -> write.clientRecordVersion >= stored.clientRecordVersion
                    UpsertRule.STRICTLY_GREATER -> write.clientRecordVersion > stored.clientRecordVersion
                }
        if (replaces) {
            sessions[id] = write.toStored()
            log(id, ownPackage, deleted = false)
        }
        return id
    }

    private fun deleteOwn(clientRecordId: String) {
        val id = ownRecordId(clientRecordId)
        val stored = sessions[id] ?: return
        if (stored.packageName != ownPackage) return
        sessions.remove(id)
        log(id, ownPackage, deleted = true)
    }

    private fun validate(write: EstimatedSessionWrite) {
        val problems = mutableListOf<String>()
        if (write.endMs <= write.startMs) problems += "startTime must be before endTime"
        if (write.startMs > now()) problems += "start time must not be in the future"
        val sorted = write.stages.sortedBy { it.startMs }
        sorted.forEachIndexed { index, stage ->
            if (stage.endMs <= stage.startMs) problems += "stage $index: startTime must be before endTime"
            if (stage.startMs < write.startMs || stage.endMs > write.endMs) problems += "stage $index outside the session"
            if (stage.type !in VALID_STAGE_TYPES) problems += "stage $index: invalid type ${stage.type}"
            val next = sorted.getOrNull(index + 1)
            if (next != null && next.startMs < stage.endMs) problems += "stages $index and ${index + 1} overlap"
        }
        if (problems.isNotEmpty()) throw HealthDataException.InvalidData("invalid session: ${problems.joinToString("; ")}")
    }

    private fun EstimatedSessionWrite.toStored(): HcSleepSession =
        HcSleepSession(
            uuid = ownRecordId(clientRecordId),
            packageName = ownPackage,
            startMs = startMs,
            endMs = endMs,
            startZoneOffsetSec = startZoneOffsetSec,
            endZoneOffsetSec = endZoneOffsetSec,
            stages = stages.sortedBy { it.startMs },
            title = title,
            notes = notes,
            clientRecordId = clientRecordId,
            clientRecordVersion = clientRecordVersion,
            lastModifiedMs = now(),
        )

    private fun querySessions(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HcSleepSession> {
        if (startMs >= endMs || packages?.isEmpty() == true) return emptyList()
        return synchronized(lock) {
            sessions.values
                .filter { it.startMs in startMs until endMs }
                .filter { packages == null || it.packageName in packages }
                .filter { isVisible(it.packageName, it.startMs) }
                .sortedWith(compareBy({ it.startMs }, { it.uuid }))
        }
    }

    private suspend fun <T> readInstant(
        channel: InstantChannel<T>,
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<T> {
        enter(channel.operation)
        if (channel.permission !in granted || startMs >= endMs || packages?.isEmpty() == true) return emptyList()
        val readable = readablePackages(packages)
        return synchronized(lock) {
            channel.records
                .filter { it.startMs in startMs until endMs }
                .filter { readable == null || it.packageName in readable }
                .filter { isVisible(it.packageName, it.startMs) }
                .sortedBy { it.startMs }
                .map { it.value }
        }
    }

    /** Whether a record of [packageName] starting at [startMs] is visible to us (the history limit). */
    private fun isVisible(
        packageName: String,
        startMs: Long,
    ): Boolean {
        val historyStart = historyStartMs
        val beforeHistory = historyStart != null && HealthPermissions.READ_HEALTH_DATA_HISTORY !in granted && startMs < historyStart
        return packageName == ownPackage || !beforeHistory
    }

    /**
     * The platform's background rule: without READ_HEALTH_DATA_IN_BACKGROUND a caller that is not in the foreground
     * may only read with an origin filter of exactly its own package. Anything else is a SecurityException
     * ([BackgroundRule.THROWS]) or is silently read as our own package ([BackgroundRule.SELF_ONLY]). Returns the origin
     * filter the read actually uses (null = any origin).
     */
    private fun readablePackages(packages: Set<String>?): Set<String>? {
        val backgroundBlocked = inBackground && HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND !in synchronized(lock) { granted.toSet() }
        // An empty origin set reads nothing (the repository never calls Health Connect for it).
        if (!backgroundBlocked || packages?.isEmpty() == true || packages == setOf(ownPackage)) return packages
        if (backgroundRule == BackgroundRule.SELF_ONLY) return setOf(ownPackage)
        throw HealthDataException.PermissionDenied(
            "fake: reading other apps' data in the background needs READ_HEALTH_DATA_IN_BACKGROUND (origins $packages)",
        )
    }

    private fun requirePermission(permission: String) {
        if (permission !in synchronized(lock) { granted.toSet() }) {
            throw HealthDataException.PermissionDenied("fake: $permission not granted")
        }
    }

    private fun requireChangesAccess() {
        requirePermission(HealthPermissions.READ_SLEEP)
        if (inBackground && HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND !in granted) {
            throw HealthDataException.PermissionDenied("fake: getChanges in the background without READ_HEALTH_DATA_IN_BACKGROUND")
        }
    }

    /** Records the call, then throws Unavailable or an injected failure. */
    private fun enter(operation: Operation) {
        val failure =
            synchronized(lock) {
                callLog += operation
                failures.firstOrNull { it.matches(operation) }?.let { injected ->
                    if (injected.skip > 0) {
                        injected.skip--
                        null
                    } else {
                        injected.remaining--
                        if (injected.remaining <= 0) failures.remove(injected)
                        injected.error
                    }
                }
            }
        if (availability != HcAvailability.AVAILABLE) throw HealthDataException.Unavailable("fake: Health Connect is $availability")
        if (failure != null) throw failure
    }

    private fun log(
        uuid: String,
        packageName: String,
        deleted: Boolean,
    ) {
        changeLog += ChangeEntry(++sequence, uuid, packageName, deleted)
    }

    private fun issueToken(
        position: Long,
        packages: Set<String>,
    ): String {
        val token = "token-${++tokenCounter}"
        tokens[token] = TokenState(position, packages.toSet(), now())
        return token
    }

    private class HeartRateSeries(
        val packageName: String,
        val startMs: Long,
        val endMs: Long,
        val samples: List<HrSample>,
    )

    private class Owned<T>(
        val packageName: String,
        val startMs: Long,
        val value: T,
    )

    /** Records of one optional instantaneous channel and what reading it needs. */
    private class InstantChannel<T>(
        val operation: Operation,
        val permission: String,
    ) {
        val records = mutableListOf<Owned<T>>()
    }

    private data class ChangeEntry(
        val sequence: Long,
        val uuid: String,
        val packageName: String,
        val deleted: Boolean,
    )

    private data class TokenState(
        val position: Long,
        val packages: Set<String>,
        val issuedAtMs: Long,
    )

    private class InjectedFailure(
        val operation: Operation?,
        val error: HealthDataException,
        var remaining: Int,
        var skip: Int,
    ) {
        fun matches(call: Operation): Boolean = operation == null || operation == call
    }

    /** Defaults. */
    companion object {
        /** Package name used for our own sessions unless another is given. */
        const val DEFAULT_OWN_PACKAGE: String = "io.github.alien2003.pulsestages"

        /** Heart-rate sampling interval of [addHeartRate] (OHealth writes about one sample per 2 minutes). */
        const val DEFAULT_HR_INTERVAL_MS: Long = 120_000L

        /** Heart rate of [addHeartRate] unless given. */
        const val DEFAULT_BPM: Double = 60.0

        /** Default [changesPageSize] (the platform page). */
        const val DEFAULT_CHANGES_PAGE_SIZE: Int = 1000

        /** Default [tokenLifetimeMs]: 30 days. */
        const val TOKEN_LIFETIME_MS: Long = 30L * 24 * 60 * 60 * 1000

        private const val LEFT_PADDING_MS: Long = 24L * 60 * 60 * 1000
        private const val LOOKUP_MARGIN_MS: Long = 60_000L
        private const val DELETE_CHUNK_SIZE = 100
        private val VALID_STAGE_TYPES = 0..7
    }
}
