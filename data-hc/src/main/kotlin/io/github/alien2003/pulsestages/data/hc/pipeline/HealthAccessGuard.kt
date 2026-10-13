package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.data.hc.HcAvailability
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * Process-wide lock around every change of the night ledger (`estimated_nights`, `night_sources`,
 * `source_sessions`, `night_baselines`) together with the matching Health Connect writes, so that concurrent
 * sync / backfill / recompute / erase runs never interleave. Not reentrant: never nest [withLock] calls.
 */
object LedgerLock {
    private val mutex = Mutex()

    /** Runs [block] while holding the lock. */
    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

/**
 * Result of [HealthAccessGuard.check].
 *
 * @property blockedReason why nothing may be read, or null when the run may proceed.
 * @property granted the granted Health Connect permissions (empty when they could not be read).
 * @property missingRequired required permissions that are not granted.
 * @property detail English detail of the problem (for the log), if any.
 */
data class AccessCheck(
    val blockedReason: BlockedReason?,
    val granted: Set<String>,
    val missingRequired: Set<String>,
    val detail: String? = null,
) {
    /** True when the run may read and write. */
    val allowed: Boolean get() = blockedReason == null
}

/**
 * Checks, before any read, that Health Connect is available and the required permissions are granted, and the
 * background guard: a background run without READ_HEALTH_DATA_IN_BACKGROUND must not read at all, because Health
 * Connect rejects every read of other apps' data (and `getChanges`) from the background with a SecurityException; only
 * reads filtered to exactly our own package still work (AOSP `HealthConnectServiceImpl.readRecords`, self-read
 * enforcement).
 */
class HealthAccessGuard
    @Inject
    constructor(
        private val repository: HealthRepository,
    ) {
        /** Checks access for a run in the background ([inBackground]) that needs [required]. */
        suspend fun check(
            inBackground: Boolean,
            required: Set<String> = HealthPermissions.REQUIRED,
        ): AccessCheck {
            val granted =
                if (repository.availability() == HcAvailability.AVAILABLE) {
                    try {
                        Result.success(repository.grantedPermissions())
                    } catch (e: HealthDataException.Unavailable) {
                        Result.failure(e)
                    }
                } else {
                    Result.failure(HealthDataException.Unavailable("Health Connect is ${repository.availability()}"))
                }
            return granted.fold(
                onSuccess = { permissions -> checkPermissions(permissions, inBackground, required) },
                onFailure = { error -> AccessCheck(BlockedReason.HEALTH_CONNECT_UNAVAILABLE, emptySet(), required, error.message) },
            )
        }

        /**
         * Why Health Connect refused a call with [HealthDataException.PermissionDenied] after [check] let the run
         * start. The granted permissions are read again: when every one of [required] is still granted but
         * READ_HEALTH_DATA_IN_BACKGROUND is not, the refusal comes from the platform's background rule (the app left
         * the foreground during the run) → [BlockedReason.BACKGROUND_PERMISSION_MISSING]; when Health Connect is gone
         * → [BlockedReason.HEALTH_CONNECT_UNAVAILABLE]; otherwise a permission was revoked →
         * [BlockedReason.PERMISSION_MISSING].
         */
        suspend fun reasonForDenial(required: Set<String> = HealthPermissions.REQUIRED): BlockedReason {
            val granted =
                try {
                    repository.grantedPermissions()
                } catch (e: HealthDataException) {
                    return reasonWithoutPermissions(e)
                }
            val onlyBackgroundMissing = required.all { it in granted } && HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND !in granted
            return if (onlyBackgroundMissing) BlockedReason.BACKGROUND_PERMISSION_MISSING else BlockedReason.PERMISSION_MISSING
        }

        private fun reasonWithoutPermissions(error: HealthDataException): BlockedReason =
            when (error) {
                is HealthDataException.Unavailable -> BlockedReason.HEALTH_CONNECT_UNAVAILABLE
                else -> BlockedReason.PERMISSION_MISSING
            }

        private fun checkPermissions(
            granted: Set<String>,
            inBackground: Boolean,
            required: Set<String>,
        ): AccessCheck {
            val missing = required.filterNotTo(linkedSetOf()) { it in granted }
            val backgroundBlocked = inBackground && HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND !in granted
            return when {
                missing.isNotEmpty() -> {
                    AccessCheck(BlockedReason.PERMISSION_MISSING, granted, missing, "missing $missing")
                }

                backgroundBlocked -> {
                    AccessCheck(
                        BlockedReason.BACKGROUND_PERMISSION_MISSING,
                        granted,
                        missing,
                        "background read not granted",
                    )
                }

                else -> {
                    AccessCheck(null, granted, missing)
                }
            }
        }
    }
