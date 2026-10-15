package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.data.local.db.InputChange
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * The fingerprint of a night's estimation input as [NightInputReader] read it from Health Connect, stored with every
 * output (`estimated_nights.inputFingerprint`) so that a later sync can tell whether the input changed after the write
 * (heart rate that arrived late, see [NewNightsSync]) without keeping any sample.
 *
 * Format: `1|hr:<count>:<sha256>|steps:<count>:<sha256>|spo2:<part>|rr:<part>|hrv:<part>`, one part per channel in this
 * order, each the number of samples and the lower-case SHA-256 hex of the samples sorted canonically, or `-` for an
 * optional channel that was not read ([InputSelection]). The samples are hashed as big-endian longs: heart rate
 * `(timeMs, bpm bits)` sorted by time then value, steps `(startMs, endMs, count)` sorted by all three, SpO2,
 * respiratory rate and HRV `(timeMs, value bits)` sorted by time then value. Sorting makes the fingerprint independent
 * of the order Health Connect returns records with equal start times in.
 *
 * Deliberately not part of it: the session boundaries, zone offsets and forced-awake gaps (a change of the source
 * sessions is detected by the pipeline itself and recomputes the night), and the baseline nadir, which comes from the
 * ledger rather than from Health Connect.
 */
object InputFingerprint {
    /** Version of the format (the first field). */
    const val FORMAT_VERSION: String = "1"

    private const val HEART_RATE = "hr"
    private const val NOT_READ = "-"

    /** The fingerprint of [input], read with [selection] (which optional channels were read). */
    fun of(
        input: NightInput,
        selection: InputSelection,
    ): String {
        val heartRate = input.heartRate.map { longArrayOf(it.timeMs, it.bpm.toRawBits()) }
        val steps = input.steps.map { longArrayOf(it.startMs, it.endMs, it.count) }
        val spo2 = input.spo2.map { longArrayOf(it.timeMs, it.percent.toRawBits()) }
        val respiratory = input.respiratoryRate.map { longArrayOf(it.timeMs, it.rate.toRawBits()) }
        val hrv = input.hrv.map { longArrayOf(it.timeMs, it.rmssdMs.toRawBits()) }
        return listOf(
            FORMAT_VERSION,
            "$HEART_RATE:${part(heartRate)}",
            "steps:${part(steps)}",
            "spo2:${if (selection.spo2) part(spo2) else NOT_READ}",
            "rr:${if (selection.respiratoryRate) part(respiratory) else NOT_READ}",
            "hrv:${if (selection.hrv) part(hrv) else NOT_READ}",
        ).joinToString("|")
    }

    /**
     * What changed between the input of the previous write ([previous], null when no fingerprint was stored) and the
     * current one ([current]), with the heart-rate sample counts of the estimates then ([hrBefore]) and now ([hrAfter]).
     * A higher count is [InputChange.MORE_HEART_RATE]; another change of the heart-rate part (or no
     * stored fingerprint) is [InputChange.HEART_RATE]; anything else is [InputChange.OTHER_DATA].
     */
    fun changeOf(
        previous: String?,
        current: String,
        hrBefore: Int,
        hrAfter: Int,
    ): InputChange {
        val heartRateChanged = previous == null || channel(previous, HEART_RATE) != channel(current, HEART_RATE)
        return when {
            hrAfter > hrBefore -> InputChange.MORE_HEART_RATE
            heartRateChanged -> InputChange.HEART_RATE
            else -> InputChange.OTHER_DATA
        }
    }

    /** The `<count>:<sha256>` part of one channel. */
    private fun part(samples: List<LongArray>): String {
        val sorted = samples.sortedWith(SAMPLE_ORDER)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out -> sorted.forEach { sample -> sample.forEach(out::writeLong) } }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
        return "${samples.size}:" + digest.joinToString("") { "%02x".format(it) }
    }

    /** The part of [channel] in [fingerprint], or null. */
    private fun channel(
        fingerprint: String,
        channel: String,
    ): String? = fingerprint.split('|').firstOrNull { it.startsWith("$channel:") }

    /** Lexicographic order of samples of the same channel (all have the same length). */
    private val SAMPLE_ORDER: Comparator<LongArray> =
        Comparator { a, b ->
            a.indices.firstNotNullOfOrNull { i -> a[i].compareTo(b[i]).takeIf { it != 0 } } ?: 0
        }
}
