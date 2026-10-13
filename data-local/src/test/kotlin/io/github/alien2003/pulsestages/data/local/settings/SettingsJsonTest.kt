package io.github.alien2003.pulsestages.data.local.settings

import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.DurationModel
import io.github.alien2003.pulsestages.algo.params.GapPolicy
import io.github.alien2003.pulsestages.algo.params.HybridParams
import io.github.alien2003.pulsestages.algo.params.PostprocessParams
import io.github.alien2003.pulsestages.algo.params.PreprocessParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SettingsJsonTest {
    private val customized =
        AppSettings(
            sources =
                SourceSettings(
                    sourcePackages = listOf(OHEALTH_PACKAGE, "com.example.watch"),
                    sourcePriority = listOf("com.example.watch", OHEALTH_PACKAGE),
                    manualPackages = listOf("com.example.watch"),
                    hrSources = HrSourcesMode.ANY,
                    minSessionMin = 120,
                    mergeGapMin = 45,
                    backfillDays = 365,
                ),
            schedule = ScheduleSettings(dailyTimeMinutes = 480, periodicHours = 3, runOnlyWhenNotLowBattery = false),
            write =
                WriteSettings(
                    titleTemplate = "Stages {estimator} v{version}",
                    overwritePolicy = OverwritePolicy.NEVER,
                    awakeAsLight = true,
                ),
            algo =
                AlgoParams(
                    estimator = EstimatorType.HMM,
                    preprocess =
                        PreprocessParams(epochSec = 30, smoothWindowEpochs = 7),
                    hybrid =
                        HybridParams(durationModel = DurationModel.GEOMETRIC, maxBoutMin = 90),
                    post =
                        PostprocessParams(gapPolicy = GapPolicy.NEAREST, calibrateProportions = true),
                ),
            debug = DebugSettings(logLevel = LogLevel.DEBUG),
        )

    @Test
    fun `export then import round-trips customized settings`() {
        val json = SettingsJson.encode(customized)

        val result = SettingsJson.decode(json)

        assertEquals(SettingsImportResult.Success(customized), result)
    }

    @Test
    fun `export then import round-trips defaults`() {
        assertEquals(SettingsImportResult.Success(AppSettings()), SettingsJson.decode(SettingsJson.encode(AppSettings())))
    }

    @Test
    fun `export is pretty and contains every default including nulls`() {
        val json = SettingsJson.encode(AppSettings())
        val root = Json.parseToJsonElement(json).jsonObject

        assertTrue(json.contains("\n"), "pretty printed")
        assertEquals(setOf("sources", "schedule", "write", "algo", "debug"), root.keys)
        assertEquals(JsonNull, root.getValue("write").jsonObject["titleTemplate"])
        val algo = root.getValue("algo").jsonObject
        assertEquals(setOf("estimator", "preprocess", "features", "hmm", "rules", "hybrid", "post", "personal"), algo.keys)
        assertTrue(
            algo
                .getValue("hmm")
                .jsonObject
                .getValue("emissions")
                .jsonObject
                .containsKey("deep"),
        )
    }

    @Test
    fun `partial JSON fills every missing key with its default`() {
        val json = """{"sources": {"minSessionMin": 120}, "algo": {"preprocess": {"epochSec": 30}}}"""

        val result = SettingsJson.decode(json)

        val expected =
            AppSettings(
                sources = SourceSettings(minSessionMin = 120),
                algo =
                    AlgoParams(
                        preprocess =
                            PreprocessParams(epochSec = 30),
                    ),
            )
        assertEquals(SettingsImportResult.Success(expected), result)
    }

    @Test
    fun `empty object imports as defaults`() {
        assertEquals(SettingsImportResult.Success(AppSettings()), SettingsJson.decode("{}"))
    }

    @Test
    fun `unknown keys are ignored at every level`() {
        val json =
            """
            {
              "formatVersion": 7,
              "sources": {"minSessionMin": 100, "futureOption": [1, 2, 3]},
              "algo": {"hmm": {"meanBouts": {"awakeMin": 6.0, "napMin": 3.0}}, "newEstimatorBlock": {"x": true}},
              "ui": {"theme": "dark"}
            }
            """.trimIndent()

        val result = SettingsJson.decode(json)

        val success = assertInstanceOf(SettingsImportResult.Success::class.java, result)
        assertEquals(100, success.settings.sources.minSessionMin)
        assertEquals(6.0, success.settings.algo.hmm.meanBouts.awakeMin)
        assertEquals(
            AppSettings()
                .algo.hmm.meanBouts.lightMin,
            success.settings.algo.hmm.meanBouts.lightMin,
        )
    }

    @Test
    fun `import repairs sourcePriority by appending enabled and manual packages`() {
        val json =
            """
            {"sources": {"sourcePackages": ["com.example.a", "com.example.b", "com.example.a"],
                         "sourcePriority": ["com.example.b"],
                         "manualPackages": ["com.example.c"]}}
            """.trimIndent()

        val result = SettingsJson.decode(json)

        val sources = assertInstanceOf(SettingsImportResult.Success::class.java, result).settings.sources
        assertEquals(listOf("com.example.a", "com.example.b"), sources.sourcePackages)
        assertEquals(listOf("com.example.b", "com.example.a", "com.example.c"), sources.sourcePriority)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{not json",
            "",
            "[]",
            "42",
            """{"sources": {"minSessionMin": "ninety"}}""",
            """{"sources": {"minSessionMin": 90.5}}""",
            """{"sources": {"minSessionMin": null}}""",
            """{"algo": {"estimator": "NEURAL"}}""",
            """{"write": {"overwritePolicy": "sometimes"}}""",
            """{"sources": {"sourcePackages": "com.example.a"}}""",
        ],
    )
    fun `malformed JSON is rejected with a MALFORMED_JSON error`(json: String) {
        val result = SettingsJson.decode(json)

        val invalid = assertInstanceOf(SettingsImportResult.Invalid::class.java, result)
        assertEquals(1, invalid.errors.size)
        assertEquals(SettingsErrorCode.MALFORMED_JSON, invalid.errors.single().code)
        assertEquals("$", invalid.errors.single().path)
        assertTrue(
            invalid.errors
                .single()
                .constraint
                .isNotBlank(),
            "parser message is kept",
        )
    }

    @Test
    fun `invalid values are rejected with every error and nothing else`() {
        val json = """{"sources": {"minSessionMin": 5, "mergeGapMin": 500}, "algo": {"preprocess": {"epochSec": 45}}}"""

        val result = SettingsJson.decode(json)

        val invalid = assertInstanceOf(SettingsImportResult.Invalid::class.java, result)
        assertEquals(
            listOf("sources.minSessionMin", "sources.mergeGapMin", "algo.preprocess.epochSec"),
            invalid.errors.map { it.path },
        )
    }
}
