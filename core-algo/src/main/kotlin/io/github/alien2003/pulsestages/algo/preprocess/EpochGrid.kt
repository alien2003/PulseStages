package io.github.alien2003.pulsestages.algo.preprocess

/**
 * The regular epoch grid of one night.
 *
 * Session epoch `k` covers `[sessionStartMs + k·E, sessionStartMs + (k+1)·E)` with `E = epochSec·1000`. The
 * session has [sessionEpochs] `= ceil((end − start)/E)` epochs `k = 0 … N−1`; the last one may be partial and is
 * clipped to [sessionEndMs] in the output. [contextEpochs] epochs on each side (`k = −C … −1` and `N … N+C−1`)
 * feed smoothing, feature windows and cycle detection but are never decoded.
 *
 * Arrays indexed by "position" cover all [totalEpochs]; position `i` holds epoch `k = i − C`.
 *
 * @property sessionStartMs session start, epoch milliseconds.
 * @property sessionEndMs session end (exclusive), epoch milliseconds.
 * @property epochSec epoch length in seconds.
 * @param contextMarginMin context on each side in minutes (rounded up to whole epochs).
 */
class EpochGrid(
    val sessionStartMs: Long,
    val sessionEndMs: Long,
    val epochSec: Int,
    contextMarginMin: Int,
) {
    init {
        require(epochSec > 0) { "epochSec must be positive, was $epochSec" }
        require(sessionEndMs > sessionStartMs) { "session end must be after start" }
        require(contextMarginMin >= 0) { "contextMarginMin must not be negative" }
    }

    /** Epoch length in milliseconds. */
    val epochMs: Long = epochSec * MS_PER_SECOND

    /** Epoch length in minutes. */
    val epochMin: Double = epochSec / SECONDS_PER_MINUTE

    /** Number of session epochs N (the last one may be partial). */
    val sessionEpochs: Int = ((sessionEndMs - sessionStartMs + epochMs - 1) / epochMs).toInt()

    /** Number of context epochs C on each side of the session. */
    val contextEpochs: Int = ((contextMarginMin * SECONDS_PER_MINUTE_INT + epochSec - 1) / epochSec)

    /** Number of positions (N + 2C). */
    val totalEpochs: Int = sessionEpochs + 2 * contextEpochs

    /** Start of the first context epoch. */
    val windowStartMs: Long = startMs(-contextEpochs)

    /** End (exclusive) of the last context epoch. */
    val windowEndMs: Long = startMs(sessionEpochs + contextEpochs)

    /** Position of epoch [k] in position-indexed arrays. */
    fun position(k: Int): Int = k + contextEpochs

    /** Epoch index of [position]. */
    fun epochAt(position: Int): Int = position - contextEpochs

    /** Start of epoch [k] (not clipped). */
    fun startMs(k: Int): Long = sessionStartMs + k * epochMs

    /** Midpoint of epoch [k] (not clipped). */
    fun midMs(k: Int): Long = startMs(k) + epochMs / 2

    /** End of session epoch [k], clipped to [sessionEndMs]. */
    fun clippedEndMs(k: Int): Long = minOf(startMs(k + 1), sessionEndMs)

    /** Duration of session epoch [k] inside the session, milliseconds. */
    fun clippedDurationMs(k: Int): Long = clippedEndMs(k) - startMs(k)

    /** Position of the epoch containing [timeMs], or −1 if the time is outside the grid window. */
    fun positionOf(timeMs: Long): Int {
        if (timeMs < windowStartMs || timeMs >= windowEndMs) return -1
        return Math.floorDiv(timeMs - windowStartMs, epochMs).toInt()
    }

    private companion object {
        const val MS_PER_SECOND = 1000L
        const val SECONDS_PER_MINUTE = 60.0
        const val SECONDS_PER_MINUTE_INT = 60
    }
}
