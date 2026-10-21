package io.github.alien2003.pulsestages.data.hc

import android.app.ActivityManager
import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.algo.model.HrvSample
import io.github.alien2003.pulsestages.algo.model.RespiratoryRateSample
import io.github.alien2003.pulsestages.algo.model.Spo2Sample
import io.github.alien2003.pulsestages.algo.model.StepsSample
import io.github.alien2003.pulsestages.data.hc.di.OwnPackageName
import io.github.alien2003.pulsestages.data.hc.di.PlatformHealthRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Health Connect treats this app as being in the foreground right now: one of its activities is in front, or
 * it runs a foreground service (WorkManager's, for a long-running or an expedited work). Without
 * READ_HEALTH_DATA_IN_BACKGROUND only an app in the foreground may read other apps' data. Asked again before (and
 * after) every such read: the state changes while a run goes on.
 */
fun interface AppForegroundState {
    /** True while the app is in the foreground for Health Connect. */
    fun isInForeground(): Boolean
}

/**
 * [AppForegroundState] from the process importance: `IMPORTANCE_FOREGROUND` (an activity in front) or
 * `IMPORTANCE_FOREGROUND_SERVICE` (a foreground service). Anything less important (a visible activity behind another
 * one, a perceptible or background process, an expedited job without a foreground service) counts as background, so
 * the check errs on the side of refusing a read.
 */
object ProcessImportanceForegroundState : AppForegroundState {
    override fun isInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
    }
}

/**
 * The [HealthRepository] the app uses: [delegate] (the real Health Connect repository) with Health Connect's
 * background rule enforced the same way on every Health Connect version.
 *
 * Without READ_HEALTH_DATA_IN_BACKGROUND an app that is not in the foreground ([foreground]) may only read its own
 * data. Health Connect built from android15-release and later rejects other reads with a SecurityException
 * (`HealthConnectServiceImpl.maybeEnforceOnlyCallingPackageDataRequested`), but android14-release silently replaces the
 * origin filter by the caller's own package (`enforceSelfRead`, `RecordHelper.getReadTableWhereClause`): the read
 * returns none of the source apps' records and no error, which a re-read would take for deleted source sessions, a
 * backfill for an empty history and a recompute for nights without sources. This class turns the silent variant into
 * the error the pipeline already handles: every read of other apps' data (any origin, or a package other than ours),
 * the origin scan and the Changes API check the foreground state before the call, and again after it when the app was
 * in the foreground before, and throw [HealthDataException.PermissionDenied] when the app is (or became) a background
 * caller without the permission; the result of a call during which the app left the foreground is discarded. The
 * callers classify the refusal as [io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason.BACKGROUND_PERMISSION_MISSING]
 * (`HealthAccessGuard.reasonForDenial`). Reads filtered to exactly our own package, the changes token, writes and
 * deletes of our own records pass through unchanged.
 */
@Singleton
@Suppress("TooManyFunctions") // The wide HealthRepository boundary.
class BackgroundRuleHealthRepository
    @Inject
    constructor(
        @param:PlatformHealthRepository private val delegate: HealthRepository,
        @param:OwnPackageName private val ownPackage: String,
        private val foreground: AppForegroundState,
    ) : HealthRepository by delegate {
        override suspend fun readSleepSessions(
            startMs: Long,
            endMs: Long,
            packages: Set<String>?,
        ): List<HcSleepSession> = checked(packages) { delegate.readSleepSessions(startMs, endMs, packages) }

        override suspend fun oldestSleepSessionStartMs(
            startMs: Long,
            endMs: Long,
            packages: Set<String>?,
        ): Long? = checked(packages) { delegate.oldestSleepSessionStartMs(startMs, endMs, packages) }

        override suspend fun readHeartRate(
            startMs: Long,
            endMs: Long,
            packages: Set<String>?,
        ): List<HrSample> = checked(packages) { delegate.readHeartRate(startMs, endMs, packages) }

        override suspend fun readSteps(
            startMs: Long,
            endMs: Long,
            packages: Set<String>?,
        ): List<StepsSample> = checked(packages) { delegate.readSteps(startMs, endMs, packages) }

        override suspend fun newestSteps(
            startMs: Long,
            endMs: Long,
            packages: Set<String>?,
        ): StepsSample? = checked(packages) { delegate.newestSteps(startMs, endMs, packages) }

        override suspend fun readSpo2(
            startMs: Long,
            endMs: Long,
            packages: Set<String>?,
        ): List<Spo2Sample> = checked(packages) { delegate.readSpo2(startMs, endMs, packages) }

        override suspend fun readRespiratoryRate(
            startMs: Long,
            endMs: Long,
            packages: Set<String>?,
        ): List<RespiratoryRateSample> = checked(packages) { delegate.readRespiratoryRate(startMs, endMs, packages) }

        override suspend fun readHrv(
            startMs: Long,
            endMs: Long,
            packages: Set<String>?,
        ): List<HrvSample> = checked(packages) { delegate.readHrv(startMs, endMs, packages) }

        override suspend fun sleepChanges(token: String): SleepChanges = checked(packages = null) { delegate.sleepChanges(token) }

        override suspend fun scanSleepOrigins(
            startMs: Long,
            endMs: Long,
        ): List<SleepOriginStats> = checked(packages = null) { delegate.scanSleepOrigins(startMs, endMs) }

        /** Runs [call], a read of [packages] (null = any origin), under the background rule (see the class documentation). */
        private suspend fun <T> checked(
            packages: Set<String>?,
            call: suspend () -> T,
        ): T {
            // Own data (and an empty origin set, which reads nothing) may be read from the background.
            if (packages != null && (packages.isEmpty() || packages == setOf(ownPackage))) return call()
            val before = foreground.isInForeground()
            if (!before) requireBackgroundPermission(packages)
            val result = call()
            if (before && !foreground.isInForeground()) requireBackgroundPermission(packages)
            return result
        }

        private suspend fun requireBackgroundPermission(packages: Set<String>?) {
            if (HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND in delegate.grantedPermissions()) return
            val origins = packages?.sorted()?.joinToString() ?: "any origin"
            throw HealthDataException.PermissionDenied(
                "the app is not in the foreground and READ_HEALTH_DATA_IN_BACKGROUND is not granted: refusing to read $origins",
            )
        }
    }
