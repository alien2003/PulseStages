package io.github.alien2003.pulsestages.e2e.support

import io.github.alien2003.pulsestages.e2e.E2e
import kotlinx.serialization.Serializable
import org.junit.AssumptionViolatedException
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Outcome of one test class as the runner summarises it (`e2e/status/<class>.json`).
 *
 * @property test the test (class#method).
 * @property runId id of the run.
 * @property result PASSED, FAILED or SKIPPED.
 * @property message the failure or skip message, if any.
 * @property finishedAtMs when it finished, epoch ms.
 */
@Serializable
data class TestStatus(
    val test: String,
    val runId: String,
    val result: String,
    val message: String?,
    val finishedAtMs: Long,
)

/**
 * Writes the outcome of every test to `e2e/status/<class>.json` (the runner prints them in its summary), and fails
 * every test before its body runs unless this process's WorkManager is the suite's in-memory one ([E2eWorkManager]).
 */
class E2eStatusRule : TestWatcher() {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        super.apply(
            object : Statement() {
                override fun evaluate() {
                    E2eWorkManager.requireInstalled()
                    base.evaluate()
                }
            },
            description,
        )

    override fun succeeded(description: Description) = write(description, "PASSED", null)

    override fun failed(
        e: Throwable,
        description: Description,
    ) = write(description, "FAILED", "${e.javaClass.simpleName}: ${e.message}")

    override fun skipped(
        e: AssumptionViolatedException,
        description: Description,
    ) = write(description, "SKIPPED", e.message)

    private fun write(
        description: Description,
        result: String,
        message: String?,
    ) {
        val name = description.testClass?.simpleName ?: description.className
        val status =
            TestStatus("$name#${description.methodName}", E2e.runId, result, message?.take(MAX_MESSAGE), System.currentTimeMillis())
        E2e.writeJson("status/$name.json", status)
        E2e.progress("$name: $result${message?.let { " ($it)" }.orEmpty()}")
    }

    private companion object {
        const val MAX_MESSAGE = 4000
    }
}
