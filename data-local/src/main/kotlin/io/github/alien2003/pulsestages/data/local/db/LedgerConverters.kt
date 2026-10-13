package io.github.alien2003.pulsestages.data.local.db

import androidx.room.TypeConverter
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.algo.model.WarningCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Compact JSON encodings of the ledger's list columns. Public so that other layers (e.g. notes, exports) can use
 * exactly the stored format.
 */
object LedgerJson {
    /** `["a","b"]`. */
    fun encodeStrings(values: List<String>): String = JsonArray(values.map(::JsonPrimitive)).toString()

    /** Inverse of [encodeStrings]. */
    fun decodeStrings(json: String): List<String> = parseArray(json).map { it.jsonPrimitive.content }

    /** `["LOW_HR_RANGE","DATA_GAPS"]`. */
    fun encodeWarnings(values: List<WarningCode>): String = encodeStrings(values.map { it.name })

    /** Inverse of [encodeWarnings]; names unknown to this version are dropped. */
    fun decodeWarnings(json: String): List<WarningCode> =
        decodeStrings(json).mapNotNull { name -> WarningCode.entries.firstOrNull { it.name == name } }

    /** `[[startMs,endMs,"DEEP"],...]`. */
    fun encodeStages(values: List<StageInterval>): String =
        JsonArray(
            values.map { interval ->
                JsonArray(listOf(JsonPrimitive(interval.startMs), JsonPrimitive(interval.endMs), JsonPrimitive(interval.stage.name)))
            },
        ).toString()

    /** Inverse of [encodeStages]. */
    fun decodeStages(json: String): List<StageInterval> =
        parseArray(json).map { element ->
            val triple = element.jsonArray
            require(triple.size == STAGE_TUPLE_SIZE) { "stage interval must be [startMs,endMs,stage], was $triple" }
            StageInterval(
                startMs = triple[0].jsonPrimitive.long,
                endMs = triple[1].jsonPrimitive.long,
                stage = SleepStage.valueOf(triple[2].jsonPrimitive.content),
            )
        }

    private const val STAGE_TUPLE_SIZE = 3

    private fun parseArray(json: String): JsonArray = Json.parseToJsonElement(json).jsonArray
}

/** Room type converters for the list columns (see [LedgerJson]). Enums use Room's built-in name mapping. */
class LedgerConverters {
    /** List of strings → JSON array. */
    @TypeConverter
    fun fromStrings(values: List<String>): String = LedgerJson.encodeStrings(values)

    /** JSON array → list of strings. */
    @TypeConverter
    fun toStrings(json: String): List<String> = LedgerJson.decodeStrings(json)

    /** Warnings → JSON array of names. */
    @TypeConverter
    fun fromWarnings(values: List<WarningCode>): String = LedgerJson.encodeWarnings(values)

    /** JSON array of names → warnings. */
    @TypeConverter
    fun toWarnings(json: String): List<WarningCode> = LedgerJson.decodeWarnings(json)

    /** Stage intervals → compact JSON. */
    @TypeConverter
    fun fromStages(values: List<StageInterval>): String = LedgerJson.encodeStages(values)

    /** Compact JSON → stage intervals. */
    @TypeConverter
    fun toStages(json: String): List<StageInterval> = LedgerJson.decodeStages(json)
}
