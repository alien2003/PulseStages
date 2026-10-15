package io.github.alien2003.pulsestages.cli

import io.github.alien2003.pulsestages.algo.hybrid.HybridFeatureExtractor
import io.github.alien2003.pulsestages.algo.metrics.EpochLabels
import io.github.alien2003.pulsestages.algo.night.NightPreparer

/**
 * `features --nights <dir|file> --out <features.csv> [--params <json>] [--nap-proxies <minutes,...>]`: one row per
 * session epoch with night_id, subject, epoch, start_ms, has_data, label (majority reference stage, see EpochLabels;
 * empty if none), the hybrid features in [HybridFeatureExtractor.FEATURE_NAMES] order, then the HMM base features not
 * already among them: cyclePhase (empty where undefined) and steps (hrNorm, hrVar, hrSlope and t are the identical
 * hybrid columns). `--nap-proxies` adds the rows of the nap proxies of every night ([NapProxies], night id
 * `<night id>-nap<L>`), the training rows of a bundle's nap model; the features are the same for nights and naps (a nap
 * model must declare the same feature set). A nap's rows are computed as the estimator computes them
 * (`NightPreparer.prepare`): with `napMode.contextMarginMin` set, from its input restricted to that margin on both sides
 * of the session and on a grid with that much context.
 */
internal object FeaturesCommand {
    /** Columns before the features. */
    val LEADING: List<String> = listOf("night_id", "subject", "epoch", "start_ms", "has_data", "label")

    /** HMM base feature columns appended after the hybrid features. */
    val BASE: List<String> = listOf("cyclePhase", "steps")

    /** The header row. */
    val HEADER: String = (LEADING + HybridFeatureExtractor.FEATURE_NAMES + BASE).joinToString(",")

    /** Runs `features` with the options [args]; returns the exit code. */
    fun run(
        args: List<String>,
        out: Appendable,
    ): Int {
        val options = Options("features", args, setOf("nights", "out", "params", "nap-proxies"))
        val read = NightFiles.read(options.requiredPath("nights"))
        val nights = options["nap-proxies"]?.let { NapProxies.withProxies(read, NapProxies.parseLengths(it)) } ?: read
        val params = ParamsFiles.load(options.path("params"))
        val target = options.requiredPath("out")
        var rows = 0
        CliFiles.writer(target).use { writer ->
            writer.write(HEADER)
            writer.write("\n")
            for (record in nights) {
                val night = NightPreparer.prepare(record.input, params)
                val features = HybridFeatureExtractor.extract(night)
                val labels = EpochLabels.majority(record.labels, night.grid)
                for (k in features.indices) {
                    val p = night.grid.position(k)
                    val fields =
                        listOf(
                            Csv.field(record.id),
                            Csv.field(record.subject),
                            k.toString(),
                            night.grid.startMs(k).toString(),
                            if (night.sessionHasData(k)) "1" else "0",
                            labels[k]?.name.orEmpty(),
                        ) + features[k].map { Csv.number(it) } +
                            Csv.number(night.features.cycle.phase[p]) +
                            Csv.number(night.features.activity.steps[p])
                    writer.write(fields.joinToString(","))
                    writer.write("\n")
                    rows++
                }
            }
        }
        out.appendLine("features: ${nights.size} nights, $rows epochs -> $target")
        return ExitCodes.OK
    }
}
