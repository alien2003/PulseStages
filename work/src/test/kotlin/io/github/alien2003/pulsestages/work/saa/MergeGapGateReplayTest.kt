package io.github.alien2003.pulsestages.work.saa

import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OWN
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** System property: the arms to replay, comma-separated (default: all five). */
private const val ARMS_PROPERTY = "pulsestages.mergegap.arms"

/** System property: where the replay report (JSON) is written. */
private const val REPORT_PROPERTY = "pulsestages.mergegap.v3Report"

/** One arm of docs/prereg/prereg-merge-gap.md section 3.1: `sources.mergeGapMin` and `sources.continuationGapMin`. */
private data class Arm(
    val name: String,
    val mergeGapMin: Int,
    val continuationGapMin: Int = 0,
)

private val ARMS: List<Arm> = listOf(Arm("A", 30), Arm("B", 60), Arm("C", 30, 60), Arm("R45", 45), Arm("R90", 90))

/**
 * The timelines of section 9.1: the clock at the start ([startMs]), PulseStages' runs (every [intervalMs] from
 * [firstRunMs]), the night's start ([nightStartMs], its heart-rate pattern), the end of the timeline's last heart-rate
 * write, made then ([lastHeartRateMs], E), the latest session end without a V3.2 fragment ([latestEndMs]) and whether
 * OHealth writes the steps of a walk with the heart rate ([walking]: the sleep-in; 18 and 19 Oct with [Replay.walks]).
 */
private enum class Scenario(
    val label: String,
    val startMs: Long,
    val firstRunMs: Long,
    val intervalMs: Long,
    val nightStartMs: Long,
    val lastHeartRateMs: Long,
    val latestEndMs: Long,
) {
    OCT18("18Oct", time(7, 30), time(7, 36, seconds = 30), 15 * MINUTE_MS, time(4, 21), time(13, 19), time(12, 15)),
    OCT19("19Oct", time(7, 30), time(7, 55), 15 * MINUTE_MS, time(2, 20), time(9, 12), time(8, 3)),
    SLEEP_IN("sleepIn", time(7, 30), time(7, 36, seconds = 30), 15 * MINUTE_MS, time(0, 40), time(11, 35), time(10, 30)),
    HOURLY("hourly", time(7, 30), time(7, 55), HOUR_MS, time(2, 20), time(9, 12), time(8, 3)),
    FRAGMENT("fragment", time(7, 30), time(7, 55), 15 * MINUTE_MS, time(2, 20), time(9, 12), time(8, 3)),
    NAP("nap", time(13, 0), time(13, 5), 15 * MINUTE_MS, time(14, 0), time(15, 41), time(15, 40)),
    ;

    /** Whether OHealth writes one-minute steps in this timeline whatever [Replay.walks] says. */
    val walking: Boolean get() = this == SLEEP_IN
}

/** One replay of section 9.1: its set, scenario, walks (18 and 19 Oct) and, for V3.2, the gap [g] and the delay [d]. */
private data class Replay(
    val set: String,
    val scenario: Scenario,
    val walks: Boolean = false,
    val g: Int? = null,
    val d: Int? = null,
) {
    /** The fragment of V3.2: [08:03 + g, 08:03 + g + 29 min]. */
    val fragment: Pair<Long, Long>? = g?.let { (time(8, 3) + it * MINUTE_MS) to (time(8, 3) + (it + 29) * MINUTE_MS) }

    /** The scenario's latest session end. */
    val latestEndMs: Long get() = fragment?.second ?: scenario.latestEndMs

    /** Whether OHealth writes one-minute steps with its heart rate. */
    val walking: Boolean get() = scenario.walking || walks
}

private val REPLAYS: List<Replay> =
    listOf(
        Replay("V3.1", Scenario.OCT18),
        Replay("V3.1", Scenario.OCT18, walks = true),
        Replay("V3.1", Scenario.OCT19),
        Replay("V3.1", Scenario.OCT19, walks = true),
        Replay("V3.1", Scenario.SLEEP_IN),
        Replay("V3.1", Scenario.HOURLY),
    ) +
        listOf(25, 35, 38, 45, 59).flatMap { g -> listOf(1, 25).map { d -> Replay("V3.2", Scenario.FRAGMENT, g = g, d = d) } } +
        Replay("V3.3", Scenario.NAP)

/** The upload extension E(P) of section 9.1, P in minutes. */
private val UPLOAD_PATTERNS: List<Int> = listOf(15, 60, 180)

/** V3.3: OHealth writes a nap 14:00-15:40 at 15:41 with heart rate from 13:30 to 15:41. Returns its start. */
private suspend fun Phone.replayNap(): Long {
    val start = time(14, 0)
    advanceTo(time(15, 41))
    h.repository.addSleepSession(OHEALTH, start, time(15, 40))
    h.heartRate(start, time(13, 30), time(15, 41))
    return start
}

/**
 * OHealth's heart rate that this class writes (the V3.2 fragment's and the upload extension's): every write covers
 * [max(W, E), its end), W = the end of the latest heart rate written so far, E = the end of the timeline's last
 * heart-rate write (made at E); nothing when that is empty, so no sample is written twice.
 */
private class ExtraHeartRate(
    private val h: PipelineHarness,
    private val scenario: Scenario,
) {
    private var writtenUntilMs: Long = scenario.lastHeartRateMs

    fun upTo(endMs: Long) {
        val from = maxOf(writtenUntilMs, scenario.lastHeartRateMs)
        if (from < endMs) h.heartRate(scenario.nightStartMs, from, endMs)
        writtenUntilMs = maxOf(writtenUntilMs, endMs)
    }
}

/**
 * Gate V3 of docs/prereg/prereg-merge-gap.md (section 9): the Sleep as Android gate replayed end to end in the harness of
 * [SleepAsAndroidGateScenarioTest] under each arm (A, B, C, R45, R90), with the upload extensions E(P), P = 15, 60, 180
 * min. The replays are recorded, not asserted (the pass rules are applied by tools/train, `pulsetrain.mergegap`), to
 * the JSON file of the system property `pulsestages.mergegap.v3Report`. Gate G2b asserts that arm A without the
 * extension reproduces what the scenario tests assert. Tagged `mergegap-replay`: excluded from the default test tasks,
 * run with `-Ppulsestages.mergegapReplay=true` (and `-Ppulsestages.mergegapArms=A` for arm A only).
 */
@Tag("mergegap-replay")
class MergeGapGateReplayTest {
    @TempDir
    lateinit var dir: File

    private var runs = 0

    private fun replayTest(
        start: Long,
        arm: Arm,
        block: suspend (PipelineHarness) -> Unit,
    ) = harnessTest(File(dir, "r${runs++}"), now = start) { h ->
        h.settings.set {
            it.copy(
                sources = it.sources.copy(mergeGapMin = arm.mergeGapMin, continuationGapMin = arm.continuationGapMin),
                write = it.write.copy(startBeforeSource = true),
            )
        }
        block(h)
    }

    @Test
    fun `G2b - arm A without the extension - 18 and 19 Oct as the scenario tests assert`() {
        for (walks in listOf(false, true)) {
            replayTest(time(7, 30), ARMS.first()) { h ->
                val phone = Phone(h, every(15 * MINUTE_MS, time(7, 36, seconds = 30), time(16, 0)))
                val start = phone.replay18Oct(walks)
                phone.advanceTo(time(16, 0))
                phone.assertImportedFinal(start, time(12, 15))
                assertEquals(listOf(time(13, 45), time(13, 55)), phone.saa.requestTimes)
                assertEquals(emptyList<ChangedNight>(), phone.notices.shown)
            }
            replayTest(time(7, 30), ARMS.first()) { h ->
                val phone = Phone(h, every(15 * MINUTE_MS, time(7, 55), time(12, 0)))
                val source = phone.replay19Oct(walks = walks)
                phone.advanceTo(time(12, 0))
                phone.assertImportedFinal(source.startMs, time(8, 3))
                assertEquals(listOf(time(9, 33), time(9, 43)), phone.saa.requestTimes)
                assertEquals(emptyList<ChangedNight>(), phone.notices.shown)
            }
        }
    }

    @Test
    fun `G2b - arm A without the extension - a sleep-in and hourly checks as the scenario tests assert`() {
        replayTest(time(7, 30), ARMS.first()) { h ->
            val phone = Phone(h, every(15 * MINUTE_MS, time(7, 36, seconds = 30), time(14, 0)))
            val start = phone.replaySleepIn { assertEquals(0, phone.saa.requests, "${phone.saa.requestTimes}") }
            phone.advanceTo(time(14, 0))
            phone.assertImportedFinal(start, time(10, 30))
            assertEquals(listOf(time(12, 0), time(12, 10)), phone.saa.requestTimes)
        }
        replayTest(time(7, 30), ARMS.first()) { h ->
            val phone = Phone(h, every(HOUR_MS, time(7, 55), time(12, 55)))
            val start = phone.replayHourlyChecks()
            phone.advanceTo(time(13, 0))
            phone.assertImportedFinal(start, time(8, 3))
            assertTrue(phone.saa.requestTimes.all { it > time(9, 55) }, "${phone.saa.requestTimes}")
        }
    }

    @Test
    fun `V3 - every replay under every arm, recorded`() {
        val names =
            System
                .getProperty(ARMS_PROPERTY)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
        val arms = if (names == null) ARMS else ARMS.filter { it.name in names }
        val records =
            arms.flatMap { arm ->
                REPLAYS.flatMap { replay -> UPLOAD_PATTERNS.map { p -> replay(arm, replay, p) } }
            }
        val target = File(System.getProperty(REPORT_PROPERTY) ?: "build/reports/mergegap-v3.json")
        target.parentFile?.mkdirs()
        val report =
            mapOf(
                "version" to 1,
                "arms" to
                    arms.map {
                        mapOf("name" to it.name, "mergeGapMin" to it.mergeGapMin, "continuationGapMin" to it.continuationGapMin)
                    },
                "uploadPatternsMin" to UPLOAD_PATTERNS,
                "replays" to records,
            )
        target.writeText(Json.encode(report) + "\n")
        assertEquals(arms.size * REPLAYS.size * UPLOAD_PATTERNS.size, records.size)
    }

    /** One replay of [replay] under [arm] with the upload extension every [uploadEveryMin] min, as a report record. */
    @Suppress("TooGenericExceptionCaught") // A replay that throws is recorded (V3a (iii)), not a test failure.
    private fun replay(
        arm: Arm,
        replay: Replay,
        uploadEveryMin: Int,
    ): Map<String, Any?> {
        val base =
            mapOf(
                "arm" to arm.name,
                "set" to replay.set,
                "scenario" to replay.scenario.label,
                "walks" to replay.walks,
                "uploadEveryMin" to uploadEveryMin,
                "g" to replay.g,
                "d" to replay.d,
                "nightStartMs" to replay.scenario.nightStartMs,
                "firstPieceEndMs" to if (replay.fragment != null) time(8, 3) else null,
                "fragmentEndMs" to replay.fragment?.second,
            )
        var record: Map<String, Any?> = base + ("error" to "not run")
        try {
            replayTest(replay.scenario.startMs, arm) { h ->
                val phone = phoneFor(h, replay, uploadEveryMin)
                when (replay.scenario) {
                    Scenario.OCT18 -> phone.replay18Oct(replay.walks)
                    Scenario.OCT19, Scenario.FRAGMENT -> phone.replay19Oct(walks = replay.walks)
                    Scenario.SLEEP_IN -> phone.replaySleepIn()
                    Scenario.HOURLY -> phone.replayHourlyChecks()
                    Scenario.NAP -> phone.replayNap()
                }
                phone.advanceTo(replay.latestEndMs + 8 * HOUR_MS)
                record = base + outcome(h, phone) + ("error" to null)
            }
        } catch (e: Exception) {
            record = base + ("error" to "${e::class.java.simpleName}: ${e.message}")
        }
        return record
    }

    /**
     * The phone of [replay]: runs at the scenario's interval until 8 h after its latest session end, OHealth's V3.2
     * fragment written at its end + d with its heart rate, and the upload extension: from U = E, every P minutes heart
     * rate up to then (and in a walking scenario the walk's step records since the previous upload) until that time.
     */
    private fun phoneFor(
        h: PipelineHarness,
        replay: Replay,
        uploadEveryMin: Int,
    ): Phone {
        val end = replay.latestEndMs + 8 * HOUR_MS
        val heartRate = ExtraHeartRate(h, replay.scenario)
        val writes = mutableListOf<Pair<Long, () -> Unit>>()
        replay.fragment?.let { (from, to) ->
            writes +=
                (to + (replay.d ?: 0) * MINUTE_MS) to {
                    h.repository.addSleepSession(OHEALTH, from, to)
                    heartRate.upTo(to)
                }
        }
        val u = replay.scenario.lastHeartRateMs
        val p = uploadEveryMin * MINUTE_MS
        generateSequence(1L) { it + 1 }.map { u + it * p }.takeWhile { it <= end }.forEach { t ->
            writes +=
                t to {
                    heartRate.upTo(t)
                    if (replay.walking) h.walk(t - p + MINUTE_MS, t - MINUTE_MS + 1)
                }
        }
        return Phone(h, every(replay.scenario.intervalMs, replay.scenario.firstRunMs, end), writes = writes)
    }

    /**
     * What the replay ends with: every request time, Sleep as Android's records, the changed-night notices, and the nights
     * PulseStages writes from OHealth's final sessions under the arm, each with whether Sleep as Android holds its final
     * version (the conditions of [Phone.assertImportedFinal]) and whether a notice was shown for it.
     */
    private suspend fun outcome(
        h: PipelineHarness,
        phone: Phone,
    ): Map<String, Any?> {
        val settings = h.settings.current
        val sources = h.repository.allSessions().filter { it.packageName == OHEALTH }
        val nights =
            h.builder
                .build(sources, settings.sources, settings.write.overwritePolicy, settings.algo)
                .candidates
                .filterNot { it.writesNothing(settings.algo) }
        val own = h.repository.ownSessions()
        return mapOf(
            "requestTimes" to phone.saa.requestTimes.toList(),
            "saaRecords" to
                phone.saa.imported.map {
                    mapOf(
                        "startMs" to it.startMs,
                        "endMs" to it.endMs,
                        "packageName" to it.packageName,
                        "clientRecordId" to it.clientRecordId,
                        "clientRecordVersion" to it.clientRecordVersion,
                    )
                },
            "notices" to
                phone.notices.shown.map {
                    mapOf(
                        "clientRecordId" to it.night.clientRecordId,
                        "sentEndMs" to it.sentEndMs,
                        "nightStartMs" to it.night.sessionStartMs,
                        "nightEndMs" to it.night.sessionEndMs,
                    )
                },
            "ownSessions" to
                own.map {
                    mapOf(
                        "startMs" to it.startMs,
                        "endMs" to it.endMs,
                        "clientRecordId" to it.clientRecordId,
                        "clientRecordVersion" to it.clientRecordVersion,
                    )
                },
            "finalNights" to
                nights.map { night ->
                    val records = phone.saa.imported.filter { it.startMs <= night.endMs && night.startMs <= it.endMs }
                    val record = records.singleOrNull()
                    val current = record?.let { r -> own.singleOrNull { it.startMs == r.startMs } }
                    val importedFinal =
                        record != null && current != null && record.packageName == OWN && record.endMs == night.endMs &&
                            record.startMs == night.startMs - 1_000L && current.clientRecordVersion == record.clientRecordVersion
                    mapOf(
                        "clientRecordId" to night.clientRecordId,
                        "startMs" to night.startMs,
                        "endMs" to night.endMs,
                        "mode" to night.mode(settings.algo).name,
                        "importedFinal" to importedFinal,
                        "noticed" to phone.notices.shown.any { it.night.clientRecordId == night.clientRecordId },
                    )
                },
        )
    }
}

/** A minimal JSON writer for the replay report (maps, lists, strings, numbers, booleans, null). */
private object Json {
    fun encode(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> quote(value)
            is Boolean, is Int, is Long -> value.toString()
            is Double -> if (value.isFinite()) value.toString() else "null"
            is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> quote(k.toString()) + ":" + encode(v) }
            is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
            else -> quote(value.toString())
        }

    private fun quote(s: String): String =
        buildString {
            append('"')
            for (c in s) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
}
