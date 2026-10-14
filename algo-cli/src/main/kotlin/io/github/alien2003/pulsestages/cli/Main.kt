package io.github.alien2003.pulsestages.cli

import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.algo.hybrid.HybridFeatureExtractor
import io.github.alien2003.pulsestages.algo.model.SleepStage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.system.exitProcess

/** Runs the CLI and returns a process exit code. Output goes to [out]/[err] so it is testable. */
fun run(
    args: List<String>,
    out: Appendable,
    err: Appendable,
): Int {
    val rest = args.drop(1)
    return try {
        when (args.firstOrNull()) {
            "features" -> FeaturesCommand.run(rest, out)
            "evaluate" -> EvaluateCommand.run(rest, out)
            "estimate" -> EstimateCommand.run(rest, out, err)
            "synthesize" -> SynthesizeCommand.run(rest, out)
            "feature-names" -> featureNames(out)
            "stages" -> stages(out)
            null, "--help", "-h", "help" -> help(out)
            else -> unknown(args.first(), err)
        }
    } catch (e: CliException) {
        err.appendLine("error: ${e.message}")
        if (e.exitCode == ExitCodes.USAGE) err.appendLine(USAGE)
        e.exitCode
    } catch (e: IOException) {
        err.appendLine("error: ${e.message}")
        ExitCodes.IO_ERROR
    } catch (e: IllegalArgumentException) {
        // Backstop: input the checks above let through but :core-algo rejects is still a data error, not a crash.
        err.appendLine("error: ${e.message}")
        ExitCodes.DATA_ERROR
    } catch (e: IllegalStateException) {
        err.appendLine("error: ${e.message}")
        ExitCodes.DATA_ERROR
    }
}

private fun stages(out: Appendable): Int {
    out.appendLine(SleepStage.SCORED.joinToString(separator = ","))
    return ExitCodes.OK
}

private fun featureNames(out: Appendable): Int {
    val json =
        buildJsonObject {
            put("algoVersion", ALGO_VERSION)
            put("featureSetVersion", HybridFeatureExtractor.FEATURE_SET_VERSION)
            put("featureNames", JsonArray(HybridFeatureExtractor.FEATURE_NAMES.map { JsonPrimitive(it) }))
        }
    out.appendLine(json.toString())
    return ExitCodes.OK
}

private fun help(out: Appendable): Int {
    out.appendLine(USAGE)
    return ExitCodes.OK
}

private fun unknown(
    command: String,
    err: Appendable,
): Int {
    err.appendLine("Unknown command: $command")
    err.appendLine(USAGE)
    return ExitCodes.USAGE
}

private val USAGE =
    """
    Usage: algo-cli <command> [options]   (formats: docs/INTERCHANGE.md)
    Commands:
      features      --nights <dir|file> --out <features.csv> [--params <json>] [--nap-proxies <minutes,...>]
                    per-epoch hybrid features + labels for training (with the nap proxies' rows)
      evaluate      --nights <dir|file> [--estimator RULES|HMM|HYBRID] [--params <json>] [--param-grid <json array>]
                    [--model <bundle.json> | --fold-models <dir with <subject>.json>] [--nap-proxies <minutes,...>]
                    --out <metrics.json> [--per-epoch <predictions.csv>]
                    score every night (and nap proxy) against its labels; one metrics block per grid entry,
                    split by mode (byMode: NIGHT / NAP); a bundle's nap section decodes the naps
      estimate      --night <file> [--id <night id>] [--estimator ...] [--model <bundle.json>] [--params <json>]
                    --csv <out.csv>
                    estimate one night and write the in-app CSV export
      synthesize    --out <dir> [--count N] [--seed S] [--subjects K]
                    write labelled synthetic nights (pipeline smoke tests only)
      feature-names print FEATURE_SET_VERSION and FEATURE_NAMES as JSON
      stages        print the sleep stage labels known to the estimator
    Exit codes: 0 ok, 2 not estimated, 64 usage, 65 bad input data, 66 missing input, 74 output error.
    """.trimIndent()

/** Entry point of the `algo-cli` launcher. */
fun main(args: Array<String>) {
    val code = run(args.toList(), System.out, System.err)
    if (code != ExitCodes.OK) exitProcess(code)
}
