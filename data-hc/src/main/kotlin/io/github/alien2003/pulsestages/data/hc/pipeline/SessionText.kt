package io.github.alien2003.pulsestages.data.hc.pipeline

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.data.hc.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import kotlin.math.roundToInt

/** The localized default title template (used when `WriteSettings.titleTemplate` is null or blank). */
fun interface DefaultTitleProvider {
    /** The default template, e.g. "Sleep stages from heart rate (estimate)". */
    fun defaultTitleTemplate(): String
}

/** [DefaultTitleProvider] reading `R.string.default_session_title` in the current locale. */
class ResourceDefaultTitleProvider
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : DefaultTitleProvider {
        override fun defaultTitleTemplate(): String = context.getString(R.string.default_session_title)
    }

/**
 * What a written session says about itself: the title (from the template) and the notes (compact JSON). The title
 * template supports `{estimator}` (RULES / HMM / HYBRID), `{version}` (ALGO_VERSION) and `{confidence}` (night
 * confidence in whole percent).
 */
object SessionText {
    /** Health Connect limit for the title. */
    const val MAX_TITLE_LENGTH: Int = 1000

    private const val PERCENT = 100
    private const val DECIMALS = 3

    /**
     * The title for a night: [template] (or [defaultTemplate] when it is null or blank) with the placeholders
     * replaced, trimmed to [MAX_TITLE_LENGTH] characters.
     */
    fun title(
        template: String?,
        defaultTemplate: String,
        estimator: EstimatorType,
        algoVersion: Int,
        confidence: Double,
    ): String {
        val source = template?.takeIf { it.isNotBlank() } ?: defaultTemplate
        val percent = if (confidence.isFinite()) (confidence.coerceIn(0.0, 1.0) * PERCENT).roundToInt() else 0
        return source
            .replace("{estimator}", estimator.name)
            .replace("{version}", algoVersion.toString())
            .replace("{confidence}", percent.toString())
            .take(MAX_TITLE_LENGTH)
    }

    /**
     * Compact JSON notes with the keys in this order:
     * `{"algo":"HYBRID","mode":"NIGHT","version":1,"paramsHash":"…","coverage":0.93,"confidence":0.61,"sourceUuids":[…],
     * "hrSamples":231,"calibrated":false}` (`mode`: NIGHT or NAP). Numbers are rounded to three decimals (half up;
     * non-finite → 0).
     */
    @Suppress("LongParameterList") // One parameter per notes field.
    fun notes(
        estimator: EstimatorType,
        mode: SessionMode,
        algoVersion: Int,
        paramsHash: String,
        coverage: Double,
        confidence: Double,
        sourceUuids: List<String>,
        hrSamples: Int,
        calibrated: Boolean,
    ): String =
        buildJsonObject {
            put("algo", estimator.name)
            put("mode", mode.name)
            put("version", algoVersion)
            put("paramsHash", paramsHash)
            put("coverage", round3(coverage))
            put("confidence", round3(confidence))
            putJsonArray("sourceUuids") { sourceUuids.forEach { add(JsonPrimitive(it)) } }
            put("hrSamples", hrSamples)
            put("calibrated", calibrated)
        }.toString()

    /**
     * The `sourceUuids` listed in notes written by [SessionText.notes], or an empty list when [notes] are missing,
     * malformed or not ours.
     */
    fun sourceUuidsOf(notes: String?): List<String> {
        // Malformed notes (JsonDecodingException, a SerializationException) simply carry no uuids.
        val parsed = if (notes.isNullOrBlank()) null else runCatching { Json.parseToJsonElement(notes) }.getOrNull()
        val uuids = ((parsed as? JsonObject)?.get("sourceUuids") as? JsonArray).orEmpty()
        return uuids.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
    }

    /** [value] rounded half up to three decimals; 0 for NaN / infinity. */
    fun round3(value: Double): Double =
        if (value.isFinite()) BigDecimal.valueOf(value).setScale(DECIMALS, RoundingMode.HALF_UP).toDouble() else 0.0
}
