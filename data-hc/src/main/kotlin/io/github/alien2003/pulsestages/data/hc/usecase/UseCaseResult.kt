package io.github.alien2003.pulsestages.data.hc.usecase

import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason
import io.github.alien2003.pulsestages.data.hc.pipeline.HealthAccessGuard
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Result of a use case called by the UI. Health Connect problems are values, never exceptions, so a screen can show
 * them (error state, "grant" button, "try again later").
 */
sealed interface UseCaseResult<out T> {
    /** The use case produced [value]. */
    data class Success<out T>(
        /** The result. */
        val value: T,
    ) : UseCaseResult<T>

    /** The night (or other item) asked for is not in the ledger. */
    data object NotFound : UseCaseResult<Nothing>

    /** The night could not be estimated with the current parameters ([outcome] says why). */
    data class NotEstimated(
        /** Reason, coverage and English detail. */
        val outcome: EstimationOutcome.NotEstimated,
    ) : UseCaseResult<Nothing>

    /** Nothing was read: Health Connect is unavailable or a permission is missing. */
    data class Blocked(
        /** Why. */
        val reason: BlockedReason,
        /** English detail for the log, if any. */
        val detail: String? = null,
    ) : UseCaseResult<Nothing>

    /** The use case failed. */
    data class Failed(
        /** English detail for the log. */
        val message: String,
        /** True for quota and transient Health Connect errors: trying again later is expected to work. */
        val retryable: Boolean,
    ) : UseCaseResult<Nothing>
}

/** [block] applied to the value of a [UseCaseResult.Success]; every other result is passed through. */
inline fun <T, R> UseCaseResult<T>.andThen(block: (T) -> UseCaseResult<R>): UseCaseResult<R> =
    when (this) {
        is UseCaseResult.Success -> block(value)
        is UseCaseResult.NotFound -> this
        is UseCaseResult.NotEstimated -> this
        is UseCaseResult.Blocked -> this
        is UseCaseResult.Failed -> this
    }

/**
 * Runs [block] and turns its failures into results: Health Connect errors into [UseCaseResult.Blocked] (permission
 * revoked / background rule / Health Connect gone, classified by [guard]) or [UseCaseResult.Failed] (quota and
 * transient errors are retryable, invalid data is not); any other exception is logged and becomes a non-retryable
 * [UseCaseResult.Failed]. Cancellation is rethrown.
 */
@Suppress("TooGenericExceptionCaught") // A use case called by the UI reports failures as values, it never crashes the screen.
internal suspend fun <T> runUseCase(
    guard: HealthAccessGuard,
    logger: AppLogger,
    tag: String,
    nightKey: String? = null,
    block: suspend () -> UseCaseResult<T>,
): UseCaseResult<T> =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: HealthDataException) {
        logger.w(tag, "Health Connect error: ${e.message}", nightKey = nightKey, throwable = e)
        e.toUseCaseResult(guard)
    } catch (e: Exception) {
        logger.e(tag, "failed unexpectedly: ${e.javaClass.simpleName}: ${e.message}", nightKey = nightKey, throwable = e)
        UseCaseResult.Failed("${e.javaClass.simpleName}: ${e.message}", retryable = false)
    }

/** The [UseCaseResult] of a Health Connect error (see [runUseCase]). */
internal suspend fun HealthDataException.toUseCaseResult(guard: HealthAccessGuard): UseCaseResult<Nothing> =
    when (this) {
        is HealthDataException.PermissionDenied -> UseCaseResult.Blocked(guard.reasonForDenial(), message)
        is HealthDataException.Unavailable -> UseCaseResult.Blocked(BlockedReason.HEALTH_CONNECT_UNAVAILABLE, message)
        is HealthDataException.QuotaExceeded, is HealthDataException.Transient -> UseCaseResult.Failed(message.orEmpty(), retryable = true)
        is HealthDataException.InvalidData -> UseCaseResult.Failed(message.orEmpty(), retryable = false)
    }
