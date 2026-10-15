package io.github.alien2003.pulsestages.algo

import io.github.alien2003.pulsestages.algo.night.PreparedNight
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ParamsHasherTest {
    private val json = Json { encodeDefaults = true }

    private class Leaf(
        val path: List<String>,
        val value: JsonPrimitive,
        val descriptor: SerialDescriptor,
    )

    /** Every primitive leaf of [element] with its descriptor, walking the JSON tree and the serial descriptors in parallel. */
    private fun leaves(
        element: JsonElement,
        descriptor: SerialDescriptor,
        path: List<String> = emptyList(),
    ): List<Leaf> =
        when (element) {
            is JsonObject -> {
                element.entries.flatMap { (key, child) ->
                    leaves(child, descriptor.getElementDescriptor(descriptor.getElementIndex(key)), path + key)
                }
            }

            is JsonPrimitive -> {
                listOf(Leaf(path, element, descriptor))
            }

            else -> {
                error("unexpected JSON element at $path: $element")
            }
        }

    private fun mutate(leaf: Leaf): JsonPrimitive {
        val value = leaf.value
        return when {
            // A null nap override ("the night's value"): any value of its type is a change.
            value is JsonNull && leaf.descriptor.kind == SerialKind.ENUM -> {
                JsonPrimitive(leaf.descriptor.getElementName(0))
            }

            value is JsonNull && leaf.descriptor.kind == PrimitiveKind.INT -> {
                JsonPrimitive(1)
            }

            value is JsonNull -> {
                JsonPrimitive(0.5)
            }

            leaf.descriptor.kind == SerialKind.ENUM -> {
                val names = (0 until leaf.descriptor.elementsCount).map { leaf.descriptor.getElementName(it) }
                JsonPrimitive(names.first { it != value.content })
            }

            value.booleanOrNull != null -> {
                JsonPrimitive(!value.content.toBoolean())
            }

            value.content.contains('.') || value.content.contains('E') -> {
                JsonPrimitive(value.content.toDouble() + 0.125)
            }

            else -> {
                JsonPrimitive(value.content.toLong() + 1)
            }
        }
    }

    private fun replace(
        element: JsonElement,
        path: List<String>,
        replacement: JsonElement,
    ): JsonElement {
        if (path.isEmpty()) return replacement
        val obj = element.jsonObject
        val key = path.first()
        return JsonObject(obj.toMutableMap().also { it[key] = replace(checkNotNull(obj[key]), path.drop(1), replacement) })
    }

    @Test
    fun `equal params give equal hashes`() {
        assertEquals(ParamsHasher.hash(AlgoParams()), ParamsHasher.hash(AlgoParams()))
        val copy = json.decodeFromString(AlgoParams.serializer(), json.encodeToString(AlgoParams.serializer(), AlgoParams()))
        assertEquals(ParamsHasher.hash(AlgoParams()), ParamsHasher.hash(copy))
        assertTrue(Regex("[0-9a-f]{64}").matches(ParamsHasher.hash(AlgoParams())))
    }

    @Test
    fun `mutating any leaf of the params JSON changes the hash`() {
        val base = AlgoParams()
        val baseHash = ParamsHasher.hash(base)
        val tree = json.parseToJsonElement(ParamsHasher.canonicalJson(base))
        val leaves = leaves(tree, AlgoParams.serializer().descriptor)

        assertTrue(leaves.size > 100, "expected the whole parameter tree, found ${leaves.size} leaves")
        val hashes = mutableSetOf(baseHash)
        for (leaf in leaves) {
            val mutated = json.decodeFromJsonElement(AlgoParams.serializer(), replace(tree, leaf.path, mutate(leaf)))
            assertNotEquals(base, mutated, "mutation of ${leaf.path} was lost")
            val hash = ParamsHasher.hash(mutated)
            assertNotEquals(baseHash, hash, "hash unchanged after mutating ${leaf.path.joinToString(".")}")
            assertTrue(hashes.add(hash), "hash collision for ${leaf.path}")
        }
    }

    @Test
    fun `fingerprints and extra options change the hash`() {
        val params = AlgoParams()
        val plain = ParamsHasher.hash(params)

        assertEquals(plain, ParamsHasher.hash(params, modelFingerprint = null, personalFingerprint = null, extra = ""))
        assertNotEquals(plain, ParamsHasher.hash(params, modelFingerprint = "abc"))
        assertNotEquals(plain, ParamsHasher.hash(params, personalFingerprint = "abc"))
        assertNotEquals(ParamsHasher.hash(params, modelFingerprint = "abc"), ParamsHasher.hash(params, personalFingerprint = "abc"))
        assertNotEquals(plain, ParamsHasher.hash(params, extra = "awakeAsLight=true"))
        assertEquals(
            ParamsHasher.sha256Hex(ParamsHasher.canonicalJson(params) + "|model=m|personal=none|x"),
            ParamsHasher.hash(params, "m", null, "x"),
        )
    }

    @Test
    fun `canonical json contains every default`() {
        val canonical = ParamsHasher.canonicalJson(AlgoParams())

        assertTrue(canonical.startsWith("{\"estimator\":\"HYBRID\""))
        assertTrue(canonical.contains("\"pseudoPosterior\":0.85"))
        assertTrue(!canonical.contains(": ") && !canonical.contains('\n'), "canonical JSON must be compact")
    }

    @Test
    fun `stager hash includes the fingerprints of its hybrid seam`() {
        val params = AlgoParams()
        val plain = SleepStager().paramsHash(params)
        val withModel =
            SleepStager(
                object : HybridEstimation {
                    override val modelFingerprint = "model-1"
                    override val personalFingerprint = "personal-1"

                    override fun estimate(night: PreparedNight) = HybridAttempt.Unavailable("test")
                },
            )

        assertEquals(ParamsHasher.hash(params), plain)
        assertEquals(ParamsHasher.hash(params, "model-1", "personal-1", "x"), withModel.paramsHash(params, "x"))
        val noPersonal = params.copy(personal = params.personal.copy(enabled = false))
        assertEquals(ParamsHasher.hash(noPersonal, "model-1", null, ""), withModel.paramsHash(noPersonal))
    }
}
