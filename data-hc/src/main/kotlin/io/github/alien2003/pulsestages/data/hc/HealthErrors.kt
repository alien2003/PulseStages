package io.github.alien2003.pulsestages.data.hc

import android.os.Build
import android.os.RemoteException
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Suspends for a while; abstracted so that tests run the backoff in virtual time. */
fun interface Sleeper {
    /** Suspends for [millis] milliseconds. */
    suspend fun sleep(millis: Long)
}

/** [Sleeper] backed by [delay] (virtual time under `kotlinx-coroutines-test`). */
object DelaySleeper : Sleeper {
    override suspend fun sleep(millis: Long) {
        delay(millis)
    }
}

/**
 * Retry schedule for retryable [HealthDataException]s ([HealthDataException.QuotaExceeded] and
 * [HealthDataException.Transient]): after the first attempt, one retry per entry of [delaysMs], waiting that long
 * before it. Jitter-free on purpose so that runs are deterministic.
 *
 * @property delaysMs waits before the 1st, 2nd, ... retry, in milliseconds.
 */
data class RetryPolicy(
    val delaysMs: List<Long>,
) {
    init {
        require(delaysMs.all { it >= 0 }) { "delays must not be negative: $delaysMs" }
    }

    /** Policies. */
    companion object {
        /** Exponential backoff 1 s, 2 s, 4 s, 8 s, 16 s: at most six attempts and 31 s of waiting per call. */
        val DEFAULT: RetryPolicy = RetryPolicy(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L))

        /** No retries. */
        val NONE: RetryPolicy = RetryPolicy(emptyList())
    }
}

/** True for errors that may succeed when retried later (quota and transient errors). */
val HealthDataException.isRetryable: Boolean
    get() = this is HealthDataException.QuotaExceeded || this is HealthDataException.Transient

/**
 * Runs [block] and retries it according to [policy] while it throws a [retryable][isRetryable]
 * [HealthDataException]; any other exception (including cancellation) propagates immediately. After the last retry
 * the last error is rethrown. [onRetry] is told about every retry (1-based attempt number, the wait, the error).
 */
suspend fun <T> retryHealthCall(
    policy: RetryPolicy,
    sleeper: Sleeper,
    onRetry: (retry: Int, delayMs: Long, error: HealthDataException) -> Unit = { _, _, _ -> },
    block: suspend () -> T,
): T {
    var retries = 0
    while (true) {
        try {
            return block()
        } catch (e: HealthDataException) {
            if (!e.isRetryable || retries >= policy.delaysMs.size) throw e
            val wait = policy.delaysMs[retries]
            retries++
            onRetry(retries, wait, e)
            sleeper.sleep(wait)
        }
    }
}

/**
 * Pure mapping of exceptions thrown by the Health Connect client into [HealthDataException]. Verified mapping of
 * connect-client 1.1.0 (see research/hc-api.md §6):
 * - [SecurityException] → [HealthDataException.PermissionDenied];
 * - [IllegalStateException] whose cause is a platform `HealthConnectException` (API 34+) with error code
 *   [PlatformErrorCodes.RATE_LIMIT_EXCEEDED] or [PlatformErrorCodes.DATA_SYNC_IN_PROGRESS] →
 *   [HealthDataException.QuotaExceeded]; with [PlatformErrorCodes.UNSUPPORTED_OPERATION] →
 *   [HealthDataException.Unavailable]; any other [IllegalStateException] → [HealthDataException.Transient], or
 *   [HealthDataException.Unavailable] when Health Connect itself is gone (`getOrCreate` throws
 *   `IllegalStateException("Service not available")` in that case);
 * - [RemoteException] / [IOException] → [HealthDataException.Transient] (on the Android 9–13 APK path a rate limit
 *   is indistinguishable and arrives as a [RemoteException], so it is retried with backoff as well);
 * - [IllegalArgumentException] (including [NumberFormatException]) → [HealthDataException.InvalidData];
 * - [UnsupportedOperationException] → [HealthDataException.Unavailable];
 * - anything else → [HealthDataException.Transient].
 *
 * Never pass a [CancellationException]: callers rethrow it before mapping (it is an [IllegalStateException]).
 */
object HealthExceptionMapper {
    /**
     * Maps [error] thrown by [operation]. [platformErrorCode] extracts the error code of a platform
     * `HealthConnectException` (null for anything else; see [PlatformErrorCodes.of]); [isHealthConnectAvailable]
     * is only consulted for otherwise unclassified [IllegalStateException]s.
     */
    fun map(
        operation: String,
        error: Throwable,
        platformErrorCode: (Throwable) -> Int? = { null },
        isHealthConnectAvailable: () -> Boolean = { true },
    ): HealthDataException {
        val message = "$operation failed: ${error.javaClass.simpleName}: ${error.message}"
        // The platform exception is the cause of the client's exception, possibly one level deeper when
        // kotlinx-coroutines recovered the stack trace (it rethrows a copy whose cause is the original).
        val code = causeChain(error).firstNotNullOfOrNull(platformErrorCode)
        return when {
            error is HealthDataException -> {
                error
            }

            code != null && (error is IllegalStateException || platformErrorCode(error) != null) -> {
                fromPlatformCode(code, message, error, isHealthConnectAvailable)
            }

            error is SecurityException -> {
                HealthDataException.PermissionDenied(message, error)
            }

            error is UnsupportedOperationException -> {
                HealthDataException.Unavailable(message, error)
            }

            error is IllegalArgumentException -> {
                HealthDataException.InvalidData(message, error)
            }

            error is IllegalStateException -> {
                unclassifiedState(message, error, isHealthConnectAvailable)
            }

            error is RemoteException || error is IOException -> {
                HealthDataException.Transient(message, error)
            }

            else -> {
                HealthDataException.Transient(message, error)
            }
        }
    }

    /** True when [error] or one of its causes is a [RemoteException] (stack-trace recovery may add a level). */
    fun isCausedByRemoteException(error: Throwable): Boolean = causeChain(error).any { it is RemoteException }

    private fun causeChain(error: Throwable): Sequence<Throwable> = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH)

    private const val MAX_CAUSE_DEPTH = 8

    private fun fromPlatformCode(
        code: Int,
        message: String,
        error: Throwable,
        isHealthConnectAvailable: () -> Boolean,
    ): HealthDataException =
        when (code) {
            PlatformErrorCodes.RATE_LIMIT_EXCEEDED, PlatformErrorCodes.DATA_SYNC_IN_PROGRESS -> {
                HealthDataException.QuotaExceeded("$message (platform error code $code)", error)
            }

            PlatformErrorCodes.UNSUPPORTED_OPERATION -> {
                HealthDataException.Unavailable("$message (platform error code $code)", error)
            }

            PlatformErrorCodes.SECURITY -> {
                HealthDataException.PermissionDenied("$message (platform error code $code)", error)
            }

            PlatformErrorCodes.INVALID_ARGUMENT -> {
                HealthDataException.InvalidData("$message (platform error code $code)", error)
            }

            else -> {
                unclassifiedState("$message (platform error code $code)", error, isHealthConnectAvailable)
            }
        }

    private fun unclassifiedState(
        message: String,
        error: Throwable,
        isHealthConnectAvailable: () -> Boolean,
    ): HealthDataException =
        if (isHealthConnectAvailable()) {
            HealthDataException.Transient(message, error)
        } else {
            HealthDataException.Unavailable(message, error)
        }
}

/**
 * Error codes of the platform `android.health.connect.HealthConnectException` (API 34+), copied from the platform
 * (`ERROR_*` constants) so that no platform field is touched on older devices.
 */
object PlatformErrorCodes {
    /** `ERROR_INVALID_ARGUMENT`. */
    const val INVALID_ARGUMENT: Int = 3

    /** `ERROR_SECURITY`. */
    const val SECURITY: Int = 5

    /** `ERROR_RATE_LIMIT_EXCEEDED`: the caller exhausted its quota. */
    const val RATE_LIMIT_EXCEEDED: Int = 7

    /** `ERROR_DATA_SYNC_IN_PROGRESS`: reads and writes are blocked; try again later. */
    const val DATA_SYNC_IN_PROGRESS: Int = 8

    /** `ERROR_UNSUPPORTED_OPERATION`. */
    const val UNSUPPORTED_OPERATION: Int = 9

    /**
     * The error code of [error] if it is a platform `HealthConnectException`, otherwise null. Always null below
     * API 34, where the class does not exist (the class is only referenced from an API-34-only holder).
     */
    fun of(error: Throwable): Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) Api34.errorCode(error) else null

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private object Api34 {
        fun errorCode(error: Throwable): Int? = (error as? android.health.connect.HealthConnectException)?.errorCode
    }
}

/**
 * Runs [block] and converts every exception except [CancellationException] into a [HealthDataException] with
 * [mapper]. Existing [HealthDataException]s pass through unchanged.
 */
@Suppress("TooGenericExceptionCaught") // This is the single place where client exceptions are classified.
suspend fun <T> mapHealthErrors(
    mapper: (Throwable) -> HealthDataException,
    block: suspend () -> T,
): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: HealthDataException) {
        throw e
    } catch (e: Exception) {
        throw mapper(e)
    }
