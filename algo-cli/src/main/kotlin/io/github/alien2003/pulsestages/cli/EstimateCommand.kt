package io.github.alien2003.pulsestages.cli

import io.github.alien2003.pulsestages.algo.SleepStager
import io.github.alien2003.pulsestages.algo.export.CsvExporter
import io.github.alien2003.pulsestages.algo.hybrid.HybridModel
import io.github.alien2003.pulsestages.algo.hybrid.HybridModelException
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import java.nio.file.Path
import java.util.Locale

/**
 * `estimate --night <file> [--id <night id>] [--estimator RULES|HMM|HYBRID] [--model <bundle>] [--params <json>]
 * --csv <out.csv>`: estimates one night through SleepStager.run and writes the in-app CSV export (CsvExporter).
 */
internal object EstimateCommand {
    private const val PERCENT = 100.0

    /** Runs `estimate` with the options [args]; returns the exit code. */
    fun run(
        args: List<String>,
        out: Appendable,
        err: Appendable,
    ): Int {
        val options = Options("estimate", args, setOf("night", "id", "estimator", "model", "params", "csv"))
        val nights = NightFiles.read(options.requiredPath("night"))
        val id = options["id"]
        val record =
            when {
                id != null -> nights.firstOrNull { it.id == id } ?: throw CliException("no night with id '$id'", ExitCodes.DATA_ERROR)
                nights.size == 1 -> nights.single()
                else -> throw CliException("${nights.size} nights in the input; choose one with --id", ExitCodes.USAGE)
            }
        val params = withEstimator(ParamsFiles.load(options.path("params")), options["estimator"])
        val stager = SleepStager(options.path("model")?.let { loadModel(it) })
        val csv = options.requiredPath("csv")
        return when (val outcome = stager.run(record.input, params)) {
            is EstimationOutcome.Estimated -> {
                val estimate = outcome.estimate
                CliFiles.writeText(csv, CsvExporter.export(estimate))
                val summary =
                    String.format(
                        Locale.ROOT,
                        "night=%s estimator=%s coverage=%.1f%% confidence=%.3f warnings=%s -> %s",
                        record.id,
                        estimate.estimator,
                        estimate.coverage * PERCENT,
                        estimate.confidence,
                        estimate.warnings.map { it.code },
                        csv,
                    )
                out.appendLine(summary)
                ExitCodes.OK
            }

            is EstimationOutcome.NotEstimated -> {
                err.appendLine("night ${record.id} not estimated: ${outcome.reason} (${outcome.detail})")
                ExitCodes.NOT_ESTIMATED
            }
        }
    }

    /** [params] with the estimator named [name] (RULES, HMM or HYBRID), unchanged when [name] is null. */
    fun withEstimator(
        params: AlgoParams,
        name: String?,
    ): AlgoParams {
        if (name == null) return params
        val estimator =
            EstimatorType.entries.firstOrNull { it.name == name.uppercase(Locale.ROOT) }
                ?: throw CliException("unknown estimator '$name' (RULES, HMM or HYBRID)", ExitCodes.USAGE)
        return params.copy(estimator = estimator)
    }

    /** Parses the bundle in [path]. */
    fun loadModel(path: Path): HybridModel =
        try {
            HybridModel.parse(CliFiles.readText(path))
        } catch (e: HybridModelException) {
            throw CliException("$path: ${e.message}", ExitCodes.DATA_ERROR, e)
        }
}
