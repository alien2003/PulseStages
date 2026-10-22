package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.data.hc.testutil.OWN
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Gate G4 of docs/prereg/prereg-merge-gap.md for the arms that the app can still be set to: [NightCandidateBuilder]
 * groups every case of the committed fixture under arms A, B, R45 and R90 (`sources.mergeGapMin` 30, 60, 45 and 90
 * min) exactly as the Python emulation `pulsetrain.mergegap.group`, on which the experiment's analysis relies, does
 * (groups, spans, forced-AWAKE gaps, modes, ignored groups). Arm C and its continuation thresholds were checked here,
 * and SourceSettlement's per-session margins in SourceSettlementMergeGapTest, only up to a55aa55: its setting
 * `sources.continuationGapMin` was removed after the experiment kept arm A.
 */
class MergeGapGroupingFixtureTest {
    private val builder = NightCandidateBuilder(OWN) { 0 }
    private val fixture = MergeGapFixture.load()

    @Test
    fun `the fixture covers the remaining arms and the cases of the pre-registration`() {
        assertEquals(listOf("A", "B", "R45", "R90"), fixture.arms.map { it.name })
        assertEquals(listOf(30, 60, 45, 90), fixture.arms.map { it.mergeGapMin })
        assertEquals(listOf("C"), fixture.skippedArms)
        assertTrue(fixture.cases.size >= 40, "${fixture.cases.size} cases")
        assertTrue(fixture.cases.any { it.name == "labelled-pair-21-oct" })
        assertTrue(fixture.cases.any { it.napMaxSessionMin == 0.0 })
    }

    @Test
    fun `NightCandidateBuilder groups every case as the fixture says`() {
        val mismatches =
            fixture.cases.flatMap { case ->
                fixture.arms.mapNotNull { arm ->
                    val algo = MergeGapFixture.algo(case)
                    val set = builder.build(case.sessions, MergeGapFixture.sources(case, arm), OverwritePolicy.SKIP_SAME_VERSION, algo)
                    val got = MergeGapFixture.groupsOf(set, algo)
                    val want = case.expected.getValue(arm.name)
                    if (got == want) null else "${case.name} / ${arm.name}: got $got, want $want"
                }
            }
        assertEquals(emptyList<String>(), mismatches)
    }
}
