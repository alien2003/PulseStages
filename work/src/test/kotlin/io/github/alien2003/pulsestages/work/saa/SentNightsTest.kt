package io.github.alien2003.pulsestages.work.saa

import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.data.hc.fake.InMemoryLedger
import io.github.alien2003.pulsestages.data.hc.testutil.DAY_MS
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OTHER_APP
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * The nights of a request to Sleep as Android in the ledger: which are stored when it is sent, and which of them
 * PulseStages has since written with another start or end, followed through merges and to the nights that took them
 * over, unless a notice already shows that version.
 */
class SentNightsTest {
    private val ledger = InMemoryLedger()
    private val sentNights = SentNights(ledger.nightDao)

    private val start = nightStart(1)
    private val end = start + 8 * HOUR_MS

    /** A ledger night [id] built from [sources] (WRITTEN with a record by default). */
    private fun night(
        id: String,
        sources: List<String>,
        startMs: Long = start,
        endMs: Long = end,
        status: NightStatus = NightStatus.WRITTEN,
        hcRecordId: String? = "hc-$id".takeIf { status == NightStatus.WRITTEN },
    ) = EstimatedNightEntity(
        clientRecordId = id,
        sourceUuids = sources,
        sourcePackage = OHEALTH,
        sessionStartMs = startMs,
        sessionEndMs = endMs,
        startZoneOffsetSec = 0,
        endZoneOffsetSec = 0,
        hcRecordId = hcRecordId,
        algoVersion = 1,
        paramsHash = "p",
        estimator = EstimatorType.HYBRID,
        status = status,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private suspend fun store(vararg nights: EstimatedNightEntity) = nights.forEach { ledger.nightDao.upsert(it) }

    private suspend fun supersede(
        vararg ids: String,
        by: String,
    ) = ledger.nightDao.markSuperseded(ids.toList(), by, now = 0L)

    private fun key(
        id: String,
        startMs: Long = start,
        endMs: Long = end,
    ) = "$id:$startMs:$endMs"

    private suspend fun changed(
        sentNightsKey: String?,
        noticedKey: String?,
    ): List<ChangedNight> = sentNights.check(sentNightsKey, noticedKey).changes

    @Test
    fun `the key holds only the WRITTEN nights with a record, sorted, and is null without any`() =
        runTest {
            store(
                night("ps:0b", listOf("b")),
                night("ps:0a", listOf("a"), startMs = start - HOUR_MS),
                night("ps:0c", listOf("c"), status = NightStatus.NOT_ESTIMATED),
                night("ps:0d", listOf("d"), hcRecordId = null),
                night("ps:0e", listOf("e"), status = NightStatus.SUPERSEDED),
            )

            assertEquals(
                "${key("ps:0a", startMs = start - HOUR_MS)};${key("ps:0b")}",
                sentNights.keyOf(listOf("b", "a", "c", "d", "e")),
            )
            assertNull(sentNights.keyOf(listOf("c", "d", "e")))
            assertNull(sentNights.keyOf(listOf("unknown")))
            assertNull(sentNights.keyOf(emptyList()))
        }

    @Test
    fun `a night rewritten in place with a later end is a change from the span that was sent`() =
        runTest {
            store(night("ps:0a", listOf("a"), endMs = end + 34 * MINUTE_MS))

            val change = changed(key("ps:0a"), noticedKey = null).single()

            assertEquals("ps:0a", change.night.clientRecordId)
            assertEquals(start, change.sentStartMs)
            assertEquals(end, change.sentEndMs)
            assertEquals(emptySet<String>(), change.replaced)
            assertFalse(change.otherSources)
            assertEquals(key("ps:0a", endMs = end + 34 * MINUTE_MS), change.key)
        }

    @Test
    fun `a night that now starts earlier is a change`() =
        runTest {
            store(night("ps:0a", listOf("a"), startMs = start - 20 * MINUTE_MS))

            val change = changed(key("ps:0a"), null).single()

            assertEquals(start - 20 * MINUTE_MS, change.night.sessionStartMs)
        }

    @Test
    fun `a night rewritten with the same span is no change`() =
        runTest {
            store(night("ps:0a", listOf("a")).copy(recomputeCount = 3, updatedAt = 99L))

            assertEquals(emptyList<ChangedNight>(), changed(key("ps:0a"), null))
        }

    @Test
    fun `a night merged into a new one is a change of the new night that replaces the old one`() =
        runTest {
            val merged = night("ps:0ab", listOf("a", "b"), endMs = end + 2 * HOUR_MS)
            store(night("ps:0a", listOf("a")), merged)
            supersede("ps:0a", by = "ps:0ab")

            val change = changed(key("ps:0a"), null).single()

            assertEquals("ps:0ab", change.night.clientRecordId)
            assertEquals(end, change.sentEndMs)
            assertEquals(setOf("ps:0a"), change.replaced)
            assertTrue(change.otherSources)
        }

    @Test
    fun `a night merged twice is a change of the last night that replaces both earlier ones`() =
        runTest {
            store(
                night("ps:0a", listOf("a")),
                night("ps:0ab", listOf("a", "b"), endMs = end + HOUR_MS),
                night("ps:0abc", listOf("a", "b", "c"), endMs = end + 2 * HOUR_MS),
            )
            supersede("ps:0a", by = "ps:0ab")
            supersede("ps:0ab", by = "ps:0abc")

            val change = changed(key("ps:0a"), null).single()

            assertEquals("ps:0abc", change.night.clientRecordId)
            assertEquals(setOf("ps:0a", "ps:0ab"), change.replaced)
        }

    @Test
    fun `a superseded night follows the WRITTEN night that overlaps it most`() =
        runTest {
            // The source "a" is now part of a night that starts at its end: a split, with the larger part elsewhere.
            store(
                night("ps:0ab", listOf("a", "b")),
                night("ps:0a2", listOf("a", "x"), startMs = start + 7 * HOUR_MS, endMs = end + HOUR_MS),
                night("ps:0a3", listOf("a", "y"), startMs = start - HOUR_MS, endMs = end - HOUR_MS),
            )
            supersede("ps:0ab", by = "ps:0a3")

            val change = changed(key("ps:0ab"), null).single()

            assertEquals("ps:0a3", change.night.clientRecordId)
        }

    @ParameterizedTest
    @EnumSource(NightStatus::class, names = ["DELETED", "SOURCE_DELETED", "NOT_ESTIMATED", "FAILED"])
    fun `a night the user or the source removed, or one without a record, is no change`(status: NightStatus) =
        runTest {
            store(night("ps:0a", listOf("a"), endMs = end + HOUR_MS, status = status))

            assertEquals(emptyList<ChangedNight>(), changed(key("ps:0a"), null))
        }

    @Test
    fun `a night superseded by one without a record is no change`() =
        runTest {
            store(night("ps:0a", listOf("a")), night("ps:0ab", listOf("a", "b"), endMs = end + HOUR_MS, status = NightStatus.FAILED))
            supersede("ps:0a", by = "ps:0ab")

            assertEquals(emptyList<ChangedNight>(), changed(key("ps:0a"), null))
        }

    @Test
    fun `a version already notified is left out, a newer one is not`() =
        runTest {
            store(night("ps:0a", listOf("a"), endMs = end + HOUR_MS))
            val noticed = key("ps:0a", endMs = end + HOUR_MS)

            assertEquals(emptyList<ChangedNight>(), changed(key("ps:0a"), noticed))

            store(night("ps:0a", listOf("a"), endMs = end + 2 * HOUR_MS))
            val change = changed(key("ps:0a"), noticed).single()
            assertEquals(end + 2 * HOUR_MS, change.night.sessionEndMs)
        }

    @Test
    fun `malformed entries are skipped`() =
        runTest {
            store(night("ps:0a", listOf("a"), endMs = end + HOUR_MS))

            val changes = changed("garbage;ps:0b:x:1;;${key("ps:0a")};ps:0c:1", null)

            assertEquals(listOf("ps:0a"), changes.map { it.night.clientRecordId })
            assertEquals(emptyList<ChangedNight>(), changed(null, null))
            assertEquals(emptyList<ChangedNight>(), changed("", null))
        }

    @Test
    fun `two nights of the request merged into one are one change from the span of the main night`() =
        runTest {
            // The main night is extended in place so that the gap to the later one falls under "Merge gap": a merge alone
            // gives the merged night the earliest start and the latest end of the two.
            val laterStart = end + 45 * MINUTE_MS
            val laterEnd = laterStart + HOUR_MS
            store(
                night("ps:0a", listOf("a")),
                night("ps:0b", listOf("b"), startMs = laterStart, endMs = laterEnd),
                night("ps:0ab", listOf("a", "b"), endMs = laterEnd),
            )
            supersede("ps:0a", "ps:0b", by = "ps:0ab")

            val change = changed("${key("ps:0a")};${key("ps:0b", laterStart, laterEnd)}", null).single()

            assertEquals("ps:0ab", change.night.clientRecordId)
            assertEquals(start to laterEnd, change.night.sessionStartMs to change.night.sessionEndMs)
            assertEquals(start to end, change.sentStartMs to change.sentEndMs, "what Sleep as Android holds for the main night")
            assertEquals(setOf("ps:0a", "ps:0b"), change.replaced)
            assertTrue(change.otherSources)
        }

    @Test
    fun `nights of the request now in one of them keep the span that night was sent with`() =
        runTest {
            // The source deleted the session of the earlier night and extended the later one back over it.
            val laterStart = end + 45 * MINUTE_MS
            val laterEnd = laterStart + HOUR_MS
            store(
                night("ps:0a", listOf("a"), status = NightStatus.SOURCE_DELETED),
                night("ps:0b", listOf("b"), endMs = laterEnd),
            )

            val change = changed("${key("ps:0a")};${key("ps:0b", laterStart, laterEnd)}", null).single()

            assertEquals("ps:0b", change.night.clientRecordId)
            assertEquals(laterStart to laterEnd, change.sentStartMs to change.sentEndMs)
            assertEquals(setOf("ps:0a"), change.replaced)
        }

    @Test
    fun `a session the source recreated under another id is followed to the night built from it`() =
        runTest {
            // In one upload the source deleted the session and wrote it again with a later end: the night of the request
            // lost its sources, and the new one shares none with it.
            store(
                night("ps:0a", listOf("a"), status = NightStatus.SOURCE_DELETED),
                night("ps:0c", listOf("c"), endMs = end + 34 * MINUTE_MS),
            )

            val change = changed(key("ps:0a"), null).single()

            assertEquals("ps:0c", change.night.clientRecordId)
            assertEquals(end, change.sentEndMs)
            assertEquals(setOf("ps:0a"), change.replaced, "a notice of the night of the request is replaced")
            assertTrue(change.otherSources)
        }

    @Test
    fun `a merged night superseded after a source of it was deleted is followed to the night of the other source`() =
        runTest {
            // The source added a longer session "b" (A merged into B), then deleted "a": B gave way to C, which shares no
            // source with the night of the request.
            store(
                night("ps:0a", listOf("a")),
                night("ps:0ab", listOf("a", "b"), endMs = end + 34 * MINUTE_MS),
                night("ps:0b", listOf("b"), endMs = end + 34 * MINUTE_MS),
            )
            supersede("ps:0a", by = "ps:0ab")
            supersede("ps:0ab", by = "ps:0b")

            val change = changed(key("ps:0a"), null).single()

            assertEquals("ps:0b", change.night.clientRecordId)
            assertEquals(setOf("ps:0a", "ps:0ab"), change.replaced, "the notice of the merged night is replaced")
        }

    @Test
    fun `a night superseded by a night of another app is followed to it`() =
        runTest {
            store(
                night("ps:0a", listOf("a")),
                night("ps:0x", listOf("x"), startMs = start + 10 * MINUTE_MS, endMs = end + HOUR_MS).copy(sourcePackage = OTHER_APP),
            )
            supersede("ps:0a", by = "ps:0x")

            assertEquals(listOf("ps:0x"), changed(key("ps:0a"), null).map { it.night.clientRecordId })
        }

    @Test
    fun `a night the source removed is not followed to a night that overlaps it less than half`() =
        runTest {
            store(
                night("ps:0a", listOf("a"), status = NightStatus.SOURCE_DELETED),
                night("ps:0n", listOf("n"), startMs = end - HOUR_MS, endMs = end + 2 * HOUR_MS),
            )

            assertEquals(emptyList<ChangedNight>(), changed(key("ps:0a"), null))
        }

    @Test
    fun `a night the user deleted is never followed`() =
        runTest {
            store(
                night("ps:0a", listOf("a"), status = NightStatus.DELETED),
                night("ps:0c", listOf("c"), endMs = end + HOUR_MS),
            )

            assertEquals(emptyList<ChangedNight>(), changed(key("ps:0a"), null))
        }

    @Test
    fun `the notified versions are those the notices show, without the withdrawn ones, sorted, and null without any`() =
        runTest {
            store(night("ps:0b", listOf("b"), endMs = end + HOUR_MS), night("ps:0a", listOf("a"), endMs = end + HOUR_MS))
            val found = sentNights.check("${key("ps:0a")};${key("ps:0b")}", null)

            assertEquals(
                "${key("ps:0a", endMs = end + HOUR_MS)};${key("ps:0b", endMs = end + HOUR_MS)}",
                SentNights.noticedAfter(key("ps:0a"), found),
                "the new version of ps:0a replaces its notice",
            )
            assertEquals(key("ps:0a"), SentNights.noticedAfter(key("ps:0a"), SentNightsCheck.NONE))
            assertEquals(
                key("ps:0b"),
                SentNights.noticedAfter("${key("ps:0a")};${key("ps:0b")}", SentNightsCheck(emptyList(), listOf(key("ps:0a")))),
            )
            assertNull(SentNights.noticedAfter(null, SentNightsCheck.NONE))
            assertNull(SentNights.noticedAfter("", SentNightsCheck.NONE))
        }

    @Test
    fun `nights of the request merged into one keep every span sent, and the repair covers them all`() =
        runTest {
            val laterStart = end + 45 * MINUTE_MS
            val laterEnd = laterStart + 2 * HOUR_MS
            store(
                night("ps:0a", listOf("a")),
                night("ps:0b", listOf("b"), startMs = laterStart, endMs = laterEnd),
                night("ps:0ab", listOf("a", "b"), endMs = laterEnd),
            )
            supersede("ps:0a", "ps:0b", by = "ps:0ab")

            val change = changed("${key("ps:0b", laterStart, laterEnd)};${key("ps:0a")}", null).single()

            assertEquals(listOf(SentSpan(start, end), SentSpan(laterStart, laterEnd)), change.sentSpans)
            assertEquals(start to laterEnd, change.repairStartMs to change.repairEndMs, "the records of both nights")
        }

    @Test
    fun `the repair covers the night as it is now and as it was sent`() =
        runTest {
            // Now shorter at the end and longer at the start: the record Sleep as Android holds reaches past the new end.
            store(night("ps:0a", listOf("a"), startMs = start - 20 * MINUTE_MS, endMs = end - HOUR_MS))

            val change = changed(key("ps:0a"), null).single()

            assertEquals(listOf(SentSpan(start, end)), change.sentSpans)
            assertEquals(start - 20 * MINUTE_MS to end, change.repairStartMs to change.repairEndMs)
        }

    @Test
    fun `a session the source recreated twice replaces the notice of the first night built from it`() =
        runTest {
            store(
                night("ps:0a", listOf("a"), status = NightStatus.SOURCE_DELETED),
                night("ps:0c", listOf("c"), endMs = end + 34 * MINUTE_MS, status = NightStatus.SOURCE_DELETED),
                night("ps:0d", listOf("d"), endMs = end + 50 * MINUTE_MS),
            )

            val change = changed(key("ps:0a"), key("ps:0c", endMs = end + 34 * MINUTE_MS)).single()

            assertEquals("ps:0d", change.night.clientRecordId)
            assertEquals(setOf("ps:0a", "ps:0c"), change.replaced, "the notice of ps:0c goes")
            assertFalse(change.silent, "other times than the notice it replaces")
        }

    @Test
    fun `a change with the times of a notice it replaces is silent`() =
        runTest {
            // The source wrote the session again with the times it already had after the change.
            store(
                night("ps:0a", listOf("a"), status = NightStatus.SOURCE_DELETED),
                night("ps:0c", listOf("c"), endMs = end + 34 * MINUTE_MS, status = NightStatus.SOURCE_DELETED),
                night("ps:0d", listOf("d"), endMs = end + 34 * MINUTE_MS),
            )

            val change = changed(key("ps:0a"), key("ps:0c", endMs = end + 34 * MINUTE_MS)).single()

            assertEquals("ps:0d", change.night.clientRecordId)
            assertTrue(change.silent)
            assertFalse(changed(key("ps:0a"), null).single().silent, "nothing notified before")
        }

    @Test
    fun `a merged night folded back into the night of the request with the same times is silent`() =
        runTest {
            store(
                night("ps:0a", listOf("a"), endMs = end + 2 * HOUR_MS),
                night("ps:0ab", listOf("a", "b"), endMs = end + 2 * HOUR_MS),
            )
            supersede("ps:0ab", by = "ps:0a")

            val change = changed(key("ps:0a"), key("ps:0ab", endMs = end + 2 * HOUR_MS)).single()

            assertEquals("ps:0a", change.night.clientRecordId)
            assertEquals(setOf("ps:0ab"), change.replaced)
            assertTrue(change.silent)
        }

    @Test
    fun `a notified night back at the span sent is withdrawn`() =
        runTest {
            store(night("ps:0a", listOf("a")))
            val noticed = key("ps:0a", endMs = end + 34 * MINUTE_MS)

            val found = sentNights.check(key("ps:0a"), noticed)

            assertEquals(emptyList<ChangedNight>(), found.changes)
            assertEquals(listOf(noticed), found.withdrawn)
            assertEquals(listOf("ps:0a"), found.withdrawnTags)
            assertNull(SentNights.noticedAfter(noticed, found), "a later change to those times is notified again")
        }

    @Test
    fun `the notice of a merged night is withdrawn when the night of the request is back at the span sent`() =
        runTest {
            store(
                night("ps:0a", listOf("a")),
                night("ps:0ab", listOf("a", "b"), endMs = end + 2 * HOUR_MS),
                night("ps:0z", listOf("z"), startMs = end + DAY_MS, endMs = end + DAY_MS + HOUR_MS),
            )
            supersede("ps:0ab", by = "ps:0a")
            val noticed = "${key("ps:0ab", endMs = end + 2 * HOUR_MS)};${key("ps:0z", end + DAY_MS, end + DAY_MS + 2 * HOUR_MS)}"

            val found = sentNights.check(key("ps:0a"), noticed)

            assertEquals(listOf(key("ps:0ab", endMs = end + 2 * HOUR_MS)), found.withdrawn, "not the notice of another night")
        }

    @Test
    fun `only the notices of the nights back at their span are withdrawn`() =
        runTest {
            // Two nights of the request, both notified: the earlier is back at its span, the later changed again.
            val laterStart = end + 3 * HOUR_MS
            store(
                night("ps:0a", listOf("a")),
                night("ps:0b", listOf("b"), startMs = laterStart, endMs = laterStart + 2 * HOUR_MS),
            )
            val noticed = "${key("ps:0a", endMs = end + HOUR_MS)};${key("ps:0b", laterStart, laterStart + 90 * MINUTE_MS)}"

            val found = sentNights.check("${key("ps:0a")};${key("ps:0b", laterStart, laterStart + HOUR_MS)}", noticed)

            assertEquals(listOf("ps:0b"), found.changes.map { it.night.clientRecordId })
            assertEquals(listOf(key("ps:0a", endMs = end + HOUR_MS)), found.withdrawn)
            assertEquals(key("ps:0b", laterStart, laterStart + 2 * HOUR_MS), SentNights.noticedAfter(noticed, found))
        }

    @Test
    fun `a night rewritten back to a version notified before the notice it shows now is notified again`() =
        runTest {
            // Sent at A, then rewritten in place to B (notified), to C (its notice now shows C) and back to B.
            val sent = key("ps:0a")
            store(night("ps:0a", listOf("a"), endMs = end + HOUR_MS))
            var noticed = SentNights.noticedAfter(null, sentNights.check(sent, null))
            store(night("ps:0a", listOf("a"), endMs = end + 2 * HOUR_MS))
            noticed = SentNights.noticedAfter(noticed, sentNights.check(sent, noticed))
            assertEquals(key("ps:0a", endMs = end + 2 * HOUR_MS), noticed, "only the version its notice shows")

            store(night("ps:0a", listOf("a"), endMs = end + HOUR_MS))
            val found = sentNights.check(sent, noticed)

            val change = found.changes.single()
            assertEquals(end + HOUR_MS, change.night.sessionEndMs, "the notice shows B again")
            assertEquals(emptySet<String>(), change.replaced, "under its own tag, which alerts once while it is shown")
            assertEquals(key("ps:0a", endMs = end + HOUR_MS), SentNights.noticedAfter(noticed, found))
        }

    @Test
    fun `a night notified before a merge is notified again when the merged night gives way to it`() =
        runTest {
            // Sent at A and rewritten in place to B (notified); merged with a fragment into ps:0af (notified, which
            // cancels the notice of ps:0a); then the fragment was deleted and ps:0a is written again at B.
            val sent = key("ps:0a")
            store(night("ps:0a", listOf("a"), endMs = end + HOUR_MS))
            var noticed = SentNights.noticedAfter(null, sentNights.check(sent, null))
            store(night("ps:0af", listOf("a", "f"), endMs = end + 2 * HOUR_MS))
            supersede("ps:0a", by = "ps:0af")
            val merged = sentNights.check(sent, noticed)
            assertEquals(listOf("ps:0af"), merged.changes.map { it.night.clientRecordId })
            noticed = SentNights.noticedAfter(noticed, merged)
            assertEquals(key("ps:0af", endMs = end + 2 * HOUR_MS), noticed, "the notice of ps:0a was cancelled")

            store(night("ps:0a", listOf("a"), endMs = end + HOUR_MS))
            supersede("ps:0af", by = "ps:0a")
            val found = sentNights.check(sent, noticed)

            val change = found.changes.single()
            assertEquals("ps:0a", change.night.clientRecordId, "the notice opens a night that can be recomputed")
            assertEquals(end + HOUR_MS, change.night.sessionEndMs)
            assertEquals(setOf("ps:0af"), change.replaced, "the notice of the merged night goes")
            assertFalse(change.silent, "other times than the notice it replaces")
            assertEquals(key("ps:0a", endMs = end + HOUR_MS), SentNights.noticedAfter(noticed, found))
        }
}
