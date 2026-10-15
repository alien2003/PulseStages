package io.github.alien2003.pulsestages.algo.hybrid

import io.github.alien2003.pulsestages.algo.Fingerprints
import io.github.alien2003.pulsestages.algo.PreviousBundle
import io.github.alien2003.pulsestages.algo.testing.MinimalBundles
import io.github.alien2003.pulsestages.algo.testing.NapModels
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class HybridModelTest {
    private val model = MinimalBundles.logreg()
    private val tree: JsonObject = Json.parseToJsonElement(model.toJson()).jsonObject

    /** [element] with the value at [path] (object keys or array indices) replaced by [value]; null removes a key. */
    private fun replaced(
        element: JsonElement,
        path: List<Any>,
        value: JsonElement?,
    ): JsonElement {
        if (path.isEmpty()) return checkNotNull(value)
        val head = path.first()
        return when (element) {
            is JsonObject -> {
                val map = element.toMutableMap()
                val key = head as String
                if (path.size == 1 &&
                    value == null
                ) {
                    map.remove(key)
                } else {
                    map[key] = replaced(checkNotNull(element[key]), path.drop(1), value)
                }
                JsonObject(map)
            }

            is JsonArray -> {
                val list = element.toMutableList()
                val index = head as Int
                list[index] = replaced(list[index], path.drop(1), value)
                JsonArray(list)
            }

            else -> {
                error("cannot descend into $element")
            }
        }
    }

    private fun mutated(
        vararg path: Any,
        value: JsonElement?,
    ): String = replaced(tree, path.toList(), value).toString()

    private fun reversedKeys(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.entries.reversed().associate { (k, v) -> k to reversedKeys(v) })
            is JsonArray -> JsonArray(element.map { reversedKeys(it) })
            else -> element
        }

    private fun assertInvalid(
        json: String,
        expectedMessagePart: String,
    ) {
        val error = assertThrows(HybridModelException::class.java) { HybridModel.parse(json) }
        assertTrue(
            error.message.orEmpty().contains(expectedMessagePart),
            "message '${error.message}' should contain '$expectedMessagePart'",
        )
    }

    @Test
    fun `bundles round-trip through JSON with a stable fingerprint`() {
        val parsed = HybridModel.parse(model.toJson())

        assertEquals(model, parsed)
        assertEquals(model.fingerprint(), parsed.fingerprint())
        assertTrue(Regex("[0-9a-f]{64}").matches(model.fingerprint()))
        val reordered = HybridModel.parse(reversedKeys(tree).toString())
        assertEquals(model.fingerprint(), reordered.fingerprint(), "key order and whitespace must not matter")
        assertNotEquals(model.fingerprint(), model.copy(modelId = "other").fingerprint())
        assertNotEquals(model.fingerprint(), model.copy(classPriors = listOf(0.2, 0.4, 0.2, 0.2)).fingerprint())
    }

    @Test
    fun `the optional stage log-bias is validated and fingerprinted only when present`() {
        assertNull(model.stageLogBias)
        assertTrue("stageLogBias" !in tree, "absent from the JSON of a bundle without it")
        // A bundle without the field keeps the fingerprint of the canonical JSON that never had the key (bundles
        // shipped before the field existed, and the personal models keyed to them, stay valid).
        val canonical =
            Json {
                encodeDefaults = true
                explicitNulls = true
            }
        val full = canonical.encodeToJsonElement(HybridModel.serializer(), model).jsonObject
        assertTrue("stageLogBias" in full && "stageLogBiasShort" in full && "nap" in full)
        assertEquals(Fingerprints.of(JsonObject(full - "stageLogBias" - "stageLogBiasShort" - "nap")), model.fingerprint())

        val biased = model.copy(stageLogBias = listOf(0.3, -0.2, -0.5, 0.4))
        val parsed = HybridModel.parse(biased.toJson())
        assertEquals(biased, parsed)
        assertEquals(listOf(0.3, -0.2, -0.5, 0.4), parsed.stageLogBias)
        assertEquals(biased.fingerprint(), parsed.fingerprint())
        assertNotEquals(model.fingerprint(), biased.fingerprint())
        assertNotEquals(biased.fingerprint(), model.copy(stageLogBias = listOf(0.3, -0.2, -0.5, 0.41)).fingerprint())
        val zeros = JsonArray(List(4) { JsonPrimitive(0.0) })
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), HybridModel.parse(JsonObject(tree + ("stageLogBias" to zeros)).toString()).stageLogBias)
        val three = JsonArray(List(3) { JsonPrimitive(0.1) })
        assertInvalid(JsonObject(tree + ("stageLogBias" to three)).toString(), "stageLogBias must have 4")
        assertThrows(HybridModelException::class.java) { model.copy(stageLogBias = listOf(0.0, Double.NaN, 0.0, 0.0)) }
        assertInvalid(JsonObject(tree + ("stageLogBias" to JsonPrimitive("high"))).toString(), "malformed")
    }

    @Test
    fun `the optional short-session log-bias is validated and fingerprinted only when present`() {
        assertNull(model.stageLogBiasShort)
        assertTrue("stageLogBiasShort" !in tree, "absent from the JSON of a bundle without it")
        val biased = model.copy(stageLogBias = listOf(0.3, -0.2, -0.5, 0.4))
        val withShort = biased.copy(stageLogBiasShort = listOf(-0.1, 0.4, 0.3, -0.6))
        val parsed = HybridModel.parse(withShort.toJson())
        assertEquals(withShort, parsed)
        assertEquals(listOf(-0.1, 0.4, 0.3, -0.6), parsed.stageLogBiasShort)
        assertEquals(withShort.fingerprint(), parsed.fingerprint())
        // Adding the short-session bias changes the fingerprint; a bundle without it keeps its old one.
        assertNotEquals(biased.fingerprint(), withShort.fingerprint())
        assertEquals(biased.fingerprint(), HybridModel.parse(biased.toJson()).fingerprint())
        // The same numbers under the other key are a different bundle.
        assertNotEquals(
            model.copy(stageLogBias = listOf(-0.1, 0.4, 0.3, -0.6)).fingerprint(),
            model.copy(stageLogBiasShort = listOf(-0.1, 0.4, 0.3, -0.6)).fingerprint(),
        )
        val three = JsonArray(List(3) { JsonPrimitive(0.1) })
        assertInvalid(JsonObject(tree + ("stageLogBiasShort" to three)).toString(), "stageLogBiasShort must have 4")
        assertThrows(HybridModelException::class.java) { model.copy(stageLogBiasShort = listOf(0.0, 0.0, Double.POSITIVE_INFINITY, 0.0)) }
    }

    @Test
    fun `unknown keys are ignored and optional sections may be absent`() {
        val withExtra = JsonObject(tree + ("validationReport" to JsonPrimitive("ignored")))
        assertEquals(model, HybridModel.parse(withExtra.toString()))
        assertNull(HybridModel.parse(mutated("hmm", value = null)).hmm)
    }

    @Test
    fun `structural problems are rejected with a reason`() {
        assertInvalid("{", "malformed")
        assertInvalid(mutated("durations", value = null), "malformed")
        assertInvalid(mutated("schemaVersion", value = JsonPrimitive(2)), "schemaVersion")
        assertInvalid(mutated("classOrder", 0, value = JsonPrimitive("LIGHT")), "classOrder")
        assertInvalid(mutated("featureNames", 1, value = JsonPrimitive("hrNorm")), "duplicates")
        assertInvalid(mutated("standardization", "std", 3, value = JsonPrimitive(0.0)), "standardization.std")
        val shortMean = JsonArray(checkNotNull(checkNotNull(tree["standardization"]).jsonObject["mean"]).jsonArray.drop(1))
        assertInvalid(mutated("standardization", "mean", value = shortMean), "standardization needs")
        assertInvalid(mutated("classifier", "layers", 0, "weights", 2, value = JsonArray(listOf(JsonPrimitive(1.0)))), "inputs")
        assertInvalid(mutated("classifier", "layers", 0, "bias", value = JsonArray(List(3) { JsonPrimitive(0.0) })), "weight rows")
        assertInvalid(mutated("classifier", "layers", 0, "activation", value = JsonPrimitive("relu")), "activation must be SOFTMAX")
        assertInvalid(mutated("classifier", "layers", 0, "activation", value = JsonPrimitive("tanh")), "malformed")
        assertInvalid(mutated("classPriors", 0, value = JsonPrimitive(0.0)), "classPriors entries must be positive")
        assertInvalid(mutated("classPriors", 0, value = JsonPrimitive(0.3)), "classPriors must sum to 1")
        assertInvalid(mutated("boutTransitions", 1, 0, value = JsonPrimitive(-0.2)), "non-negative")
        assertInvalid(mutated("boutTransitions", 2, 1, value = JsonPrimitive(0.5)), "boutTransitions[2] must sum to 1")
        assertInvalid(mutated("initialStage", value = JsonArray(List(3) { JsonPrimitive(1.0 / 3) })), "initialStage must have 4")
        assertInvalid(mutated("durations", "DEEP", "shape", value = JsonPrimitive(0.0)), "durations.DEEP")
        assertInvalid(mutated("durations", "AWAKE", "scale", value = JsonPrimitive(-1.0)), "durations.AWAKE")
        assertInvalid(mutated("timePrior", "binMinutes", value = JsonPrimitive(0.0)), "binMinutes")
        assertInvalid(mutated("timePrior", "table", 1, 0, value = JsonPrimitive(0.9)), "timePrior.table[1]")
        assertInvalid(mutated("timePrior", "table", value = JsonArray(emptyList())), "timePrior.table is empty")
        assertInvalid(model.toJson().replaceFirst("1.5,", "NaN,"), "malformed")
    }

    @Test
    fun `logreg must be a single layer and mlp layers must chain`() {
        val hidden = DenseLayer(List(3) { List(model.featureNames.size) { 0.1 } }, List(3) { 0.0 }, Activation.RELU)
        val output = DenseLayer(List(4) { List(3) { 0.2 } }, List(4) { 0.0 }, Activation.SOFTMAX)
        val mlp = model.copy(classifier = ClassifierSpec(ClassifierType.MLP, listOf(hidden, output)))
        assertEquals(mlp, HybridModel.parse(mlp.toJson()))

        val error =
            assertThrows(HybridModelException::class.java) {
                model.copy(classifier = ClassifierSpec(ClassifierType.LOGREG, listOf(hidden, output)))
            }
        assertTrue(error.message.orEmpty().contains("logreg"))
        assertThrows(HybridModelException::class.java) {
            model.copy(classifier = ClassifierSpec(ClassifierType.MLP, listOf(hidden, output.copy(weights = List(4) { List(2) { 0.2 } }))))
        }
    }

    @Test
    fun `compatibility with this build's features is checked separately`() {
        assertNull(model.compatibilityProblem())
        assertTrue(
            model
                .copy(featureSetVersion = 2)
                .compatibilityProblem()
                .orEmpty()
                .contains("featureSetVersion 2"),
        )
        val renamed = HybridFeatureExtractor.FEATURE_NAMES.toMutableList().also { it[5] = "somethingElse" }
        val problem = MinimalBundles.logreg(featureNames = renamed).compatibilityProblem().orEmpty()
        assertTrue(problem.contains("index 5") && problem.contains("somethingElse"), problem)
        val shorter = MinimalBundles.logreg(featureNames = HybridFeatureExtractor.FEATURE_NAMES.dropLast(1)).compatibilityProblem()
        assertTrue(shorter.orEmpty().contains("index ${HybridFeatureExtractor.FEATURE_NAMES.size - 1}"), shorter)
    }

    @Test
    fun `the optional nap section parses, round-trips and enters the fingerprint`() {
        assertNull(model.nap)
        assertTrue("nap" !in tree, "absent from the JSON of a bundle without it")
        val nap =
            NapModels.from(
                MinimalBundles.logreg(hrNormWeight = listOf(1.0, 0.2, -1.0, 0.5)),
                stageLogBias = listOf(0.1, 0.2, -0.1, -0.2),
            )
        val withNap = model.copy(nap = nap)
        val parsed = HybridModel.parse(withNap.toJson())
        assertEquals(withNap, parsed)
        assertEquals(nap, parsed.nap)
        assertEquals(withNap.fingerprint(), parsed.fingerprint())
        assertNotEquals(model.fingerprint(), withNap.fingerprint())
        assertNotEquals(withNap.fingerprint(), model.copy(nap = nap.copy(stageLogBias = null)).fingerprint())
        assertNotEquals(withNap.fingerprint(), model.copy(nap = nap.copy(classPriors = listOf(0.2, 0.4, 0.2, 0.2))).fingerprint())
        // The nap section's keys are just as order-independent, and its training info is optional.
        val reordered = HybridModel.parse(reversedKeys(Json.parseToJsonElement(withNap.toJson())).toString())
        assertEquals(withNap.fingerprint(), reordered.fingerprint())
        assertNull(HybridModel.parse(model.copy(nap = nap.copy(training = null)).toJson()).nap?.training)
        assertNull(withNap.napCompatibilityProblem())
        assertNull(model.napCompatibilityProblem())
    }

    @Test
    fun `the nap section is validated like the main model, with nap-prefixed reasons`() {
        val napTree = Json.parseToJsonElement(model.copy(nap = NapModels.from(model, stageLogBias = List(4) { 0.0 })).toJson()).jsonObject

        fun napMutated(
            vararg path: Any,
            value: JsonElement?,
        ): String = replaced(napTree, listOf("nap") + path.toList(), value).toString()

        assertInvalid(napMutated("featureNames", 1, value = JsonPrimitive("hrNorm")), "nap.featureNames contains duplicates")
        assertInvalid(napMutated("standardization", "std", 3, value = JsonPrimitive(0.0)), "nap.standardization.std")
        assertInvalid(napMutated("classifier", "layers", 0, "activation", value = JsonPrimitive("relu")), "nap.layer 0: activation")
        assertInvalid(napMutated("classifier", "layers", 0, "bias", value = JsonArray(List(3) { JsonPrimitive(0.0) })), "nap.layer 0")
        assertInvalid(napMutated("classPriors", 0, value = JsonPrimitive(0.3)), "nap.classPriors must sum to 1")
        assertInvalid(napMutated("boutTransitions", 2, 1, value = JsonPrimitive(0.5)), "nap.boutTransitions[2] must sum to 1")
        assertInvalid(napMutated("initialStage", value = JsonArray(List(3) { JsonPrimitive(1.0 / 3) })), "nap.initialStage must have 4")
        assertInvalid(napMutated("durations", "DEEP", "shape", value = JsonPrimitive(0.0)), "nap.durations.DEEP")
        assertInvalid(napMutated("timePrior", "binMinutes", value = JsonPrimitive(0.0)), "nap.timePrior.binMinutes")
        assertInvalid(napMutated("stageLogBias", value = JsonArray(List(3) { JsonPrimitive(0.1) })), "nap.stageLogBias must have 4")
        assertInvalid(napMutated("timePrior", value = null), "malformed")
        assertThrows(
            HybridModelException::class.java,
        ) { model.copy(nap = NapModels.from(model, stageLogBias = listOf(0.0, Double.NaN, 0.0, 0.0))) }
    }

    @Test
    fun `the previous bundle keeps the fingerprints the base build computed before any change`() {
        // The pins come from the logged NOTE of the base build (PreviousBundle), never from this build.
        val digest = MessageDigest.getInstance("SHA-256").digest(PreviousBundle.bytes).joinToString("") { "%02x".format(it) }
        assertEquals(PreviousBundle.SHA256, digest, "the test resource is the previous shipped bundle, byte for byte")
        val previous = PreviousBundle.model
        assertEquals(PreviousBundle.FINGERPRINT, previous.fingerprint())
        assertEquals(PreviousBundle.NIGHT_MODEL_FINGERPRINT, previous.nightModelFingerprint())
        assertNull(checkNotNull(previous.nap).stageLogBiasByLength)
        // Written again, it keeps both (an absent length table is not written and not fingerprinted).
        val rewritten = HybridModel.parse(previous.toJson())
        assertTrue("stageLogBiasByLength" !in checkNotNull(Json.parseToJsonElement(previous.toJson()).jsonObject["nap"]).jsonObject)
        assertEquals(PreviousBundle.FINGERPRINT, rewritten.fingerprint())
        assertEquals(PreviousBundle.NIGHT_MODEL_FINGERPRINT, rewritten.nightModelFingerprint())
    }

    @Test
    fun `a length table interpolates linearly between its anchors and holds the end rows outside`() {
        val r30 = listOf(0.5, -0.25, 0.0, -0.25)
        val r60 = listOf(-0.5, 0.25, 0.5, -0.25)
        val r90 = listOf(0.0, 0.0, 1.0, -1.0)
        val table = LengthBiasTable(listOf(30.0, 60.0, 90.0), listOf(r30, r60, r90))
        assertSame(r30, table.at(30.0), "an anchor gets exactly its row")
        assertSame(r60, table.at(60.0))
        assertSame(r90, table.at(90.0))
        assertSame(r30, table.at(10.0), "below the first anchor: the first row")
        assertSame(r30, table.at(0.0))
        assertSame(r90, table.at(179.9), "above the last anchor: the last row")
        assertEquals(listOf(0.0, 0.0, 0.25, -0.25), table.at(45.0))
        assertEquals(listOf(-0.25, 0.125, 0.75, -0.625), table.at(75.0))
        val third = table.at(40.0)
        for (s in 0 until 4) assertEquals(r30[s] + (r60[s] - r30[s]) / 3.0, third[s], 1e-12)
        val single = LengthBiasTable(listOf(90.0), listOf(r90))
        for (minutes in listOf(1.0, 90.0, 170.0)) assertSame(r90, single.at(minutes))
    }

    @Test
    fun `the nap length table parses, round-trips and enters the fingerprint only when present`() {
        val scalarNap = NapModels.from(model, stageLogBias = listOf(0.1, 0.2, -0.1, -0.2))
        val withScalar = model.copy(nap = scalarNap)
        // A nap section without the table keeps the canonical JSON it had before the field existed (no key at all).
        val canonical =
            Json {
                encodeDefaults = true
                explicitNulls = true
            }
        val full = canonical.encodeToJsonElement(HybridModel.serializer(), withScalar).jsonObject
        val napTree = checkNotNull(full["nap"]).jsonObject
        assertTrue("stageLogBiasByLength" in napTree && "training" in napTree)
        val before = JsonObject(full - "stageLogBias" - "stageLogBiasShort" + ("nap" to JsonObject(napTree - "stageLogBiasByLength")))
        assertEquals(Fingerprints.of(before), withScalar.fingerprint())
        assertTrue("stageLogBiasByLength" !in checkNotNull(Json.parseToJsonElement(withScalar.toJson()).jsonObject["nap"]).jsonObject)
        // A null nap stageLogBias stays in the canonical JSON, as it always did.
        val noBias = model.copy(nap = scalarNap.copy(stageLogBias = null))
        val noBiasTree = canonical.encodeToJsonElement(HybridModel.serializer(), noBias).jsonObject
        val noBiasNap = checkNotNull(noBiasTree["nap"]).jsonObject - "stageLogBiasByLength"
        val noBiasBefore = JsonObject(noBiasTree - "stageLogBias" - "stageLogBiasShort" + ("nap" to JsonObject(noBiasNap)))
        assertEquals(Fingerprints.of(noBiasBefore), noBias.fingerprint())

        val rows = listOf(listOf(0.1, 0.2, -0.1, -0.2), List(4) { 0.0 }, listOf(-0.3, 0.1, 0.1, 0.1))
        val table = LengthBiasTable(listOf(30.0, 90.0, 170.0), rows)
        val withTable = model.copy(nap = scalarNap.copy(stageLogBias = null, stageLogBiasByLength = table))
        val parsed = HybridModel.parse(withTable.toJson())
        assertEquals(withTable, parsed)
        assertEquals(table, parsed.nap?.stageLogBiasByLength)
        assertEquals(withTable.fingerprint(), parsed.fingerprint())
        val reordered = HybridModel.parse(reversedKeys(Json.parseToJsonElement(withTable.toJson())).toString())
        assertEquals(withTable.fingerprint(), reordered.fingerprint())
        assertNotEquals(withScalar.fingerprint(), withTable.fingerprint())
        assertNotEquals(noBias.fingerprint(), withTable.fingerprint())
        val changed = table.copy(bias = rows.toMutableList().also { it[1] = listOf(0.0, 0.0, 0.0, 0.01) })
        val withChanged = model.copy(nap = checkNotNull(withTable.nap).copy(stageLogBiasByLength = changed))
        assertNotEquals(withTable.fingerprint(), withChanged.fingerprint())
        // Nights never read the nap section: the night-model fingerprint does not see the table.
        assertEquals(model.nightModelFingerprint(), withTable.nightModelFingerprint())
        assertEquals(withScalar.nightModelFingerprint(), withTable.nightModelFingerprint())
        // The bias a nap gets: the table at its length, else the constant one.
        assertEquals(table.at(60.0), checkNotNull(withTable.nap).stageLogBiasFor(60.0))
        assertEquals(scalarNap.stageLogBias, scalarNap.stageLogBiasFor(60.0))
        assertNull(scalarNap.copy(stageLogBias = null).stageLogBiasFor(60.0))
    }

    @Test
    fun `the nap length table is validated with nap-prefixed reasons and excludes a constant nap bias`() {
        val nap = NapModels.from(model, stageLogBias = null)
        val rows = listOf(List(4) { 0.0 }, listOf(0.1, -0.1, 0.0, 0.0))

        fun withTable(table: LengthBiasTable): HybridModel = model.copy(nap = nap.copy(stageLogBiasByLength = table))

        fun assertRejected(
            table: LengthBiasTable,
            expectedMessagePart: String,
        ) {
            val error = assertThrows(HybridModelException::class.java) { withTable(table) }
            val message = error.message.orEmpty()
            assertTrue(message.contains(expectedMessagePart), "message '$message' should contain '$expectedMessagePart'")
        }

        assertEquals(rows, withTable(LengthBiasTable(listOf(30.0, 60.0), rows)).nap?.stageLogBiasByLength?.bias)
        assertEquals(
            1,
            withTable(LengthBiasTable(listOf(0.5), rows.take(1)))
                .nap
                ?.stageLogBiasByLength
                ?.lengthsMin
                ?.size,
        )
        val both =
            assertThrows(HybridModelException::class.java) {
                model.copy(nap = nap.copy(stageLogBias = List(4) { 0.0 }, stageLogBiasByLength = LengthBiasTable(listOf(30.0, 60.0), rows)))
            }
        assertTrue(both.message.orEmpty().contains("nap.stageLogBias and nap.stageLogBiasByLength are mutually exclusive"), both.message)
        assertRejected(LengthBiasTable(emptyList(), emptyList()), "nap.stageLogBiasByLength.lengthsMin needs at least one anchor")
        assertRejected(LengthBiasTable(listOf(0.0, 60.0), rows), "nap.stageLogBiasByLength.lengthsMin entries must be positive and finite")
        assertRejected(LengthBiasTable(listOf(-30.0, 60.0), rows), "positive and finite")
        assertRejected(LengthBiasTable(listOf(30.0, Double.NaN), rows), "positive and finite")
        assertRejected(LengthBiasTable(listOf(30.0, Double.POSITIVE_INFINITY), rows), "positive and finite")
        assertRejected(LengthBiasTable(listOf(60.0, 30.0), rows), "nap.stageLogBiasByLength.lengthsMin must be strictly increasing")
        assertRejected(LengthBiasTable(listOf(30.0, 30.0), rows), "strictly increasing")
        val anchors = listOf(30.0, 60.0)
        assertRejected(LengthBiasTable(anchors + 90.0, rows), "nap.stageLogBiasByLength.bias needs one row per anchor length (3), has 2")
        assertRejected(LengthBiasTable(anchors, listOf(rows[0], listOf(0.1, 0.2, 0.3))), "nap.stageLogBiasByLength.bias[1] must have 4")
        assertRejected(LengthBiasTable(anchors, listOf(rows[0], listOf(0.0, Double.NaN, 0.0, 0.0))), "bias[1] entries must be finite")
        // Through JSON as well; a malformed table is a malformed bundle.
        val bundleTree = Json.parseToJsonElement(model.copy(nap = nap).toJson()).jsonObject

        fun withNapKey(value: JsonElement): String {
            val napObject = checkNotNull(bundleTree["nap"]).jsonObject
            return JsonObject(bundleTree + ("nap" to JsonObject(napObject + ("stageLogBiasByLength" to value)))).toString()
        }

        fun tableJson(row: List<Double>): JsonObject =
            JsonObject(
                mapOf(
                    "lengthsMin" to JsonArray(listOf(JsonPrimitive(30.0))),
                    "bias" to JsonArray(listOf(JsonArray(row.map { JsonPrimitive(it) }))),
                ),
            )

        assertInvalid(withNapKey(tableJson(listOf(0.0, 0.0, 0.0))), "nap.stageLogBiasByLength.bias[0] must have 4")
        assertInvalid(withNapKey(JsonPrimitive("high")), "malformed")
        val parsed = HybridModel.parse(withNapKey(tableJson(rows[1])))
        assertEquals(LengthBiasTable(listOf(30.0), listOf(rows[1])), parsed.nap?.stageLogBiasByLength)
    }

    @Test
    fun `an incompatible nap section is a valid bundle whose naps cannot use it`() {
        val renamed = HybridFeatureExtractor.FEATURE_NAMES.toMutableList().also { it[3] = "elsewhere" }
        val bundle = model.copy(nap = NapModels.from(MinimalBundles.logreg(featureNames = renamed)))
        assertNull(bundle.compatibilityProblem())
        assertTrue(bundle.napCompatibilityProblem().orEmpty().contains("index 3"), bundle.napCompatibilityProblem())
        assertTrue(
            model
                .copy(nap = NapModels.from(model).copy(featureSetVersion = 9))
                .napCompatibilityProblem()
                .orEmpty()
                .contains("9"),
        )
    }
}
