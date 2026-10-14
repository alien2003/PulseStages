package io.github.alien2003.pulsestages.cli

import io.github.alien2003.pulsestages.algo.SleepStager
import io.github.alien2003.pulsestages.algo.metrics.EpochLabels
import io.github.alien2003.pulsestages.algo.metrics.IntervalScoring
import io.github.alien2003.pulsestages.algo.metrics.NightScore
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.algo.night.NightPreparer
import io.github.alien2003.pulsestages.algo.night.PreparedNight
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.FeatureParams
import io.github.alien2003.pulsestages.algo.params.PreprocessParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** Outcome of one night under one parameter set. */
internal sealed interface NightResult {
    /** The night. */
    val record: NightRecord

    /** Night or nap under the parameter set. */
    val mode: SessionMode

    /** Not scored, with a reason (NO_LABELS, NO_FOLD_MODEL, or a NotEstimatedReason). */
    class Skipped(
        override val record: NightRecord,
        override val mode: SessionMode,
        val reason: String,
    ) : NightResult

    /** Scored against the night's labels. */
    class Scored(
        override val record: NightRecord,
        override val mode: SessionMode,
        val estimator: EstimatorType,
        val coverage: Double,
        val confidence: Double,
        val warnings: List<WarningCode>,
        val score: NightScore,
    ) : NightResult
}

/**
 * `evaluate --nights <dir|file> [--estimator RULES|HMM|HYBRID] [--params <json>] [--param-grid <json array>]
 * [--model <bundle> | --fold-models <dir with <subject>.json>] [--nap-proxies <minutes,...>] --out <metrics.json>
 * [--per-epoch <csv>]`.
 *
 * Every night is estimated through SleepStager.run (with its subject's fold model when --fold-models is given; a bundle
 * with a nap section decodes the naps with it) and scored against its labels with IntervalScoring (each label interval
 * split into 30-s chunks, compared with the predicted stage at the chunk midpoint). `--estimator` sets the estimator of
 * nights and naps. `--nap-proxies 30,60,90,120` also evaluates the first L minutes of every night as a nap proxy
 * ([NapProxies], id `<night id>-nap<L>`, same subject and fold model). Every metrics block also reports its numbers
 * per mode (`byMode`: NIGHT / NAP, by `napMode.maxSessionMin` of the block's parameters). With --param-grid, every
 * grid entry (a partial AlgoParams patch merged over the base parameters) produces one metrics block; nights are
 * parsed once and their preparation (preprocessing, features, hybrid features and classifier outputs) is shared by all
 * entries with the same preprocessing and feature parameters (and mode). Nights are processed in parallel; the output
 * does not depend on the scheduling.
 */
internal object EvaluateCommand {
    private const val CHUNK = 64

    /** Columns of the --per-epoch CSV. */
    val PER_EPOCH_HEADER: String =
        listOf(
            "grid_index",
            "night_id",
            "subject",
            "epoch",
            "start_ms",
            "has_data",
            "label",
            "stage",
            "p_awake",
            "p_light",
            "p_deep",
            "p_rem",
        ).joinToString(",")

    private val PRETTY = Json { prettyPrint = true }

    /** Runs `evaluate` with the options [args]; returns the exit code. */
    fun run(
        args: List<String>,
        out: Appendable,
    ): Int {
        val options =
            Options(
                "evaluate",
                args,
                setOf("nights", "estimator", "params", "param-grid", "model", "fold-models", "nap-proxies", "out", "per-epoch"),
            )
        val nights = NightFiles.read(options.requiredPath("nights"))
        val records = options["nap-proxies"]?.let { NapProxies.withProxies(nights, NapProxies.parseLengths(it)) } ?: nights
        val base = EstimateCommand.withEstimator(ParamsFiles.load(options.path("params")), options["estimator"])
        val patches = options.path("param-grid")?.let { path -> ParamsFiles.grid(path) }
        val grid = patches?.mapIndexed { i, patch -> ParamsFiles.merge(base, patch, "grid entry $i") } ?: listOf(base)
        val stagers = stagers(options, records)
        val target = options.requiredPath("out")
        val started = System.nanoTime()
        val results = evaluate(records, grid, stagers, options.path("per-epoch"))
        val blocks =
            grid.indices.map { g ->
                MetricsJson.block(results.map { it[g] }, grid[g], patches?.let { g to it[g] })
            }
        val document: JsonElement = if (patches == null) blocks.single() else buildJsonObject { put("grid", JsonArray(blocks)) }
        CliFiles.writeText(target, PRETTY.encodeToString(JsonElement.serializer(), document) + "\n")
        val seconds = (System.nanoTime() - started) / NANOS_PER_SECOND
        out.appendLine(
            "evaluate: ${records.size} nights × ${grid.size} parameter sets in ${"%.1f".format(Locale.ROOT, seconds)} s -> $target",
        )
        return ExitCodes.OK
    }

    private const val NANOS_PER_SECOND = 1e9

    /** The stager for each subject (null: the subject's fold model is missing). */
    private fun stagers(
        options: Options,
        records: List<NightRecord>,
    ): (NightRecord) -> SleepStager? {
        val model = options.path("model")
        val folds = options.path("fold-models")
        if (model != null && folds != null) throw CliException("--model and --fold-models are mutually exclusive", ExitCodes.USAGE)
        if (folds != null) {
            if (!Files.isDirectory(folds)) throw CliException("--fold-models: $folds is not a directory", ExitCodes.NO_INPUT)
            val bySubject =
                records.map { it.subject }.distinct().associateWith { subject ->
                    val file = folds.resolve("$subject.json")
                    if (Files.isRegularFile(file)) SleepStager(EstimateCommand.loadModel(file)) else null
                }
            return { record -> bySubject[record.subject] }
        }
        val stager = SleepStager(model?.let { EstimateCommand.loadModel(it) })
        return { stager }
    }

    /** Results `[night][grid entry]`, writing the per-epoch CSV (if requested) in night order. */
    private fun evaluate(
        records: List<NightRecord>,
        grid: List<AlgoParams>,
        stagers: (NightRecord) -> SleepStager?,
        perEpoch: Path?,
    ): List<List<NightResult>> {
        val writer = perEpoch?.let { CliFiles.writer(it) }
        val results = ArrayList<List<NightResult>>(records.size)
        writer.use { csv ->
            csv?.write(PER_EPOCH_HEADER + "\n")
            runBlocking {
                for (chunk in records.chunked(CHUNK)) {
                    val evaluated =
                        chunk.map { record ->
                            async(Dispatchers.Default) {
                                evaluateNight(
                                    record,
                                    grid,
                                    stagers(record),
                                    csv != null,
                                )
                            }
                        }
                    for (night in evaluated.awaitAll()) {
                        results += night.results
                        csv?.write(night.perEpochRows)
                    }
                }
            }
        }
        return results
    }

    private class NightEvaluation(
        val results: List<NightResult>,
        val perEpochRows: String,
    )

    private fun evaluateNight(
        record: NightRecord,
        grid: List<AlgoParams>,
        stager: SleepStager?,
        perEpoch: Boolean,
    ): NightEvaluation {
        val skipReason =
            when {
                record.labels.isEmpty() -> "NO_LABELS"
                stager == null -> "NO_FOLD_MODEL"
                else -> null
            }
        val durationMs = record.input.sessionEndMs - record.input.sessionStartMs
        if (skipReason != null || stager == null) {
            return NightEvaluation(grid.map { NightResult.Skipped(record, it.modeOf(durationMs), skipReason.orEmpty()) }, "")
        }
        val prepared = HashMap<Pair<PreprocessParams, FeatureParams>, PreparedNight>()
        val rows = StringBuilder()
        val results =
            grid.mapIndexed { g, params ->
                val night =
                    prepared
                        .getOrPut(
                            params.preprocess to params.features,
                        ) { NightPreparer.prepare(record.input, params) }
                        .withParams(params)
                when (val outcome = stager.run(night)) {
                    is EstimationOutcome.NotEstimated -> {
                        NightResult.Skipped(record, night.mode, outcome.reason.name)
                    }

                    is EstimationOutcome.Estimated -> {
                        val estimate = outcome.estimate
                        if (perEpoch) appendRows(rows, g, record, night, estimate)
                        NightResult.Scored(
                            record = record,
                            mode = estimate.mode,
                            estimator = estimate.estimator,
                            coverage = estimate.coverage,
                            confidence = estimate.confidence,
                            warnings = estimate.warnings.map { it.code }.distinct(),
                            score = IntervalScoring.score(record.labels, estimate.stages),
                        )
                    }
                }
            }
        return NightEvaluation(results, rows.toString())
    }

    private fun appendRows(
        out: StringBuilder,
        gridIndex: Int,
        record: NightRecord,
        night: PreparedNight,
        estimate: NightEstimate,
    ) {
        val labels = EpochLabels.majority(record.labels, night.grid)
        val id = Csv.field(record.id)
        val subject = Csv.field(record.subject)
        for (epoch in estimate.perEpoch) {
            out
                .append(gridIndex)
                .append(',')
                .append(id)
                .append(',')
                .append(subject)
                .append(',')
            out
                .append(epoch.index)
                .append(',')
                .append(epoch.startMs)
                .append(',')
                .append(if (epoch.hasData) "1" else "0")
                .append(',')
            out.append(labels[epoch.index]?.name.orEmpty()).append(',').append(epoch.stage.name)
            for (p in epoch.posterior.toList()) out.append(',').append(Csv.number(p))
            out.append('\n')
        }
    }
}
