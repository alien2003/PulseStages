package io.github.alien2003.pulsestages.cli

import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.AlgoParamsValidator
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path

/**
 * Parameter files: `--params` holds a (possibly partial) AlgoParams JSON object, missing fields take their defaults;
 * `--param-grid` holds a JSON array of partial AlgoParams objects ("patches"), each deep-merged over the base
 * parameters (objects merge key by key, any other value replaces). Unknown keys and values outside the ranges of
 * [AlgoParamsValidator] are errors (exit 65, naming the file or grid entry and every offending field).
 */
internal object ParamsFiles {
    /** Strict JSON: unknown keys fail, every field is written. */
    val JSON: Json =
        Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
            explicitNulls = true
        }

    /** The parameters in [path], or the defaults when [path] is null. */
    fun load(path: Path?): AlgoParams {
        if (path == null) return AlgoParams()
        return decode(CliFiles.readText(path), path.toString())
    }

    /** The patches of a grid file (a non-empty JSON array of objects). */
    fun grid(path: Path): List<JsonObject> {
        val element =
            try {
                JSON.parseToJsonElement(CliFiles.readText(path))
            } catch (e: SerializationException) {
                throw CliException("$path: malformed JSON: ${e.message}", ExitCodes.DATA_ERROR, e)
            }
        val array = element as? JsonArray
        val patches = array?.map { it as? JsonObject }
        if (patches == null || patches.isEmpty() || patches.any { it == null }) {
            throw CliException("$path: a parameter grid must be a non-empty JSON array of objects", ExitCodes.DATA_ERROR)
        }
        return patches.filterNotNull()
    }

    /** [base] with [patch] deep-merged over it; [source] names the patch in error messages. */
    fun merge(
        base: AlgoParams,
        patch: JsonObject,
        source: String,
    ): AlgoParams {
        val tree = JSON.encodeToJsonElement(AlgoParams.serializer(), base).jsonObject
        return decode(deepMerge(tree, patch).toString(), source)
    }

    /** [patch]'s keys override [base]'s; nested objects are merged recursively. */
    fun deepMerge(
        base: JsonObject,
        patch: JsonObject,
    ): JsonObject {
        val merged = base.toMutableMap()
        for ((key, value) in patch) {
            val current = merged[key]
            merged[key] = if (current is JsonObject && value is JsonObject) deepMerge(current, value) else value
        }
        return JsonObject(merged)
    }

    /** Decodes and validates ([AlgoParamsValidator]); every problem is reported in one DATA_ERROR naming [source]. */
    private fun decode(
        text: String,
        source: String,
    ): AlgoParams {
        val params =
            try {
                JSON.decodeFromString(AlgoParams.serializer(), text)
            } catch (e: IllegalArgumentException) {
                throw CliException("$source: invalid parameters: ${e.message}", ExitCodes.DATA_ERROR, e)
            }
        val problems = AlgoParamsValidator.problems(params)
        if (problems.isNotEmpty()) {
            throw CliException("$source: invalid parameters: ${problems.joinToString("; ")}", ExitCodes.DATA_ERROR)
        }
        return params
    }
}
