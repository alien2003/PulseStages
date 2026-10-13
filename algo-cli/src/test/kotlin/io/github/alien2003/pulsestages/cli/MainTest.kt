package io.github.alien2003.pulsestages.cli

import io.github.alien2003.pulsestages.algo.SleepStager
import io.github.alien2003.pulsestages.algo.export.CsvExporter
import io.github.alien2003.pulsestages.algo.hybrid.HybridFeatureExtractor
import io.github.alien2003.pulsestages.algo.metrics.EpochLabels
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.night.NightPreparer
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticOptions
import io.github.alien2003.pulsestages.algo.testing.SyntheticHybridModels
import io.github.alien2003.pulsestages.cli.CliTestSupport.cli
import io.github.alien2003.pulsestages.cli.CliTestSupport.ok
import io.github.alien2003.pulsestages.cli.CliTestSupport.record
import io.github.alien2003.pulsestages.cli.CliTestSupport.writeModel
import io.github.alien2003.pulsestages.cli.CliTestSupport.writeNights
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MainTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `stages command lists all stages`() {
        val result = cli("stages")

        assertEquals(ExitCodes.OK, result.code)
        assertEquals("AWAKE,LIGHT,DEEP,REM", result.out.trim())
        assertTrue(result.err.isEmpty())
    }

    @Test
    fun `unknown command is a usage error`() {
        val result = cli("bogus")

        assertEquals(ExitCodes.USAGE, result.code)
        assertTrue(result.err.contains("Unknown command: bogus"))
        assertTrue(ok("--help").out.contains("evaluate"))
    }

    @Test
    fun `feature-names prints the feature contract`() {
        val json = Json.parseToJsonElement(ok("feature-names").out).jsonObject
        assertEquals(HybridFeatureExtractor.FEATURE_SET_VERSION.toString(), checkNotNull(json["featureSetVersion"]).jsonPrimitive.content)
        assertEquals(HybridFeatureExtractor.FEATURE_NAMES, checkNotNull(json["featureNames"]).jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `features writes one row per session epoch with labels and the documented columns`() {
        val records =
            listOf(record(6001, "a"), record(6002, "b", index = 1), record(6003, "a", index = 2, SyntheticOptions(longGapMin = 40)))
        val out = dir.resolve("features.csv")

        ok("features", "--nights", writeNights(dir.resolve("nights"), records).toString(), "--out", out.toString())

        val lines = Files.readAllLines(out)
        val header = lines.first().split(',')
        val expected =
            listOf("night_id", "subject", "epoch", "start_ms", "has_data", "label") + HybridFeatureExtractor.FEATURE_NAMES +
                listOf("cyclePhase", "steps")
        assertEquals(expected, header)
        val rows = lines.drop(1).map { it.split(',') }
        assertTrue(rows.all { it.size == header.size })
        val params = AlgoParams()
        for (r in records) {
            val night = NightPreparer.prepare(r.input, params)
            val mine = rows.filter { it[0] == r.id }
            assertEquals(night.grid.sessionEpochs, mine.size)
            val labels = EpochLabels.majority(r.labels, night.grid)
            assertEquals(labels.map { it?.name.orEmpty() }, mine.map { it[5] })
            val features = HybridFeatureExtractor.extract(night)
            val phase = header.indexOf("cyclePhase")
            val steps = header.indexOf("steps")
            mine.forEachIndexed { k, row ->
                val p = night.grid.position(k)
                assertEquals(r.id, row[0])
                assertEquals(r.subject, row[1])
                assertEquals(k.toString(), row[2])
                assertEquals(night.grid.startMs(k).toString(), row[3])
                assertEquals(if (night.sessionHasData(k)) "1" else "0", row[4])
                HybridFeatureExtractor.FEATURE_NAMES.forEachIndexed { j, name ->
                    assertEquals(Csv.number(features[k][j]), row[header.indexOf(name)], "${r.id} epoch $k $name")
                }
                assertEquals(Csv.number(night.features.cycle.phase[p]), row[phase], "${r.id} epoch $k cyclePhase")
                assertEquals(Csv.number(night.features.activity.steps[p]), row[steps], "${r.id} epoch $k steps")
            }
        }
        assertTrue(rows.any { it[4] == "0" }, "the night with a long gap has epochs without data")
        val phase = header.indexOf("cyclePhase")
        val steps = header.indexOf("steps")
        assertTrue(rows.any { it[phase] != it[steps] && it[phase].isNotEmpty() }, "cyclePhase and steps distinguishable")
        assertTrue(rows.any { it[steps] != "0.0" }, "some epochs have steps")
    }

    @Test
    fun `estimate writes the same CSV as the in-app export`() {
        val night = record(6100, "a")
        val file = writeNights(dir.resolve("one"), listOf(night)).resolve("${night.id}.night.json")
        val model = writeModel(dir.resolve("model.json"))
        val csv = dir.resolve("estimate.csv")

        val result =
            ok("estimate", "--night", file.toString(), "--model", model.toString(), "--estimator", "hybrid", "--csv", csv.toString())

        val params = AlgoParams(estimator = EstimatorType.HYBRID)
        val estimate = (SleepStager(SyntheticHybridModels.default).run(night.input, params) as EstimationOutcome.Estimated).estimate
        assertEquals(CsvExporter.export(estimate), Files.readString(csv))
        assertTrue(result.out.contains("estimator=HYBRID"), result.out)

        val noHr = night.copy(id = "no-hr", input = night.input.copy(heartRate = emptyList()))
        val noHrFile = writeNights(dir.resolve("two"), listOf(noHr)).resolve("no-hr.night.json")
        val skipped = cli("estimate", "--night", noHrFile.toString(), "--csv", dir.resolve("x.csv").toString())
        assertEquals(ExitCodes.NOT_ESTIMATED, skipped.code)
        assertTrue(skipped.err.contains("NO_HR_DATA"))
        val many = writeNights(dir.resolve("many"), listOf(night, noHr)).toString()
        assertEquals(ExitCodes.USAGE, cli("estimate", "--night", many, "--csv", csv.toString()).code)
        ok("estimate", "--night", many, "--id", night.id, "--csv", csv.toString())
    }

    @Test
    fun `night files accept jsonl, float times, optional channels and nulls`() {
        val jsonl = dir.resolve("nights.jsonl")
        val first =
            """{"id": "n1", "sessionStartMs": 0, "sessionEndMs": 3.6E6, "hr": [[0.0, 60], [60000, 61.5]], "steps": [[0, 60000, 3]], """ +
                """"spo2": [[0, 97]], "hrv": [[0, 40]], "respiratoryRate": [[0, 14]], "forcedAwake": [[60000, 120000]], """ +
                """"baselineNadirBpm": 55, "labels": [[0, 30000, "LIGHT"], [30000, 60000, "UNKNOWN"]]}"""
        val second =
            """{"id": "n2", "subject": "p2", "variant": null, "sessionStartMs": 0, "sessionEndMs": 60000, """ +
                """"hr": [], "labels": null}"""
        Files.writeString(jsonl, "$first\n\n$second\n")

        val nights = NightFiles.read(jsonl)

        assertEquals(listOf("n1", "n2"), nights.map { it.id })
        val parsed = nights[0]
        assertEquals("n1", parsed.subject)
        assertEquals(3_600_000L, parsed.input.sessionEndMs)
        assertEquals(61.5, parsed.input.heartRate[1].bpm)
        assertEquals(1, parsed.input.forcedAwake.size)
        assertEquals(55.0, parsed.input.baselineNadirBpm)
        assertEquals(1, parsed.input.hrv.size)
        assertEquals(2, parsed.labels.size)
        assertEquals("p2", nights[1].subject)
        assertEquals(null, nights[1].variant)
        assertEquals(parsed, NightFiles.parse(NightFiles.toJson(parsed).toString(), "round trip"))

        val bad = Files.writeString(dir.resolve("bad.night.json"), """{"id": "x", "sessionStartMs": 10, "sessionEndMs": 5}""")
        assertEquals(ExitCodes.DATA_ERROR, cli("features", "--nights", bad.toString(), "--out", dir.resolve("f.csv").toString()).code)
        val stage =
            Files.writeString(
                dir.resolve("stage.night.json"),
                """{"id": "x", "sessionStartMs": 0, "sessionEndMs": 5, "labels": [[0, 5, "N3"]]}""",
            )
        assertTrue(
            cli("features", "--nights", stage.toString(), "--out", dir.resolve("f.csv").toString()).err.contains("unknown stage 'N3'"),
        )
    }

    @Test
    fun `synthesize writes labelled nights that evaluate`() {
        val out = dir.resolve("synthetic")
        ok("synthesize", "--out", out.toString(), "--count", "4", "--seed", "70", "--subjects", "2")

        val nights = NightFiles.read(out)
        assertEquals(4, nights.size)
        assertEquals(setOf("s000", "s001"), nights.map { it.subject }.toSet())
        assertTrue(nights.all { it.labels.isNotEmpty() })
        ok("evaluate", "--nights", out.toString(), "--estimator", "RULES", "--out", dir.resolve("m.json").toString())
        assertEquals(ExitCodes.USAGE, cli("synthesize", "--out", out.toString(), "--count", "0").code)
    }
}
