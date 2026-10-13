package io.github.alien2003.pulsestages.cli

import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.algo.model.HrvSample
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.RespiratoryRateSample
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.Spo2Sample
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.algo.model.StepsSample
import io.github.alien2003.pulsestages.algo.model.TimeSpan
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * One night of a night file (docs/INTERCHANGE.md §1).
 *
 * @property labels reference hypnogram (empty when the file has none).
 */
internal data class NightRecord(
    val id: String,
    val subject: String,
    val variant: String?,
    val input: NightInput,
    val labels: List<StageInterval>,
)

/**
 * Reading and writing of night files: a `*.night.json` file holds one JSON object, a `*.jsonl` file one object per
 * line; a directory is read as all its `*.night.json` and `*.jsonl` files in name order. Fields: id, subject (default:
 * id), variant, sessionStartMs, sessionEndMs, hr `[[t, bpm]]`, steps `[[start, end, count]]`, spo2 `[[t, %]]`, and the
 * optional extensions hrv `[[t, rmssdMs]]`, respiratoryRate `[[t, rate]]`, forcedAwake `[[start, end]]`,
 * baselineNadirBpm and labels `[[start, end, "STAGE"]]`. Times are epoch milliseconds (integers or floats).
 */
internal object NightFiles {
    private const val NIGHT_SUFFIX = ".night.json"
    private const val JSONL_SUFFIX = ".jsonl"
    private const val TRIPLE = 3

    /** All nights under [path] (file or directory), in file order; ids must be unique. */
    fun read(path: Path): List<NightRecord> {
        val files =
            if (path.isDirectory()) {
                listFiles(path).filter { it.name.endsWith(NIGHT_SUFFIX) || it.name.endsWith(JSONL_SUFFIX) }.sortedBy { it.name }
            } else {
                listOf(path)
            }
        if (files.isEmpty()) throw CliException("no *$NIGHT_SUFFIX or *$JSONL_SUFFIX files in $path", ExitCodes.NO_INPUT)
        val nights = files.flatMap { readFile(it) }
        val duplicate =
            nights
                .groupingBy { it.id }
                .eachCount()
                .entries
                .firstOrNull { it.value > 1 }
        if (duplicate != null) throw CliException("night id '${duplicate.key}' occurs ${duplicate.value} times", ExitCodes.DATA_ERROR)
        return nights
    }

    private fun listFiles(directory: Path): List<Path> =
        try {
            Files.list(directory).use { stream -> stream.toList() }
        } catch (e: IOException) {
            throw CliException("cannot list $directory: ${e.message}", ExitCodes.NO_INPUT, e)
        }

    private fun readFile(file: Path): List<NightRecord> {
        val text = CliFiles.readText(file)
        return if (file.name.endsWith(JSONL_SUFFIX)) {
            text
                .lineSequence()
                .withIndex()
                .filter { it.value.isNotBlank() }
                .map { (i, line) -> parse(line, "$file:${i + 1}") }
                .toList()
        } else {
            listOf(parse(text, file.toString()))
        }
    }

    /** Parses one night object from [text]; [source] names it in error messages. */
    fun parse(
        text: String,
        source: String,
    ): NightRecord =
        try {
            val element = Json.parseToJsonElement(text)
            fromJson(element as? JsonObject ?: fail("a night must be a JSON object"))
        } catch (e: SerializationException) {
            throw CliException("$source: malformed JSON: ${e.message}", ExitCodes.DATA_ERROR, e)
        } catch (e: IllegalArgumentException) {
            throw CliException("$source: ${e.message}", ExitCodes.DATA_ERROR, e)
        }

    private fun fromJson(json: JsonObject): NightRecord {
        val id = string(json, "id") ?: fail("missing 'id'")
        val start = long(json["sessionStartMs"] ?: fail("missing 'sessionStartMs'"), "sessionStartMs")
        val end = long(json["sessionEndMs"] ?: fail("missing 'sessionEndMs'"), "sessionEndMs")
        val input =
            NightInput(
                sessionStartMs = start,
                sessionEndMs = end,
                heartRate = rows(json, "hr", 2).map { HrSample(long(it[0], "hr time"), double(it[1], "hr bpm")) },
                steps =
                    rows(
                        json,
                        "steps",
                        TRIPLE,
                    ).map { StepsSample(long(it[0], "steps start"), long(it[1], "steps end"), long(it[2], "steps")) },
                spo2 = rows(json, "spo2", 2).map { Spo2Sample(long(it[0], "spo2 time"), double(it[1], "spo2")) },
                respiratoryRate =
                    rows(
                        json,
                        "respiratoryRate",
                        2,
                    ).map { RespiratoryRateSample(long(it[0], "rr time"), double(it[1], "rr")) },
                hrv = rows(json, "hrv", 2).map { HrvSample(long(it[0], "hrv time"), double(it[1], "rmssd")) },
                forcedAwake =
                    rows(
                        json,
                        "forcedAwake",
                        2,
                    ).map { TimeSpan(long(it[0], "forcedAwake start"), long(it[1], "forcedAwake end")) },
                baselineNadirBpm = json["baselineNadirBpm"]?.takeUnless { it is JsonNull }?.let { double(it, "baselineNadirBpm") },
            )
        val labels =
            rows(json, "labels", TRIPLE).map { row ->
                val name = (row[2] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("label stage must be a string")
                val stage = SleepStage.entries.firstOrNull { it.name == name } ?: fail("unknown stage '$name'")
                StageInterval(long(row[0], "label start"), long(row[1], "label end"), stage)
            }
        return NightRecord(id, string(json, "subject") ?: id, string(json, "variant"), input, labels)
    }

    private fun string(
        json: JsonObject,
        key: String,
    ): String? = (json[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun rows(
        json: JsonObject,
        key: String,
        width: Int,
    ): List<List<JsonElement>> {
        val value = json[key]
        if (value == null || value is JsonNull) return emptyList()
        val array = value as? JsonArray ?: fail("'$key' must be an array")
        return array.map { row ->
            val cells = row as? JsonArray ?: fail("'$key' entries must be arrays")
            if (cells.size != width) fail("'$key' entries must have $width elements, found ${cells.size}")
            cells
        }
    }

    private fun long(
        element: JsonElement,
        what: String,
    ): Long {
        val primitive = element as? JsonPrimitive
        val value = primitive?.longOrNull ?: primitive?.doubleOrNull?.takeIf { it.isFinite() }?.toLong()
        return value ?: fail("$what must be a number, was $element")
    }

    private fun double(
        element: JsonElement,
        what: String,
    ): Double = (element as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: fail("$what must be a finite number, was $element")

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

    /** The JSON object of [record] (the format read by [parse]). */
    fun toJson(record: NightRecord): JsonObject =
        buildJsonObject {
            put("id", record.id)
            put("subject", record.subject)
            record.variant?.let { put("variant", it) }
            put("sessionStartMs", record.input.sessionStartMs)
            put("sessionEndMs", record.input.sessionEndMs)
            put("hr", JsonArray(record.input.heartRate.map { row(JsonPrimitive(it.timeMs), JsonPrimitive(it.bpm)) }))
            put(
                "steps",
                JsonArray(record.input.steps.map { row(JsonPrimitive(it.startMs), JsonPrimitive(it.endMs), JsonPrimitive(it.count)) }),
            )
            optional("spo2", record.input.spo2.map { row(JsonPrimitive(it.timeMs), JsonPrimitive(it.percent)) })
            optional("respiratoryRate", record.input.respiratoryRate.map { row(JsonPrimitive(it.timeMs), JsonPrimitive(it.rate)) })
            optional("hrv", record.input.hrv.map { row(JsonPrimitive(it.timeMs), JsonPrimitive(it.rmssdMs)) })
            optional("forcedAwake", record.input.forcedAwake.map { row(JsonPrimitive(it.startMs), JsonPrimitive(it.endMs)) })
            record.input.baselineNadirBpm?.let { put("baselineNadirBpm", it) }
            put(
                "labels",
                JsonArray(record.labels.map { row(JsonPrimitive(it.startMs), JsonPrimitive(it.endMs), JsonPrimitive(it.stage.name)) }),
            )
        }

    private fun JsonObjectBuilder.optional(
        key: String,
        rows: List<JsonArray>,
    ) {
        if (rows.isNotEmpty()) put(key, JsonArray(rows))
    }

    private fun row(vararg cells: JsonElement): JsonArray = JsonArray(cells.toList())
}
