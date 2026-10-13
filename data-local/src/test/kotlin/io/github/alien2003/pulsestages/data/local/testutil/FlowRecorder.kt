package io.github.alien2003.pulsestages.data.local.testutil

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Collects [flow] in [scope] so a test can wait for specific emissions. Room may emit the same value more than
 * once, so tests wait for a matching value instead of counting emissions; because the first value is taken before
 * the test writes, a later match proves that the flow reacted to the write.
 */
class FlowRecorder<T>(
    scope: CoroutineScope,
    flow: Flow<T>,
) {
    private val values = Channel<T>(Channel.UNLIMITED)
    private val job = scope.launch { flow.collect { values.send(it) } }

    /** The next emission. */
    suspend fun next(): T = values.receive()

    /** Skips emissions until one matches [predicate] and returns it. */
    suspend fun awaitMatching(predicate: (T) -> Boolean): T {
        while (true) {
            val value = values.receive()
            if (predicate(value)) return value
        }
    }

    /** Stops collecting. */
    fun cancel() {
        job.cancel()
    }
}
