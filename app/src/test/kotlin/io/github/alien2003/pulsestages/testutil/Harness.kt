package io.github.alien2003.pulsestages.testutil

import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File

/**
 * Runs [block] with a fresh data-hc [PipelineHarness] (fake Health Connect, in-memory ledger, fake estimator) whose
 * clock is at [now], on the scheduler of [main] (so ViewModel coroutines and the harness share virtual time).
 */
fun harnessTest(
    main: MainDispatcherExtension,
    dir: File,
    now: Long = NOON,
    block: suspend TestScope.(PipelineHarness) -> Unit,
) = runTest(main.dispatcher) {
    val harness = PipelineHarness(backgroundScope, dir, StandardTestDispatcher(testScheduler), startMs = now)
    block(harness)
}
