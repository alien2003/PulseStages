package io.github.alien2003.pulsestages.ui.common

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.work.run.OutcomeKind
import io.github.alien2003.pulsestages.work.run.RunReport
import io.github.alien2003.pulsestages.work.run.RunSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Resolves the result of "Estimate new" with nights that the automatic refresh rewrote against the app's real
 * resources, in English and Russian: every plural form of the refreshed sentence formats, it follows the summary as a
 * second sentence (before the failed nights), the sync request to Sleep as Android, sent or waiting for complete
 * nights, comes after it, and no format placeholder is left.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class)
class WorkResultTextsTest(
    private val language: String,
) {
    private val resources: Resources by lazy {
        val application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        application.createConfigurationContext(configuration).resources
    }

    private fun resolved(summary: RunSummary): String =
        WorkResultText.report(RunSubject.ESTIMATE, RunReport(OutcomeKind.COMPLETED, summary, null, null, null)).resolve(resources)

    /** The first sentence of "Estimate new" with one night estimated. */
    private fun summaryText(
        found: Int,
        skipped: Int,
    ): String {
        val estimated = resources.getQuantityString(R.plurals.result_estimated, 1, 1)
        return resources.getString(R.string.result_summary_estimate, estimated, found, skipped)
    }

    @Test
    fun `nights updated with late data are a second sentence in every plural form`() {
        val summary = summaryText(found = 3, skipped = 2)
        // 1, 2, 5 and 21 select the forms one, few, many and one of Russian; English has one and other.
        listOf(1, 2, 5, 21).forEach { count ->
            val text = resolved(RunSummary(found = 3, estimated = 1, skipped = 2, refreshed = count))

            assertEquals("$summary ${resources.getQuantityString(R.plurals.result_refreshed, count, count)}", text)
            assertFalse("[$language] unfilled placeholder in \"$text\"", PLACEHOLDER.containsMatchIn(text))
        }
        assertEquals(summary, resolved(RunSummary(found = 3, estimated = 1, skipped = 2)))
        val forms = listOf(1, 2, 5).map { resources.getQuantityString(R.plurals.result_refreshed, it, it).substringAfter(' ') }
        assertEquals("[$language] distinct forms for 1, 2 and 5: $forms", if (language == "ru") 3 else 2, forms.toSet().size)
    }

    @Test
    fun `failed nights stay the last sentence`() {
        val text = resolved(RunSummary(found = 3, estimated = 1, skipped = 1, failed = 1, refreshed = 2))

        val summary = summaryText(found = 3, skipped = 1)
        val refreshed = resources.getQuantityString(R.plurals.result_refreshed, 2, 2)
        assertEquals("$summary $refreshed ${resources.getQuantityString(R.plurals.result_failed_nights, 1, 1)}", text)
        assertFalse("[$language] unfilled placeholder in \"$text\"", PLACEHOLDER.containsMatchIn(text))
        if (language == "en") {
            assertEquals(
                "Estimate new: 1 night estimated (found 3, skipped 1). 2 nights updated with late data. 1 night failed, see the log.",
                text,
            )
        }
    }

    @Test
    fun `the sync request to Sleep as Android follows the refreshed nights, failed nights stay last`() {
        val summary = summaryText(found = 3, skipped = 1)
        val saa = resources.getString(R.string.result_saa_sync_requested)

        assertEquals("$summary $saa", resolved(RunSummary(found = 3, estimated = 1, skipped = 1, sleepAsAndroidSyncRequested = true)))
        val text =
            resolved(RunSummary(found = 3, estimated = 1, skipped = 1, failed = 1, refreshed = 2, sleepAsAndroidSyncRequested = true))

        val refreshed = resources.getQuantityString(R.plurals.result_refreshed, 2, 2)
        assertEquals("$summary $refreshed $saa ${resources.getQuantityString(R.plurals.result_failed_nights, 1, 1)}", text)
        assertFalse("[$language] unfilled placeholder in \"$text\"", PLACEHOLDER.containsMatchIn(text))
        if (language == "en") {
            assertEquals(
                "Estimate new: 1 night estimated (found 3, skipped 1). 2 nights updated with late data. " +
                    "Sleep as Android was asked to sync. 1 night failed, see the log.",
                text,
            )
        }
    }

    @Test
    fun `a sync request to Sleep as Android that waits for complete nights is said in its place`() {
        val summary = summaryText(found = 3, skipped = 1)
        val deferred = resources.getString(R.string.result_saa_sync_deferred)

        assertEquals("$summary $deferred", resolved(RunSummary(found = 3, estimated = 1, skipped = 1, sleepAsAndroidSyncDeferred = true)))
        val text =
            resolved(RunSummary(found = 3, estimated = 1, skipped = 1, failed = 1, refreshed = 2, sleepAsAndroidSyncDeferred = true))

        val refreshed = resources.getQuantityString(R.plurals.result_refreshed, 2, 2)
        assertEquals("$summary $refreshed $deferred ${resources.getQuantityString(R.plurals.result_failed_nights, 1, 1)}", text)
        assertFalse("[$language] unfilled placeholder in \"$text\"", PLACEHOLDER.containsMatchIn(text))
        if (language == "en") {
            assertEquals(
                "Estimate new: 1 night estimated (found 3, skipped 1). 2 nights updated with late data. " +
                    "Sleep as Android will be asked to sync once the nights are complete. 1 night failed, see the log.",
                text,
            )
        }
    }

    private companion object {
        val PLACEHOLDER = Regex("""%(\d+\$)?[-#+0,(]*\d*(\.\d+)?[a-zA-Z]""")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun languages(): List<Array<Any>> = listOf(arrayOf("en"), arrayOf("ru"))
    }
}
