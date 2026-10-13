package io.github.alien2003.pulsestages.cli

import io.github.alien2003.pulsestages.algo.model.SleepStage
import kotlin.system.exitProcess

/** Runs the CLI and returns a process exit code. Output goes to [out]/[err] so it is testable. */
fun run(
    args: List<String>,
    out: Appendable,
    err: Appendable,
): Int =
    when (args.firstOrNull()) {
        "stages" -> {
            out.appendLine(SleepStage.SCORED.joinToString(separator = ","))
            ExitCodes.OK
        }

        null, "--help", "-h" -> {
            out.appendLine(USAGE)
            ExitCodes.OK
        }

        else -> {
            err.appendLine("Unknown command: ${args.first()}")
            err.appendLine(USAGE)
            ExitCodes.USAGE
        }
    }

private val USAGE =
    """
    Usage: pulsestages-algo <command>
    Commands:
      stages    print the sleep stage labels known to the estimator
    """.trimIndent()

fun main(args: Array<String>) {
    val code = run(args.toList(), System.out, System.err)
    if (code != ExitCodes.OK) exitProcess(code)
}
