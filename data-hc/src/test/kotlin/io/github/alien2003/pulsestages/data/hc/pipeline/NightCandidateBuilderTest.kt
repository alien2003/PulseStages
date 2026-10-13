package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.algo.model.TimeSpan
import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.HcStage
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OTHER_APP
import io.github.alien2003.pulsestages.data.hc.testutil.OWN
import io.github.alien2003.pulsestages.data.hc.testutil.THIRD_APP
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.SourceOutcome
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import io.github.alien2003.pulsestages.data.local.settings.SourceSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class NightCandidateBuilderTest {
    private val builder = NightCandidateBuilder(OWN) { 3600 }
    private val start = nightStart(1)
    private var counter = 0

    private fun session(
        pkg: String = OHEALTH,
        startMs: Long = start,
        endMs: Long = startMs + 8 * HOUR_MS,
        stages: List<HcStage> = emptyList(),
        startOffset: Int? = 10_800,
        endOffset: Int? = 10_800,
        uuid: String = "u${++counter}",
    ) = HcSleepSession(uuid, pkg, startMs, endMs, startOffset, endOffset, stages, null, null, null, 0, 0)

    private fun build(
        vararg sessions: HcSleepSession,
        sources: SourceSettings = SourceSettings(),
        policy: OverwritePolicy = OverwritePolicy.SKIP_SAME_VERSION,
    ) = builder.build(sessions.toList(), sources, policy)

    @Test
    fun `only enabled packages are used (allowlist)`() {
        val result = build(session(OHEALTH), session(OTHER_APP, startMs = start - 2 * 24 * HOUR_MS))
        assertEquals(1, result.candidates.size)
        assertEquals(OHEALTH, result.candidates.single().packageName)
        assertTrue(result.skipped.isEmpty(), "sessions of disabled packages are ignored, not counted")

        val both =
            build(
                session(OHEALTH),
                session(OTHER_APP, startMs = start - 2 * 24 * HOUR_MS),
                sources = SourceSettings(sourcePackages = listOf(OHEALTH, OTHER_APP)),
            )
        assertEquals(setOf(OHEALTH, OTHER_APP), both.candidates.map { it.packageName }.toSet())
    }

    @Test
    fun `our own sessions are never candidates, even when our package is enabled`() {
        val own = session(OWN, uuid = "own-1")
        val result =
            build(own, session(OHEALTH, startMs = start + 24 * HOUR_MS), sources = SourceSettings(sourcePackages = listOf(OHEALTH, OWN)))
        assertEquals(listOf(own), result.own)
        assertTrue(result.candidates.none { it.packageName == OWN })
    }

    @Test
    fun `sessions with real stages are skipped, UNKNOWN and SLEEPING do not count, RECOMPUTE_ALL includes them`() {
        val staged = session(stages = listOf(HcStage(start, start + HOUR_MS, 5)), uuid = "staged")
        val generic =
            session(
                startMs = start + 24 * HOUR_MS,
                stages =
                    listOf(
                        HcStage(start + 24 * HOUR_MS, start + 25 * HOUR_MS, 2),
                        HcStage(start + 25 * HOUR_MS, start + 26 * HOUR_MS, 0),
                    ),
            )

        val result = build(staged, generic)
        assertEquals(listOf(generic.uuid), result.candidates.flatMap { it.sourceUuids })
        assertEquals(SourceOutcome.HAS_STAGES, result.skipped.single().outcome)
        assertEquals(listOf(staged), result.skipped.single().sessions)

        val all = build(staged, generic, policy = OverwritePolicy.RECOMPUTE_ALL)
        assertEquals(2, all.candidates.size)
        assertTrue(all.candidates.first { "staged" in it.sourceUuids }.hasStages)
        assertTrue(all.skipped.isEmpty())
    }

    @Test
    fun `sessions shorter than minSessionMin after merging are naps`() {
        val nap = session(startMs = start + 14 * HOUR_MS, endMs = start + 14 * HOUR_MS + 89 * MINUTE_MS, uuid = "nap")
        val exact = session(startMs = start + 30 * HOUR_MS, endMs = start + 30 * HOUR_MS + 90 * MINUTE_MS, uuid = "exact")
        val result = build(nap, exact)
        assertEquals(listOf("exact"), result.candidates.flatMap { it.sourceUuids })
        assertEquals(SourceOutcome.NAP, result.skipped.single().outcome)
        assertTrue(
            result.skipped
                .single()
                .reason
                .contains("89 min"),
        )

        // Two short fragments that merge into a long night are not naps.
        val a = session(startMs = start, endMs = start + 60 * MINUTE_MS, uuid = "a")
        val b = session(startMs = start + 70 * MINUTE_MS, endMs = start + 130 * MINUTE_MS, uuid = "b")
        assertEquals(listOf(listOf("a", "b")), build(a, b).candidates.map { it.sourceUuids })
    }

    @Test
    fun `fragments closer than mergeGapMin are merged, gaps become forced-awake spans, offsets come from first start and last end`() {
        val a = session(startMs = start, endMs = start + 3 * HOUR_MS, startOffset = 10_800, endOffset = 10_800, uuid = "b-first")
        val b =
            session(
                startMs = start + 3 * HOUR_MS + 29 * MINUTE_MS,
                endMs = start + 8 * HOUR_MS,
                startOffset = 10_800,
                endOffset = 7_200,
                uuid = "a-second",
            )
        val c = session(startMs = start + 8 * HOUR_MS + 30 * MINUTE_MS, endMs = start + 10 * HOUR_MS, uuid = "c-third")

        val result = build(c, b, a)
        assertEquals(2, result.candidates.size)
        val night = result.candidates.first()
        assertEquals(listOf(a, b), night.fragments)
        assertEquals(start, night.startMs)
        assertEquals(start + 8 * HOUR_MS, night.endMs)
        assertEquals(10_800, night.startZoneOffsetSec)
        assertEquals(7_200, night.endZoneOffsetSec)
        assertEquals(listOf(TimeSpan(start + 3 * HOUR_MS, start + 3 * HOUR_MS + 29 * MINUTE_MS)), night.forcedAwake)
        assertEquals(listOf("a-second", "b-first"), night.sourceUuids, "uuids are sorted")
        assertEquals(listOf("c-third"), result.candidates[1].sourceUuids, "a gap of exactly mergeGapMin is not merged")
    }

    @Test
    fun `overlapping fragments merge without gaps and missing offsets use the fallback zone`() {
        val a = session(startMs = start, endMs = start + 5 * HOUR_MS, startOffset = null, endOffset = null)
        val b = session(startMs = start + 4 * HOUR_MS, endMs = start + 4 * HOUR_MS + 30 * MINUTE_MS, startOffset = null, endOffset = null)
        val night = build(a, b).candidates.single()
        assertEquals(start + 5 * HOUR_MS, night.endMs, "end is the latest end, not the last fragment's")
        assertTrue(night.forcedAwake.isEmpty())
        assertEquals(3600, night.startZoneOffsetSec)
        assertEquals(3600, night.endZoneOffsetSec)
        assertTrue(night.startOffsetIsFallback && night.endOffsetIsFallback, "the pipeline must know that the offsets are a guess")

        val known = build(session()).candidates.single()
        assertFalse(known.startOffsetIsFallback || known.endOffsetIsFallback)
    }

    @Test
    fun `mergeGapMin 0 merges only overlapping fragments`() {
        val a = session(startMs = start, endMs = start + 4 * HOUR_MS)
        val b = session(startMs = start + 4 * HOUR_MS, endMs = start + 8 * HOUR_MS)
        assertEquals(2, build(a, b, sources = SourceSettings(mergeGapMin = 0)).candidates.size)
        assertEquals(1, build(a, b).candidates.size)
    }

    @Test
    fun `cross-source duplicates keep the higher-priority source`() {
        val sources = SourceSettings(sourcePackages = listOf(OHEALTH, OTHER_APP, THIRD_APP), sourcePriority = listOf(OTHER_APP, OHEALTH))
        val oh = session(OHEALTH, startMs = start, endMs = start + 8 * HOUR_MS, uuid = "oh")
        val other = session(OTHER_APP, startMs = start + 30 * MINUTE_MS, endMs = start + 7 * HOUR_MS, uuid = "other")
        val third = session(THIRD_APP, startMs = start + HOUR_MS, endMs = start + 6 * HOUR_MS, uuid = "third")

        val result = build(oh, other, third, sources = sources)
        assertEquals(listOf("other"), result.candidates.flatMap { it.sourceUuids })
        val duplicates = result.skipped.filter { it.outcome == SourceOutcome.LOWER_PRIORITY_DUPLICATE }
        assertEquals(setOf("oh", "third"), duplicates.flatMap { d -> d.sessions.map { it.uuid } }.toSet())
        assertTrue(duplicates.all { it.reason.contains(OTHER_APP) })
    }

    @Test
    fun `a staged night of a higher-priority source makes the overlapping night of a lower-priority source a duplicate`() {
        val sources = SourceSettings(sourcePackages = listOf(OHEALTH, OTHER_APP), sourcePriority = listOf(OTHER_APP, OHEALTH))
        val staged =
            session(OTHER_APP, startMs = start + 15 * MINUTE_MS, endMs = start + 8 * HOUR_MS, uuid = "staged").let {
                it.copy(stages = listOf(HcStage(it.startMs, it.endMs, 4)))
            }
        val oh = session(OHEALTH, startMs = start, endMs = start + 8 * HOUR_MS, uuid = "oh")

        val result = build(oh, staged, sources = sources)

        assertTrue(result.candidates.isEmpty(), "the night is taken from the higher-priority source, which needs no estimate")
        val byOutcome = result.skipped.associateBy { it.outcome }
        assertEquals(listOf("staged"), byOutcome.getValue(SourceOutcome.HAS_STAGES).sessions.map { it.uuid })
        val duplicate = byOutcome.getValue(SourceOutcome.LOWER_PRIORITY_DUPLICATE)
        assertEquals(listOf("oh"), duplicate.sessions.map { it.uuid })
        assertEquals(listOf("staged"), duplicate.stagedWinnerUuids)
        assertTrue(duplicate.reason.contains(OTHER_APP) && duplicate.reason.contains("sleep stages"), duplicate.reason)
        assertEquals(result.skipped, result.containingAny(setOf("staged")).skipped, "a changed staged session brings its duplicate")

        // RECOMPUTE_ALL estimates the staged session itself: the priority decides as for any two nights.
        val all = build(oh, staged, sources = sources, policy = OverwritePolicy.RECOMPUTE_ALL)
        assertEquals(listOf("staged"), all.candidates.flatMap { it.sourceUuids })
        assertTrue(
            all.skipped
                .single()
                .stagedWinnerUuids
                .isEmpty(),
        )
    }

    @Test
    fun `a staged night blocks only lower-priority nights it mostly overlaps and a staged nap blocks nothing`() {
        val stages = { from: Long, to: Long -> listOf(HcStage(from, to, 5)) }
        val lowerStaged =
            session(OTHER_APP, startMs = start, endMs = start + 8 * HOUR_MS, uuid = "other-staged").let {
                it.copy(stages = stages(it.startMs, it.endMs))
            }
        val oh = session(OHEALTH, startMs = start, endMs = start + 8 * HOUR_MS, uuid = "oh")
        val ohFirst = SourceSettings(sourcePackages = listOf(OHEALTH, OTHER_APP), sourcePriority = listOf(OHEALTH, OTHER_APP))
        assertEquals(listOf("oh"), build(oh, lowerStaged, sources = ohFirst).candidates.flatMap { it.sourceUuids })
        assertEquals(listOf(SourceOutcome.HAS_STAGES), build(oh, lowerStaged, sources = ohFirst).skipped.map { it.outcome })

        val otherFirst = SourceSettings(sourcePackages = listOf(OHEALTH, OTHER_APP), sourcePriority = listOf(OTHER_APP, OHEALTH))
        val nap =
            session(OTHER_APP, startMs = start + HOUR_MS, endMs = start + 2 * HOUR_MS, uuid = "staged-nap").let {
                it.copy(stages = stages(it.startMs, it.endMs))
            }
        assertEquals(listOf("oh"), build(oh, nap, sources = otherFirst).candidates.flatMap { it.sourceUuids }, "a nap records no night")

        // Staged fragments of one source merge like any other: together they cover the night.
        val first =
            session(
                OTHER_APP,
                startMs = start,
                endMs = start + 4 * HOUR_MS,
                uuid = "part-1",
            ).let { it.copy(stages = stages(it.startMs, it.endMs)) }
        val second =
            session(OTHER_APP, startMs = start + 4 * HOUR_MS + 10 * MINUTE_MS, endMs = start + 8 * HOUR_MS, uuid = "part-2").let {
                it.copy(stages = stages(it.startMs, it.endMs))
            }
        val merged = build(oh, first, second, sources = otherFirst)
        assertTrue(merged.candidates.isEmpty())
        assertEquals(
            listOf("part-1", "part-2"),
            merged.skipped.single { it.outcome == SourceOutcome.LOWER_PRIORITY_DUPLICATE }.stagedWinnerUuids,
        )
        assertEquals(2, merged.skipped.count { it.outcome == SourceOutcome.HAS_STAGES }, "each staged session stays HAS_STAGES")
    }

    @Test
    fun `a nap-length piece of a higher-priority source does not replace the whole night of another source`() {
        // minSessionMin 30 (naps estimated): the watch recorded only 40 minutes of the night the other app recorded fully.
        val otherFirst =
            SourceSettings(sourcePackages = listOf(OHEALTH, OTHER_APP), sourcePriority = listOf(OTHER_APP, OHEALTH), minSessionMin = 30)
        val oh = session(OHEALTH, startMs = start, endMs = start + 8 * HOUR_MS, uuid = "oh")
        val piece = session(OTHER_APP, startMs = start + 3 * HOUR_MS, endMs = start + 3 * HOUR_MS + 40 * MINUTE_MS, uuid = "piece")

        val result = build(oh, piece, sources = otherFirst)

        assertEquals(listOf("oh"), result.candidates.flatMap { it.sourceUuids })
        val duplicate = result.skipped.single()
        assertEquals(SourceOutcome.LOWER_PRIORITY_DUPLICATE, duplicate.outcome)
        assertEquals(listOf("piece"), duplicate.sessions.map { it.uuid })
        assertTrue(duplicate.reason.contains("inside the longer night of $OHEALTH"), duplicate.reason)
        assertTrue(duplicate.stagedWinnerUuids.isEmpty())
        // The other order of priority gives the same result.
        val ohFirst = otherFirst.copy(sourcePriority = listOf(OHEALTH, OTHER_APP))
        assertEquals(listOf("oh"), build(oh, piece, sources = ohFirst).candidates.flatMap { it.sourceUuids })

        // At exactly half of the longer night it is still a piece; above half the priority decides again.
        val half = session(OTHER_APP, startMs = start, endMs = start + 4 * HOUR_MS, uuid = "half")
        assertEquals(listOf("oh"), build(oh, half, sources = otherFirst).candidates.flatMap { it.sourceUuids })
        val most = session(OTHER_APP, startMs = start, endMs = start + 4 * HOUR_MS + MINUTE_MS, uuid = "most")
        val byPriority = build(oh, most, sources = otherFirst)
        assertEquals(listOf("most"), byPriority.candidates.flatMap { it.sourceUuids })
        assertTrue(
            byPriority.skipped
                .single()
                .reason
                .contains("higher-priority source $OTHER_APP"),
        )
    }

    @Test
    fun `staged pieces block nothing and a piece inside a staged night needs no estimate`() {
        val stages = { from: Long, to: Long -> listOf(HcStage(from, to, 5)) }
        val otherFirst =
            SourceSettings(sourcePackages = listOf(OHEALTH, OTHER_APP), sourcePriority = listOf(OTHER_APP, OHEALTH), minSessionMin = 30)
        val oh = session(OHEALTH, startMs = start, endMs = start + 8 * HOUR_MS, uuid = "oh")
        val stagedPiece =
            session(OTHER_APP, startMs = start + HOUR_MS, endMs = start + 2 * HOUR_MS, uuid = "staged-piece").let {
                it.copy(stages = stages(it.startMs, it.endMs))
            }

        val blocked = build(oh, stagedPiece, sources = otherFirst)

        assertEquals(listOf("oh"), blocked.candidates.flatMap { it.sourceUuids })
        assertEquals(listOf(SourceOutcome.HAS_STAGES), blocked.skipped.map { it.outcome })

        // A stage-less higher-priority piece inside a lower-priority night with stages: the night has its stages.
        val stagedNight = oh.copy(uuid = "oh-staged", stages = stages(oh.startMs, oh.endMs))
        val piece = session(OTHER_APP, startMs = start + HOUR_MS, endMs = start + 2 * HOUR_MS, uuid = "piece")
        val inside = build(stagedNight, piece, sources = otherFirst)
        assertTrue(inside.candidates.isEmpty())
        val duplicate = inside.skipped.single { it.outcome == SourceOutcome.LOWER_PRIORITY_DUPLICATE }
        assertEquals(listOf("piece"), duplicate.sessions.map { it.uuid })
        assertEquals(listOf("oh-staged"), duplicate.stagedWinnerUuids)
        assertTrue(duplicate.reason.contains("sleep stages of $OHEALTH"), duplicate.reason)
    }

    @Test
    fun `an overlap of at most half of the shorter night is not a duplicate`() {
        val sources = SourceSettings(sourcePackages = listOf(OHEALTH, OTHER_APP), sourcePriority = listOf(OHEALTH, OTHER_APP))
        val oh = session(OHEALTH, startMs = start, endMs = start + 8 * HOUR_MS)
        val other = session(OTHER_APP, startMs = start + 6 * HOUR_MS, endMs = start + 10 * HOUR_MS)
        assertEquals(2, build(oh, other, sources = sources).candidates.size)
    }

    @Test
    fun `clientRecordId is ps + sha256 of the sorted uuids and does not depend on input order`() {
        val a = session(startMs = start, endMs = start + 3 * HOUR_MS, uuid = "zz")
        val b = session(startMs = start + 3 * HOUR_MS + 10 * MINUTE_MS, endMs = start + 8 * HOUR_MS, uuid = "aa")
        val expected =
            "ps:" + MessageDigest.getInstance("SHA-256").digest("aa,zz".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, build(a, b).candidates.single().clientRecordId)
        assertEquals(expected, build(b, a).candidates.single().clientRecordId)
        assertEquals(expected, NightKeys.clientRecordId(listOf("zz", "aa", "zz")))
        assertEquals(67, expected.length)
    }

    @Test
    fun `candidate sets filter by window ownership, uuids and overlap`() {
        val first = session(startMs = start, uuid = "first")
        val second = session(startMs = start + 24 * HOUR_MS, uuid = "second")
        val nap = session(startMs = start + 12 * HOUR_MS, endMs = start + 13 * HOUR_MS, uuid = "nap")
        val set = build(first, second, nap)

        val owned = set.ownedBy(start + HOUR_MS, start + 48 * HOUR_MS)
        assertEquals(listOf("second"), owned.candidates.flatMap { it.sourceUuids })
        assertEquals(listOf("nap"), owned.skipped.flatMap { s -> s.sessions.map { it.uuid } })

        assertEquals(listOf("first"), set.containingAny(setOf("first", "unknown")).candidates.flatMap { it.sourceUuids })
        assertEquals(listOf("first"), set.overlapping(start + 7 * HOUR_MS, start + 9 * HOUR_MS).candidates.flatMap { it.sourceUuids })
        assertEquals(3, set.nightCount)
        assertEquals(set, set + set)
    }
}
