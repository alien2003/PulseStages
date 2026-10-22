package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.data.hc.testutil.OWN
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Gate G4 of docs/prereg/prereg-merge-gap.md: [NightCandidateBuilder] groups every case of the committed fixture under
 * every arm (A, B, C, R45, R90) exactly as the Python emulation `pulsetrain.mergegap.group` does (groups, spans,
 * forced-AWAKE gaps, modes, ignored groups), and [NightCandidateBuilder.continuationThresholds] gives the fixture's
 * thresholds.
 */
class MergeGapGroupingFixtureTest {
    private val builder = NightCandidateBuilder(OWN) { 0 }
    private val fixture = MergeGapFixture.load()

    @Test
    fun `the fixture covers every arm and the cases of the pre-registration`() {
        assertEquals(listOf("A", "B", "C", "R45", "R90"), fixture.arms.map { it.name })
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
                    val want = case.expected.getValue(arm.name).groups
                    if (got == want) null else "${case.name} / ${arm.name}: got $got, want $want"
                }
            }
        assertEquals(emptyList<String>(), mismatches)
    }

    @Test
    fun `continuationThresholds gives every case's thresholds`() {
        val mismatches =
            fixture.cases.flatMap { case ->
                fixture.arms.mapNotNull { arm ->
                    val got =
                        builder.continuationThresholds(
                            case.sessions,
                            MergeGapFixture.sources(case, arm),
                            OverwritePolicy.SKIP_SAME_VERSION,
                            MergeGapFixture.algo(case),
                        )
                    val want = case.expected.getValue(arm.name).thresholds
                    if (got == want) null else "${case.name} / ${arm.name}: got $got, want $want"
                }
            }
        assertEquals(emptyList<String>(), mismatches)
    }
}
