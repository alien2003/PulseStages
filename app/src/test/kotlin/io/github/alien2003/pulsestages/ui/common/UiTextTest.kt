package io.github.alien2003.pulsestages.ui.common

import android.app.Application
import android.content.res.Resources
import io.github.alien2003.pulsestages.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** [UiText.resolve] formats a resource only when it has arguments. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en")
class UiTextTest {
    private val resources: Resources get() = RuntimeEnvironment.getApplication().resources

    @Test
    fun `a text without arguments is not formatted, so a literal percent sign is kept`() {
        assertEquals("%", UiText.of(R.string.unit_percent).resolve(resources))
    }

    @Test
    fun `a text with arguments is formatted, nested texts first`() {
        assertEquals("23\u00A0%", UiText.of(R.string.format_percent, 23).resolve(resources))
        assertEquals("42 %", UiText.of(R.string.format_value_unit, "42", UiText.of(R.string.unit_percent)).resolve(resources))
    }

    @Test
    fun `a plural is formatted with its count by default and read as it is without arguments`() {
        val formatted = UiText.plural(R.plurals.settings_stale_nights, 3).resolve(resources)
        assertEquals(resources.getQuantityString(R.plurals.settings_stale_nights, 3, 3), formatted)
        val unformatted = UiText.Plural(R.plurals.settings_stale_nights, 1, emptyList()).resolve(resources)
        assertEquals(resources.getQuantityString(R.plurals.settings_stale_nights, 1), unformatted)
    }

    @Test
    fun `a verbatim text is shown as it is`() {
        assertEquals("100% of %1\$s", UiText.Verbatim("100% of %1\$s").resolve(resources))
    }
}
