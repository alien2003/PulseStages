package io.github.alien2003.pulsestages.cli

import io.github.alien2003.pulsestages.algo.metrics.ConfusionMatrix
import io.github.alien2003.pulsestages.algo.metrics.StageMinutes
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The metrics block of one parameter set:
 * - `index`, `patch` (grid runs only), `estimator` (configured), `params` (the full effective AlgoParams);
 * - `nights`: total / evaluated / skipped and `skipReasons` (reason → count); `skipped`: id, subject and reason;
 * - `estimatorsUsed` (actual estimator → nights) and `warnings` (warning code → nights);
 * - `pooled`: confusion matrix (rows = truth, columns = prediction, `labels` order), kappa, accuracy, macroF1, scored
 *   and skipped 30-s chunks, `perStage` precision / recall / F1 / support / predicted;
 * - `meanNightKappa`, `medianNightKappa` over nights with a defined kappa;
 * - `perSubject`: `meanKappa` / `medianKappa` of the per-subject pooled kappas and the per-subject values;
 * - `minutesError`: mean absolute minutes error of DEEP, REM and AWAKE over the evaluated nights;
 * - `perNight`: id, subject, variant, estimator, kappa, accuracy, macroF1, coverage, confidence, truth and predicted
 *   minutes per stage, minute errors and warning codes.
 * Undefined numbers (e.g. kappa of a night with a single class) are null.
 */
internal object MetricsJson {
    /** The block of [results] (one per night) computed with [params]; [gridEntry] = (index, patch) in grid runs. */
    fun block(
        results: List<NightResult>,
        params: AlgoParams,
        gridEntry: Pair<Int, JsonObject>?,
    ): JsonObject {
        val scored = results.filterIsInstance<NightResult.Scored>()
        val skipped = results.filterIsInstance<NightResult.Skipped>()
        val pooled = scored.fold(ConfusionMatrix.EMPTY) { acc, r -> acc + r.score.confusion }
        val nightKappas = scored.map { it.score.confusion.kappa }.filter { it.isFinite() }
        return buildJsonObject {
            gridEntry?.let { (index, patch) ->
                put("index", index)
                put("patch", patch)
            }
            put("estimator", params.estimator.name)
            put("params", ParamsFiles.JSON.encodeToJsonElement(AlgoParams.serializer(), params))
            put("nights", nights(results.size, scored.size, skipped))
            put("skipped", JsonArray(skipped.map { skippedNight(it) }))
            put("estimatorsUsed", counts(scored.map { it.estimator.name }))
            put("warnings", counts(scored.flatMap { r -> r.warnings.map { it.name } }))
            put("pooled", confusion(pooled))
            putNumber("meanNightKappa", nightKappas.averageOrNaN())
            putNumber("medianNightKappa", median(nightKappas))
            put("perSubject", perSubject(scored))
            put("minutesError", minutesError(scored))
            put("perNight", JsonArray(scored.map { night(it) }))
        }
    }

    private fun nights(
        total: Int,
        evaluated: Int,
        skipped: List<NightResult.Skipped>,
    ): JsonObject =
        buildJsonObject {
            put("total", total)
            put("evaluated", evaluated)
            put("skipped", skipped.size)
            put("skipReasons", counts(skipped.map { it.reason }))
        }

    private fun skippedNight(result: NightResult.Skipped): JsonObject =
        buildJsonObject {
            put("id", result.record.id)
            put("subject", result.record.subject)
            put("reason", result.reason)
        }

    private fun counts(keys: List<String>): JsonObject =
        buildJsonObject {
            keys
                .groupingBy { it }
                .eachCount()
                .toSortedMap()
                .forEach { (key, count) -> put(key, count) }
        }

    /** Confusion matrix and the derived scores. */
    fun confusion(matrix: ConfusionMatrix): JsonObject =
        buildJsonObject {
            put("labels", JsonArray(SleepStage.SCORED.map { JsonPrimitive(it.name) }))
            put("confusion", JsonArray(matrix.toArray().map { row -> JsonArray(row.map { JsonPrimitive(it) }) }))
            putNumber("kappa", matrix.kappa)
            putNumber("accuracy", matrix.accuracy)
            putNumber("macroF1", matrix.macroF1)
            put("scoredChunks", matrix.total)
            put("skippedChunks", matrix.skipped)
            put(
                "perStage",
                buildJsonObject {
                    for (stage in SleepStage.SCORED) {
                        val score = matrix.stage(stage)
                        put(
                            stage.name,
                            buildJsonObject {
                                putNumber("precision", score.precision)
                                putNumber("recall", score.recall)
                                putNumber("f1", score.f1)
                                put("support", score.support)
                                put("predicted", score.predicted)
                            },
                        )
                    }
                },
            )
        }

    private fun perSubject(scored: List<NightResult.Scored>): JsonObject {
        val subjects =
            scored.groupBy { it.record.subject }.toSortedMap().map { (subject, nights) ->
                val pooled = nights.fold(ConfusionMatrix.EMPTY) { acc, r -> acc + r.score.confusion }
                Triple(subject, nights.size, pooled)
            }
        val kappas = subjects.map { it.third.kappa }.filter { it.isFinite() }
        return buildJsonObject {
            put("subjects", subjects.size)
            putNumber("meanKappa", kappas.averageOrNaN())
            putNumber("medianKappa", median(kappas))
            put(
                "values",
                buildJsonArray {
                    for ((subject, nights, pooled) in subjects) {
                        add(
                            buildJsonObject {
                                put("subject", subject)
                                put("nights", nights)
                                putNumber("kappa", pooled.kappa)
                                putNumber("accuracy", pooled.accuracy)
                            },
                        )
                    }
                },
            )
        }
    }

    private fun minutesError(scored: List<NightResult.Scored>): JsonObject =
        buildJsonObject {
            putNumber("deep", scored.map { it.score.minutesError.deep }.averageOrNaN())
            putNumber("rem", scored.map { it.score.minutesError.rem }.averageOrNaN())
            putNumber("awake", scored.map { it.score.minutesError.awake }.averageOrNaN())
        }

    private fun night(result: NightResult.Scored): JsonObject {
        val confusion = result.score.confusion
        return buildJsonObject {
            put("id", result.record.id)
            put("subject", result.record.subject)
            put("variant", result.record.variant?.let { JsonPrimitive(it) } ?: JsonNull)
            put("estimator", result.estimator.name)
            putNumber("kappa", confusion.kappa)
            putNumber("accuracy", confusion.accuracy)
            putNumber("macroF1", confusion.macroF1)
            putNumber("coverage", result.coverage)
            putNumber("confidence", result.confidence)
            put("scoredChunks", confusion.total)
            put("truthMinutes", minutes(result.score.truthMinutes))
            put("predictedMinutes", minutes(result.score.predictedMinutes))
            put(
                "minutesError",
                buildJsonObject {
                    putNumber("deep", result.score.minutesError.deep)
                    putNumber("rem", result.score.minutesError.rem)
                    putNumber("awake", result.score.minutesError.awake)
                },
            )
            put("warnings", JsonArray(result.warnings.map { JsonPrimitive(it.name) }))
        }
    }

    private fun minutes(values: StageMinutes): JsonObject =
        buildJsonObject { for (stage in SleepStage.SCORED) putNumber(stage.name, values[stage]) }

    private fun JsonObjectBuilder.putNumber(
        key: String,
        value: Double,
    ) {
        put(key, number(value))
    }

    /** [value] as JSON, null when it is NaN or infinite. */
    fun number(value: Double): JsonElement = if (value.isFinite()) JsonPrimitive(value) else JsonNull

    private fun List<Double>.averageOrNaN(): Double = if (isEmpty()) Double.NaN else average()

    /** Median (mean of the middle two for an even count); NaN when empty. */
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
