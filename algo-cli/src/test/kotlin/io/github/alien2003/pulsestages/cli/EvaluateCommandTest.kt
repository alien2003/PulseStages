package io.github.alien2003.pulsestages.cli

import io.github.alien2003.pulsestages.algo.SleepStager
import io.github.alien2003.pulsestages.algo.metrics.ConfusionMatrix
import io.github.alien2003.pulsestages.algo.metrics.EpochLabels
import io.github.alien2003.pulsestages.algo.metrics.IntervalScoring
import io.github.alien2003.pulsestages.algo.metrics.NightScore
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.night.NightPreparer
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.DurationModel
import io.github.alien2003.pulsestages.algo.params.HmmParams
import io.github.alien2003.pulsestages.algo.params.HybridParams
import io.github.alien2003.pulsestages.algo.testing.MinimalBundles
import io.github.alien2003.pulsestages.algo.testing.NapModels
import io.github.alien2003.pulsestages.algo.testing.SyntheticHybridModels
import io.github.alien2003.pulsestages.cli.CliTestSupport.cli
import io.github.alien2003.pulsestages.cli.CliTestSupport.ok
import io.github.alien2003.pulsestages.cli.CliTestSupport.readJson
import io.github.alien2003.pulsestages.cli.CliTestSupport.record
import io.github.alien2003.pulsestages.cli.CliTestSupport.writeModel
import io.github.alien2003.pulsestages.cli.CliTestSupport.writeNights
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class EvaluateCommandTest {
    @TempDir
    lateinit var dir: Path

    private val records = (0 until 6).map { record(5001L + it, subject = "s${it % 3}", index = it) }

    private fun JsonObject.obj(key: String): JsonObject = checkNotNull(this[key]).jsonObject

    private fun JsonObject.num(key: String): Double = checkNotNull(this[key]).jsonPrimitive.double

    private fun nightsDir(extra: List<NightRecord> = emptyList()): Path = writeNights(dir.resolve("nights"), records + extra)

    /**
     * Parameters of the threshold checks below: the specification's HMM and the pre-training hybrid decoder values,
     * with which the thresholds were set (the shipped defaults are chosen on real nights for the real bundle).
     */
    private val pinned =
        AlgoParams(
            hmm = HmmParams.PHYSIOLOGY,
            hybrid = HybridParams(durationModel = DurationModel.EXPLICIT, timePriorWeight = 1.0, emissionWeight = 1.0),
        )

    private fun pinnedFile(): Path =
        Files.writeString(dir.resolve("pinned-params.json"), ParamsFiles.JSON.encodeToString(AlgoParams.serializer(), pinned))

    @Test
    fun `evaluates labelled nights with sane metrics and counts skipped nights`() {
        val unlabelled = record(5100, subject = "s9").copy(id = "unlabelled", labels = emptyList())
        val out = dir.resolve("hmm.json")

        ok(
            "evaluate",
            "--nights",
            nightsDir(listOf(unlabelled)).toString(),
            "--estimator",
            "HMM",
            "--params",
            pinnedFile().toString(),
            "--out",
            out.toString(),
        )

        val metrics = readJson(out)
        assertEquals("HMM", checkNotNull(metrics["estimator"]).jsonPrimitive.content)
        val nights = metrics.obj("nights")
        assertEquals(7, checkNotNull(nights["total"]).jsonPrimitive.int)
        assertEquals(6, checkNotNull(nights["evaluated"]).jsonPrimitive.int)
        assertEquals(1, checkNotNull(nights.obj("skipReasons")["NO_LABELS"]).jsonPrimitive.int)
        assertEquals(6, checkNotNull(metrics["perNight"]).jsonArray.size)
        val pooled = metrics.obj("pooled")
        assertTrue(pooled.num("kappa") > 0.3, "HMM kappa on synthetic nights: ${pooled.num("kappa")}")
        assertEquals(4, checkNotNull(pooled["confusion"]).jsonArray.size)
        // The per-night matrices add up to the pooled one (training pools results of several runs from them).
        val matrices = checkNotNull(metrics["perNight"]).jsonArray.map { night -> longMatrix(night.jsonObject, "confusion") }
        val summed = matrices.reduce { a, b -> a.zip(b) { ra, rb -> ra.zip(rb) { x, y -> x + y } } }
        assertEquals(longMatrix(pooled, "confusion"), summed)

        // Pooled numbers equal the library's scoring of the same nights.
        val stager = SleepStager()
        val expected =
            records.fold(ConfusionMatrix.EMPTY) { acc, r ->
                val estimate = (stager.run(r.input, pinned.copy(estimator = EstimatorType.HMM)) as EstimationOutcome.Estimated).estimate
                acc + IntervalScoring.score(r.labels, estimate.stages).confusion
            }
        assertEquals(expected.kappa, pooled.num("kappa"), 1e-12)
        assertEquals(3, checkNotNull(metrics.obj("perSubject")["subjects"]).jsonPrimitive.int)
        assertTrue(metrics.obj("minutesError").num("deep") >= 0.0)
    }

    /** The 4×4 integer matrix under [key]. */
    private fun longMatrix(
        json: JsonObject,
        key: String,
    ): List<List<Long>> = checkNotNull(json[key]).jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.long } }

    /** A JSON number or null (undefined) as a Double (NaN for null). */
    private fun JsonObject.numOrNaN(key: String): Double = checkNotNull(this[key]).jsonPrimitive.doubleOrNull ?: Double.NaN

    private fun assertNumber(
        expected: Double,
        actual: Double,
        what: String,
    ) {
        if (expected.isFinite()) {
            assertEquals(
                expected,
                actual,
                1e-12,
                what,
            )
        } else {
            assertTrue(actual.isNaN(), "$what: expected null, got $actual")
        }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    @Test
    fun `every metric and the per-epoch CSV equal the library's results field by field`() {
        val params = AlgoParams(estimator = EstimatorType.HMM)
        val out = dir.resolve("metrics.json")
        val perEpoch = dir.resolve("per-epoch.csv")
        ok(
            "evaluate",
            "--nights",
            nightsDir().toString(),
            "--estimator",
            "HMM",
            "--out",
            out.toString(),
            "--per-epoch",
            perEpoch.toString(),
        )
        val metrics = readJson(out)

        val stager = SleepStager()
        val estimates = records.map { (stager.run(it.input, params) as EstimationOutcome.Estimated).estimate }
        val scores = records.zip(estimates).map { (r, e) -> IntervalScoring.score(r.labels, e.stages) }

        assertPerNight(checkNotNull(metrics["perNight"]).jsonArray.map { it.jsonObject }, estimates, scores)
        assertPooled(metrics.obj("pooled"), scores.fold(ConfusionMatrix.EMPTY) { acc, score -> acc + score.confusion })
        assertAggregates(metrics, scores)
        assertPerEpoch(Files.readAllLines(perEpoch).drop(1).map { it.split(',') }, params, estimates)
    }

    private fun assertPerNight(
        perNight: List<JsonObject>,
        estimates: List<NightEstimate>,
        scores: List<NightScore>,
    ) {
        assertEquals(records.map { it.id }, perNight.map { checkNotNull(it["id"]).jsonPrimitive.content })
        perNight.forEachIndexed { i, night ->
            val score = scores[i]
            val what = records[i].id
            assertEquals(records[i].subject, checkNotNull(night["subject"]).jsonPrimitive.content)
            assertNumber(score.confusion.kappa, night.numOrNaN("kappa"), "$what kappa")
            assertNumber(score.confusion.accuracy, night.numOrNaN("accuracy"), "$what accuracy")
            assertNumber(score.confusion.macroF1, night.numOrNaN("macroF1"), "$what macroF1")
            assertNumber(estimates[i].coverage, night.num("coverage"), "$what coverage")
            assertNumber(estimates[i].confidence, night.num("confidence"), "$what confidence")
            assertEquals(score.confusion.total, checkNotNull(night["scoredChunks"]).jsonPrimitive.long)
            assertEquals(score.confusion.toArray().map { it.toList() }, longMatrix(night, "confusion"), "$what confusion")
            for (stage in SleepStage.SCORED) {
                assertNumber(score.truthMinutes[stage], night.obj("truthMinutes").num(stage.name), "$what truth $stage")
                assertNumber(score.predictedMinutes[stage], night.obj("predictedMinutes").num(stage.name), "$what predicted $stage")
            }
            val errors = night.obj("minutesError")
            assertNumber(score.minutesError.deep, errors.num("deep"), "$what deep error")
            assertNumber(score.minutesError.rem, errors.num("rem"), "$what rem error")
            assertNumber(score.minutesError.awake, errors.num("awake"), "$what awake error")
        }
    }

    private fun assertPooled(
        json: JsonObject,
        pooled: ConfusionMatrix,
    ) {
        assertEquals(
            pooled.toArray().map { it.toList() },
            checkNotNull(json["confusion"]).jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.long } },
        )
        assertNumber(pooled.kappa, json.num("kappa"), "pooled kappa")
        assertNumber(pooled.accuracy, json.num("accuracy"), "pooled accuracy")
        assertNumber(pooled.macroF1, json.num("macroF1"), "pooled macroF1")
        for (stage in SleepStage.SCORED) {
            val expected = pooled.stage(stage)
            val actual = json.obj("perStage").obj(stage.name)
            assertNumber(expected.precision, actual.num("precision"), "$stage precision")
            assertNumber(expected.recall, actual.num("recall"), "$stage recall")
            assertNumber(expected.f1, actual.num("f1"), "$stage f1")
            assertEquals(expected.support, checkNotNull(actual["support"]).jsonPrimitive.long, "$stage support")
            assertEquals(expected.predicted, checkNotNull(actual["predicted"]).jsonPrimitive.long, "$stage predicted")
        }
        assertTrue(SleepStage.SCORED.any { pooled.stage(it).precision != pooled.stage(it).recall }, "precision and recall distinguishable")
    }

    /** Night and subject kappa aggregates and the mean minute errors. */
    private fun assertAggregates(
        metrics: JsonObject,
        scores: List<NightScore>,
    ) {
        val nightKappas = scores.map { it.confusion.kappa }.filter { it.isFinite() }
        assertNumber(nightKappas.average(), metrics.num("meanNightKappa"), "meanNightKappa")
        assertNumber(median(nightKappas), metrics.num("medianNightKappa"), "medianNightKappa")
        assertTrue(median(nightKappas) != nightKappas.average(), "median and mean distinguishable")
        val bySubject = records.indices.groupBy { records[it].subject }.toSortedMap()
        val subjectJson = metrics.obj("perSubject")
        val values = checkNotNull(subjectJson["values"]).jsonArray.map { it.jsonObject }
        assertEquals(bySubject.keys.toList(), values.map { checkNotNull(it["subject"]).jsonPrimitive.content })
        val subjectKappas =
            bySubject.values.mapIndexed { j, indices ->
                val matrix = indices.fold(ConfusionMatrix.EMPTY) { acc, i -> acc + scores[i].confusion }
                assertEquals(indices.size, checkNotNull(values[j]["nights"]).jsonPrimitive.int)
                assertNumber(matrix.kappa, values[j].num("kappa"), "subject ${values[j]["subject"]} kappa")
                assertNumber(matrix.accuracy, values[j].num("accuracy"), "subject ${values[j]["subject"]} accuracy")
                matrix.kappa
            }
        assertNumber(subjectKappas.average(), subjectJson.num("meanKappa"), "per-subject mean kappa")
        assertNumber(median(subjectKappas), subjectJson.num("medianKappa"), "per-subject median kappa")
        val minutes = metrics.obj("minutesError")
        assertNumber(scores.map { it.minutesError.deep }.average(), minutes.num("deep"), "mean deep error")
        assertNumber(scores.map { it.minutesError.rem }.average(), minutes.num("rem"), "mean rem error")
        assertNumber(scores.map { it.minutesError.awake }.average(), minutes.num("awake"), "mean awake error")
        assertTrue(setOf(minutes.num("deep"), minutes.num("rem"), minutes.num("awake")).size == 3, "minute errors distinguishable")
    }

    private fun assertPerEpoch(
        rows: List<List<String>>,
        params: AlgoParams,
        estimates: List<NightEstimate>,
    ) {
        var row = 0
        records.forEachIndexed { i, record ->
            val labels = EpochLabels.majority(record.labels, NightPreparer.prepare(record.input, params).grid)
            for (epoch in estimates[i].perEpoch) {
                val expected =
                    listOf(
                        "0",
                        record.id,
                        record.subject,
                        epoch.index.toString(),
                        epoch.startMs.toString(),
                        if (epoch.hasData) "1" else "0",
                        labels[epoch.index]?.name.orEmpty(),
                        epoch.stage.name,
                    ) + epoch.posterior.toList().map { Csv.number(it) }
                assertEquals(expected, rows[row++], "${record.id} epoch ${epoch.index}")
            }
        }
        assertEquals(rows.size, row)
        assertTrue(rows.any { it[6] != it[7] }, "label and stage columns distinguishable")
    }

    @Test
    fun `hybrid evaluation with a model and with fold models`() {
        val model = writeModel(dir.resolve("model.json"))
        val out = dir.resolve("hybrid.json")
        val params = pinnedFile().toString()
        ok(
            "evaluate",
            "--nights",
            nightsDir().toString(),
            "--estimator",
            "HYBRID",
            "--model",
            model.toString(),
            "--params",
            params,
            "--out",
            out.toString(),
        )

        val metrics = readJson(out)
        assertTrue(metrics.obj("pooled").num("kappa") > 0.6, "hybrid kappa: ${metrics.obj("pooled").num("kappa")}")
        assertEquals(6, checkNotNull(metrics.obj("estimatorsUsed")["HYBRID"]).jsonPrimitive.int)

        val folds = Files.createDirectories(dir.resolve("folds"))
        writeModel(folds.resolve("s0.json"))
        writeModel(folds.resolve("s1.json"))
        val foldOut = dir.resolve("folds.json")
        ok("evaluate", "--nights", nightsDir().toString(), "--fold-models", folds.toString(), "--out", foldOut.toString())

        val foldMetrics = readJson(foldOut)
        assertEquals(2, checkNotNull(foldMetrics.obj("nights").obj("skipReasons")["NO_FOLD_MODEL"]).jsonPrimitive.int)
        assertEquals(4, checkNotNull(foldMetrics.obj("estimatorsUsed")["HYBRID"]).jsonPrimitive.int)
    }

    @Test
    fun `a parameter grid gives one block per entry in one run`() {
        val model = writeModel(dir.resolve("model.json"))
        val grid = dir.resolve("grid.json")
        Files.writeString(
            grid,
            """[{}, {"hybrid": {"durationModel": "GEOMETRIC"}}, {"post": {"minDeepMin": 15.0}, "hybrid": {"timePriorWeight": 0.0}},
               {"estimator": "RULES"}]""",
        )
        val out = dir.resolve("grid-out.json")
        val perEpoch = dir.resolve("per-epoch.csv")
        val nights = nightsDir().toString()
        val params = pinnedFile().toString()

        ok(
            "evaluate",
            "--nights",
            nights,
            "--model",
            model.toString(),
            "--params",
            params,
            "--param-grid",
            grid.toString(),
            "--out",
            out.toString(),
            "--per-epoch",
            perEpoch.toString(),
        )

        val blocks = checkNotNull(readJson(out)["grid"]).jsonArray.map { it.jsonObject }
        assertEquals(4, blocks.size)
        blocks.forEachIndexed { i, block -> assertEquals(i, checkNotNull(block["index"]).jsonPrimitive.int) }
        assertEquals("GEOMETRIC", checkNotNull(blocks[1].obj("params").obj("hybrid")["durationModel"]).jsonPrimitive.content)
        assertEquals(15.0, blocks[2].obj("params").obj("post").num("minDeepMin"))
        assertEquals("RULES", checkNotNull(blocks[3]["estimator"]).jsonPrimitive.content)
        assertEquals(6, checkNotNull(blocks[3].obj("estimatorsUsed")["RULES"]).jsonPrimitive.int)

        val single = dir.resolve("single.json")
        ok("evaluate", "--nights", nights, "--model", model.toString(), "--params", params, "--out", single.toString())
        assertEquals(readJson(single).obj("pooled"), blocks[0].obj("pooled"), "grid entry {} equals the plain run")
        assertTrue(blocks.drop(1).take(2).all { it.obj("pooled") != blocks[0].obj("pooled") })

        val lines = Files.readAllLines(perEpoch)
        assertEquals(EvaluateCommand.PER_EPOCH_HEADER, lines.first())
        val epochs = records.sumOf { ((it.input.sessionEndMs - it.input.sessionStartMs + 59_999) / 60_000).toInt() }
        assertEquals(4 * epochs, lines.size - 1)
        assertEquals(setOf("0", "1", "2", "3"), lines.drop(1).map { it.substringBefore(',') }.toSet())
    }

    private fun lightPredicted(metrics: JsonObject): Double {
        val perStage = metrics.obj("pooled").obj("perStage")
        return perStage.obj("LIGHT").num("predicted")
    }

    @Test
    fun `the prior exponent is a grid parameter and the bundle's stage log-bias is applied`() {
        val model = writeModel(dir.resolve("model.json"))
        val grid = dir.resolve("beta-grid.json")
        Files.writeString(grid, """[{}, {"hybrid": {"priorExponent": 1.0}}, {"hybrid": {"priorExponent": 0.0}}]""")
        val nights = nightsDir().toString()
        val params = pinnedFile().toString()
        val out = dir.resolve("beta-grid-out.json")
        val gridArgs = arrayOf("--param-grid", grid.toString(), "--out", out.toString())
        ok("evaluate", "--nights", nights, "--model", model.toString(), "--params", params, *gridArgs)
        val blocks = checkNotNull(readJson(out)["grid"]).jsonArray.map { it.jsonObject }
        assertEquals(1.0, blocks[0].obj("params").obj("hybrid").num("priorExponent"), "the default is 1")
        assertEquals(blocks[0].obj("pooled"), blocks[1].obj("pooled"), "priorExponent 1 is today's decoder")
        assertTrue(blocks[2].obj("pooled") != blocks[0].obj("pooled"), "priorExponent 0 changes the decode")

        val biased = dir.resolve("biased.json")
        Files.writeString(biased, SyntheticHybridModels.default.copy(stageLogBias = listOf(0.0, 3.0, 0.0, 0.0)).toJson())
        val biasedOut = dir.resolve("biased-out.json")
        ok("evaluate", "--nights", nights, "--model", biased.toString(), "--params", params, "--out", biasedOut.toString())
        assertTrue(lightPredicted(readJson(biasedOut)) > lightPredicted(blocks[0]), "a LIGHT log-bias of +3 predicts more LIGHT")
    }

    @Test
    fun `the short-session log-bias applies only to naps, sessions shorter than napMode maxSessionMin`() {
        val biased = dir.resolve("short-biased.json")
        Files.writeString(biased, SyntheticHybridModels.default.copy(stageLogBiasShort = listOf(0.0, 3.0, 0.0, 0.0)).toJson())
        assertTrue(records.all { it.input.sessionEndMs - it.input.sessionStartMs > 180 * 60_000L }, "whole nights")
        val grid = dir.resolve("short-grid.json")
        Files.writeString(grid, """[{}, {"napMode": {"maxSessionMin": 0.0}}, {"napMode": {"maxSessionMin": 720.0}}]""")
        val out = dir.resolve("short-grid-out.json")
        val input = arrayOf("--nights", nightsDir().toString(), "--model", biased.toString(), "--params", pinnedFile().toString())
        ok("evaluate", *input, "--param-grid", grid.toString(), "--out", out.toString())
        val blocks = checkNotNull(readJson(out)["grid"]).jsonArray.map { it.jsonObject }
        assertEquals(180.0, blocks[0].obj("params").obj("napMode").num("maxSessionMin"), "the default is 180 min")
        assertEquals(blocks[1].obj("pooled"), blocks[0].obj("pooled"), "nights are longer than the default threshold")
        assertTrue(lightPredicted(blocks[2]) > lightPredicted(blocks[0]), "every session a nap: the LIGHT bias of +3 applies")
        assertEquals(
            6,
            blocks[2]
                .obj("byMode")
                .obj("NAP")
                .obj("nights")
                .int("evaluated"),
        )
        assertEquals(
            6,
            blocks[0]
                .obj("byMode")
                .obj("NIGHT")
                .obj("nights")
                .int("evaluated"),
        )
    }

    private fun JsonObject.int(key: String): Int = checkNotNull(this[key]).jsonPrimitive.int

    @Test
    fun `nap proxies are evaluated with the bundle's nap section and the metrics are split by mode`() {
        val napSection = NapModels.from(MinimalBundles.logreg(), stageLogBias = listOf(0.0, 1.0, 0.0, -1.0))
        val bundle = SyntheticHybridModels.default.copy(nap = napSection)
        val model = Files.writeString(dir.resolve("nap-model.json"), bundle.toJson())
        val out = dir.resolve("naps.json")
        val perEpoch = dir.resolve("naps.csv")
        val args =
            arrayOf("--model", model.toString(), "--nap-proxies", "30,90", "--out", out.toString(), "--per-epoch", perEpoch.toString())
        ok("evaluate", "--nights", nightsDir().toString(), *args)

        val metrics = readJson(out)
        assertEquals(18, metrics.obj("nights").int("total"))
        val byMode = metrics.obj("byMode")
        assertEquals(6, byMode.obj("NIGHT").obj("nights").int("evaluated"))
        assertEquals(12, byMode.obj("NAP").obj("nights").int("evaluated"))
        val perNight = checkNotNull(metrics["perNight"]).jsonArray.map { it.jsonObject }
        val ids = perNight.map { checkNotNull(it["id"]).jsonPrimitive.content }
        assertEquals(records.map { it.id } + records.map { "${it.id}-nap30" } + records.map { "${it.id}-nap90" }, ids)
        assertEquals(List(6) { "NIGHT" } + List(12) { "NAP" }, perNight.map { checkNotNull(it["mode"]).jsonPrimitive.content })
        // The pooled matrices of the modes add up to the whole, and NAP equals the library with the nap model.
        val night = longMatrix(byMode.obj("NIGHT").obj("pooled"), "confusion")
        val nap = longMatrix(byMode.obj("NAP").obj("pooled"), "confusion")
        assertEquals(longMatrix(metrics.obj("pooled"), "confusion"), night.zip(nap) { a, b -> a.zip(b) { x, y -> x + y } })
        assertNumber(proxyMatrix(SleepStager(bundle), listOf(30.0, 90.0)).kappa, byMode.obj("NAP").obj("pooled").num("kappa"), "NAP kappa")
        val mainOnly = proxyMatrix(SleepStager(SyntheticHybridModels.default), listOf(30.0, 90.0))
        assertTrue(mainOnly != proxyMatrix(SleepStager(bundle), listOf(30.0, 90.0)), "the nap section changes the naps")
        val rows = Files.readAllLines(perEpoch).drop(1)
        assertTrue(rows.any { it.contains("-nap30,") } && rows.any { it.contains("-nap90,") })
    }

    /** The pooled confusion matrix of the nap proxies of [lengths] of every night, estimated by [stager]. */
    private fun proxyMatrix(
        stager: SleepStager,
        lengths: List<Double>,
    ): ConfusionMatrix =
        records.flatMap { r -> lengths.mapNotNull { NapProxies.cut(r, it) } }.fold(ConfusionMatrix.EMPTY) { acc, proxy ->
            val estimate = (stager.run(proxy.input, AlgoParams()) as EstimationOutcome.Estimated).estimate
            assertEquals(SessionMode.NAP, estimate.mode)
            acc + IntervalScoring.score(proxy.labels, estimate.stages).confusion
        }

    @Test
    fun `fold models with a nap section decode each subject's naps with that subject's nap model`() {
        val folds = Files.createDirectories(dir.resolve("nap-folds"))
        val withNap = SyntheticHybridModels.default.copy(nap = NapModels.from(MinimalBundles.logreg()))
        for (subject in listOf("s0", "s1", "s2")) Files.writeString(folds.resolve("$subject.json"), withNap.toJson())
        val nights = nightsDir().toString()
        val out = dir.resolve("nap-folds.json")
        ok("evaluate", "--nights", nights, "--fold-models", folds.toString(), "--nap-proxies", "60", "--out", out.toString())
        val single = dir.resolve("nap-single.json")
        val model = Files.writeString(dir.resolve("nap-single-model.json"), withNap.toJson())
        ok("evaluate", "--nights", nights, "--model", model.toString(), "--nap-proxies", "60", "--out", single.toString())
        assertEquals(readJson(single).obj("byMode"), readJson(out).obj("byMode"))
        assertEquals(
            6,
            readJson(out)
                .obj("byMode")
                .obj("NAP")
                .obj("estimatorsUsed")
                .int("HYBRID"),
        )
        // Without the nap section in the fold bundle the same proxies decode differently.
        for (subject in listOf("s0", "s1", "s2")) Files.writeString(folds.resolve("$subject.json"), SyntheticHybridModels.default.toJson())
        val plain = dir.resolve("plain-folds.json")
        ok("evaluate", "--nights", nights, "--fold-models", folds.toString(), "--nap-proxies", "60", "--out", plain.toString())
        assertTrue(readJson(plain).obj("byMode").obj("NAP") != readJson(out).obj("byMode").obj("NAP"))
        assertEquals(readJson(plain).obj("byMode").obj("NIGHT"), readJson(out).obj("byMode").obj("NIGHT"), "nights do not change")
    }

    @Test
    fun `the estimator option sets the estimator of nights and naps`() {
        val out = dir.resolve("rules-naps.json")
        ok("evaluate", "--nights", nightsDir().toString(), "--estimator", "RULES", "--nap-proxies", "60", "--out", out.toString())
        val metrics = readJson(out)
        assertEquals("RULES", checkNotNull(metrics.obj("params").obj("napMode")["estimator"]).jsonPrimitive.content)
        assertEquals(
            6,
            metrics
                .obj("byMode")
                .obj("NAP")
                .obj("estimatorsUsed")
                .int("RULES"),
        )
        assertEquals(
            6,
            metrics
                .obj("byMode")
                .obj("NIGHT")
                .obj("estimatorsUsed")
                .int("RULES"),
        )
    }

    @Test
    fun `a nap proxy is cut like tools train's nap_proxy`() {
        val night = records.first()
        val start = night.input.sessionStartMs
        val end = start + 3_600_000L
        val proxy = checkNotNull(NapProxies.cut(night, 60.0))
        assertEquals("${night.id}-nap60", proxy.id)
        assertEquals(night.subject, proxy.subject)
        assertEquals(start, proxy.input.sessionStartMs)
        assertEquals(end, proxy.input.sessionEndMs)
        val context = start - 1_800_000L
        assertEquals(night.input.heartRate.filter { it.timeMs in context..end }, proxy.input.heartRate)
        assertEquals(night.input.steps.filter { it.endMs > context && it.endMs <= end }, proxy.input.steps)
        assertEquals(night.labels.filter { it.startMs >= start && it.endMs <= end }, proxy.labels)
        assertTrue(proxy.input.heartRate.isNotEmpty() && proxy.labels.isNotEmpty())
        assertEquals("${night.id}-nap90.5", NapProxies.cut(night, 90.5)?.id)
        assertEquals(null, NapProxies.cut(night, 24 * 60.0), "a night shorter than L gives no proxy")
        assertEquals(listOf(30.0, 60.0), NapProxies.parseLengths("30, 60"))
    }

    @Test
    fun `bad inputs give the documented exit codes`() {
        val nights = nightsDir().toString()
        val out = dir.resolve("x.json").toString()
        assertEquals(ExitCodes.USAGE, cli("evaluate", "--nights", nights).code)
        assertEquals(ExitCodes.USAGE, cli("evaluate", "--nights", nights, "--out", out, "--bogus", "1").code)
        assertEquals(ExitCodes.USAGE, cli("evaluate", "--nights", nights, "--out", out, "--estimator", "FOO").code)
        assertEquals(ExitCodes.NO_INPUT, cli("evaluate", "--nights", dir.resolve("missing").toString(), "--out", out).code)

        val grid = Files.writeString(dir.resolve("bad-grid.json"), """[{"hybrid": {"noSuchField": 1}}]""")
        val badGrid = cli("evaluate", "--nights", nights, "--out", out, "--param-grid", grid.toString())
        assertEquals(ExitCodes.DATA_ERROR, badGrid.code)
        assertTrue(badGrid.err.contains("grid entry 0"), badGrid.err)
        val empty = Files.writeString(dir.resolve("empty-grid.json"), "[]")
        assertEquals(ExitCodes.DATA_ERROR, cli("evaluate", "--nights", nights, "--out", out, "--param-grid", empty.toString()).code)

        // Out-of-range values are data errors naming every field (and the grid entry), never a JVM stack trace.
        val invalid =
            Files.writeString(
                dir.resolve("invalid.json"),
                """{"preprocess": {"epochSec": 0, "contextMarginMin": -30}, "features": {"varHalfWindowEpochs": -1}}""",
            )
        val badParams = cli("evaluate", "--nights", nights, "--out", out, "--params", invalid.toString())
        assertEquals(ExitCodes.DATA_ERROR, badParams.code, badParams.err)
        for (field in listOf("preprocess.epochSec", "preprocess.contextMarginMin", "features.varHalfWindowEpochs")) {
            assertTrue(badParams.err.contains(field), badParams.err)
        }
        assertTrue(!badParams.err.contains("Exception"), badParams.err)
        val badEntry =
            Files.writeString(dir.resolve("bad-entry.json"), """[{}, {"hybrid": {"maxBoutMin": 0}}, {"post": {"minDeepMin": -5}}]""")
        val badEntryRun = cli("evaluate", "--nights", nights, "--out", out, "--param-grid", badEntry.toString())
        assertEquals(ExitCodes.DATA_ERROR, badEntryRun.code)
        assertTrue(badEntryRun.err.contains("grid entry 1") && badEntryRun.err.contains("hybrid.maxBoutMin"), badEntryRun.err)

        val model =
            Files.writeString(
                dir.resolve("broken-model.json"),
                SyntheticHybridModels.default.toJson().replace("\"logreg\"", "\"svm\""),
            )
        assertEquals(ExitCodes.DATA_ERROR, cli("evaluate", "--nights", nights, "--out", out, "--model", model.toString()).code)
        val both = cli("evaluate", "--nights", nights, "--out", out, "--model", model.toString(), "--fold-models", dir.toString())
        assertEquals(ExitCodes.USAGE, both.code)
        for (lengths in listOf("", "30,abc", "0", "-30", "30,30")) {
            assertEquals(ExitCodes.USAGE, cli("evaluate", "--nights", nights, "--out", out, "--nap-proxies", lengths).code, lengths)
        }
        // The thresholds that napMode.maxSessionMin replaced are unknown keys now.
        for (legacy in listOf("""{"hybrid": {"shortSessionMaxMin": 180}}""", """{"features": {"baselineMinSessionMin": 180}}""")) {
            val file = Files.writeString(dir.resolve("legacy.json"), legacy)
            assertEquals(ExitCodes.DATA_ERROR, cli("evaluate", "--nights", nights, "--out", out, "--params", file.toString()).code, legacy)
        }
    }
}
