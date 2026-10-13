package io.github.alien2003.pulsestages.algo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Fingerprints of JSON documents (model bundles, personal models) for the params hash. */
internal object Fingerprints {
    /** SHA-256 (lower-case hex) of [canonical] of [element]. */
    fun of(element: JsonElement): String = ParamsHasher.sha256Hex(canonical(element))

    /** Compact JSON with the keys of every object sorted (by UTF-16 code units), so key order does not matter. */
    fun canonical(element: JsonElement): String = buildString { append(this, element) }

    private fun append(
        out: StringBuilder,
        element: JsonElement,
    ) {
        when (element) {
            is JsonObject -> {
                out.append('{')
                element.keys.sorted().forEachIndexed { i, key ->
                    if (i > 0) out.append(',')
                    out.append(JsonPrimitive(key).toString()).append(':')
                    append(out, element.getValue(key))
                }
                out.append('}')
            }

            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { i, child ->
                    if (i > 0) out.append(',')
                    append(out, child)
                }
                out.append(']')
            }

            is JsonPrimitive -> {
                out.append(element.toString())
            }
        }
    }
}
