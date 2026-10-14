package io.github.alien2003.pulsestages.e2e.shadow

import androidx.health.connect.client.records.SleepSessionRecord
import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.params.NapOutputPolicy
import io.github.alien2003.pulsestages.data.hc.EstimatedSessionWrite
import io.github.alien2003.pulsestages.data.hc.pipeline.SessionText
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.e2e.safety.sha256
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json as KJson

/**
 * The invariants every session the pipeline asks to write must satisfy, checked independently of the app's own
 * validation (StageMapping.validate):
 * - clientRecordId `ps:` + 64 hex digits, equal to `ps:` + sha256 of the sorted source uuids of the notes joined by ",";
 * - clientRecordVersion = ALGO_VERSION · 1000 + n with 0 ≤ n < 1000;
 * - at least one stage; stages sorted, contiguous (no gap, no overlap), exactly covering the session, each at least one
 *   epoch long (one stage when the session is shorter than an epoch), only SleepSessionRecord.STAGE_TYPE_* values the
 *   app writes; for a nap with the SLEEP_WAKE output only AWAKE / SLEEPING, otherwise no SLEEPING; with awakeAsLight
 *   no AWAKE;
 * - notes: a JSON object with exactly the keys algo, mode, version, paramsHash, coverage, confidence, sourceUuids,
 *   hrSamples, calibrated (in that order) and plausible values;
 * - title: non-blank, at most 1000 characters, the template of the settings (or the localized default) with its
 *   placeholders replaced;
 * - zone offsets within ±18 h, start before end.
 */
object WriteInvariants {
    private val CLIENT_RECORD_ID = Regex("^ps:[0-9a-f]{64}$")
    private val PARAMS_HASH = Regex("^[0-9a-f]{64}$")
    private val NOTE_KEYS =
        listOf("algo", "mode", "version", "paramsHash", "coverage", "confidence", "sourceUuids", "hrSamples", "calibrated")
    private val STAGE_TYPES =
        setOf(
            SleepSessionRecord.STAGE_TYPE_UNKNOWN,
            SleepSessionRecord.STAGE_TYPE_AWAKE,
            SleepSessionRecord.STAGE_TYPE_LIGHT,
            SleepSessionRecord.STAGE_TYPE_DEEP,
            SleepSessionRecord.STAGE_TYPE_REM,
        )
    private val SLEEP_WAKE_TYPES = setOf(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_SLEEPING)
    private const val VERSION_FACTOR = 1000L
    private const val MAX_OFFSET_SEC = 18 * 60 * 60
    private const val SECOND_MS = 1000L

    /** Problems of [write] under [settings] with the localized default title [defaultTitle]; empty when it is valid. */
    fun problems(
        write: EstimatedSessionWrite,
        settings: AppSettings,
        defaultTitle: String,
    ): List<String> {
        val problems = mutableListOf<String>()
        val notes = parseNotes(write.notes, problems)
        val mode =
            notes
                ?.get("mode")
                ?.jsonPrimitive
                ?.content
                ?.let { runCatching { SessionMode.valueOf(it) }.getOrNull() }
        checkIdentity(write, notes, problems)
        checkStages(write, settings, mode, problems)
        checkTitle(write, settings, defaultTitle, notes, problems)
        if (write.startMs >= write.endMs) problems += "session start ${write.startMs} is not before its end ${write.endMs}"
        if (kotlin.math.abs(write.startZoneOffsetSec) > MAX_OFFSET_SEC || kotlin.math.abs(write.endZoneOffsetSec) > MAX_OFFSET_SEC) {
            problems += "zone offsets ${write.startZoneOffsetSec}/${write.endZoneOffsetSec} s are out of range"
        }
        return problems
    }

    private fun checkIdentity(
        write: EstimatedSessionWrite,
        notes: JsonObject?,
        problems: MutableList<String>,
    ) {
        if (!CLIENT_RECORD_ID.matches(write.clientRecordId)) problems += "clientRecordId ${write.clientRecordId} is not ps: + sha256"
        val uuids =
            notes
                ?.get("sourceUuids")
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
                .orEmpty()
        if (uuids.isNotEmpty() && write.clientRecordId != "ps:" + sha256(uuids.distinct().sorted().joinToString(","))) {
            problems += "clientRecordId is not ps: + sha256 of the source uuids listed in the notes"
        }
        val version = write.clientRecordVersion
        if (version / VERSION_FACTOR != ALGO_VERSION.toLong() || version % VERSION_FACTOR !in 0 until VERSION_FACTOR) {
            problems += "clientRecordVersion $version is not ALGO_VERSION ($ALGO_VERSION) * 1000 + n"
        }
    }

    private fun checkStages(
        write: EstimatedSessionWrite,
        settings: AppSettings,
        mode: SessionMode?,
        problems: MutableList<String>,
    ) {
        if (write.stages.isEmpty()) {
            problems += "no stages"
            return
        }
        checkBoundaries(write, settings.algo.preprocess.epochSec * SECOND_MS, problems)
        val types = write.stages.mapTo(HashSet()) { it.type }
        val sleepWake = mode == SessionMode.NAP && settings.algo.napMode.outputPolicy == NapOutputPolicy.SLEEP_WAKE
        val allowed = if (sleepWake) SLEEP_WAKE_TYPES else STAGE_TYPES
        (types - allowed).forEach { problems += "stage type $it is not allowed here (sleep/wake output: $sleepWake)" }
        if (settings.write.awakeAsLight &&
            SleepSessionRecord.STAGE_TYPE_AWAKE in types
        ) {
            problems += "AWAKE written although awakeAsLight is on"
        }
    }

    /** Sorted, contiguous, exactly covering the session, every stage at least one epoch long. */
    private fun checkBoundaries(
        write: EstimatedSessionWrite,
        epochMs: Long,
        problems: MutableList<String>,
    ) {
        val stages = write.stages
        val shorterThanEpoch = write.endMs - write.startMs < epochMs
        if (stages.first().startMs != write.startMs) problems += "the first stage does not start at the session start"
        if (stages.last().endMs != write.endMs) problems += "the last stage does not end at the session end"
        if (shorterThanEpoch && stages.size != 1) problems += "a session shorter than an epoch has ${stages.size} stages"
        stages.zipWithNext().forEachIndexed { index, (a, b) ->
            if (b.startMs < a.startMs) problems += "stages $index and ${index + 1} are not sorted"
            if (b.startMs != a.endMs) problems += "stages $index and ${index + 1} are not contiguous"
        }
        stages.forEachIndexed { index, stage ->
            if (stage.startMs < write.startMs || stage.endMs > write.endMs) problems += "stage $index lies outside the session"
            if (!shorterThanEpoch && stage.endMs - stage.startMs < epochMs) problems += "stage $index is shorter than one epoch"
            if (stage.endMs <= stage.startMs) problems += "stage $index is empty"
        }
    }

    private fun parseNotes(
        text: String,
        problems: MutableList<String>,
    ): JsonObject? {
        val notes = runCatching { KJson.parseToJsonElement(text).jsonObject }.getOrNull()
        if (notes == null) {
            problems += "notes are not a JSON object"
            return null
        }
        if (notes.keys.toList() != NOTE_KEYS) problems += "notes keys ${notes.keys} are not $NOTE_KEYS"
        checkNoteValues(notes, problems)
        return notes
    }

    private fun checkNoteValues(
        notes: JsonObject,
        problems: MutableList<String>,
    ) {
        val algo = notes["algo"]?.jsonPrimitive?.content
        if (EstimatorType.entries.none { it.name == algo }) problems += "notes algo $algo is no estimator"
        if (notes["mode"]?.jsonPrimitive?.content !in SessionMode.entries.map { it.name }) problems += "notes mode is no session mode"
        if (notes["version"]?.jsonPrimitive?.intOrNull != ALGO_VERSION) problems += "notes version is not ALGO_VERSION"
        if (!PARAMS_HASH.matches(notes["paramsHash"]?.jsonPrimitive?.content.orEmpty())) problems += "notes paramsHash is not 64 hex digits"
        listOf("coverage", "confidence").forEach { key ->
            val value = notes[key]?.jsonPrimitive?.doubleOrNull
            if (value == null || value !in 0.0..1.0) problems += "notes $key $value is not in [0, 1]"
        }
        val uuids = runCatching { notes["sourceUuids"]?.jsonArray }.getOrNull()
        if (uuids.isNullOrEmpty() || uuids.any { (it as? JsonPrimitive)?.isString != true || it.content.isBlank() }) {
            problems += "notes sourceUuids is not a non-empty list of ids"
        }
        if ((notes["hrSamples"]?.jsonPrimitive?.intOrNull ?: -1) < 0) problems += "notes hrSamples is not a count"
        if (notes["calibrated"]?.jsonPrimitive?.booleanOrNull == null) problems += "notes calibrated is not a boolean"
    }

    private fun checkTitle(
        write: EstimatedSessionWrite,
        settings: AppSettings,
        defaultTitle: String,
        notes: JsonObject?,
        problems: MutableList<String>,
    ) {
        val title = write.title
        if (title.isBlank() || title.length > SessionText.MAX_TITLE_LENGTH) problems += "title is blank or longer than 1000 characters"
        if (listOf("{estimator}", "{version}", "{confidence}").any { it in title }) problems += "title still has a placeholder: $title"
        val estimator =
            notes
                ?.get("algo")
                ?.jsonPrimitive
                ?.content
                ?.let { name -> EstimatorType.entries.firstOrNull { it.name == name } } ?: return
        val confidence = notes["confidence"]?.jsonPrimitive?.doubleOrNull ?: return
        val expected = SessionText.title(settings.write.titleTemplate, defaultTitle, estimator, ALGO_VERSION, confidence)
        // The title is built from the unrounded confidence, the notes keep three decimals: the percent may differ by one.
        val digitsOnly = { s: String -> s.replace(Regex("[0-9]+"), "#") }
        if (title != expected &&
            digitsOnly(title) != digitsOnly(expected)
        ) {
            problems += "title \"$title\" is not the template's \"$expected\""
        }
    }
}
