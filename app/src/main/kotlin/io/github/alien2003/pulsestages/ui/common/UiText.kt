package io.github.alien2003.pulsestages.ui.common

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalResources
import java.util.concurrent.atomic.AtomicLong

/**
 * Text that a ViewModel hands to the UI without touching Android resources: a string or plural resource with
 * arguments (which may be [UiText] themselves) or a verbatim value (package names, file names, numbers).
 */
@Immutable
sealed interface UiText {
    /**
     * A string resource.
     *
     * @property id the resource.
     * @property args format arguments; [UiText] arguments are resolved first.
     */
    data class Res(
        @param:StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    /**
     * A plural resource.
     *
     * @property id the resource.
     * @property count selects the plural form.
     * @property args format arguments; [UiText] arguments are resolved first.
     */
    data class Plural(
        @param:PluralsRes val id: Int,
        val count: Int,
        val args: List<Any> = listOf(count),
    ) : UiText

    /**
     * Text shown as it is.
     *
     * @property value the text.
     */
    data class Verbatim(
        val value: String,
    ) : UiText

    /** Constructors. */
    companion object {
        /** A string resource with [args]. */
        fun of(
            @StringRes id: Int,
            vararg args: Any,
        ): UiText = Res(id, args.toList())

        /** A plural resource for [count]; the arguments default to the count itself. */
        fun plural(
            @PluralsRes id: Int,
            count: Int,
            vararg args: Any,
        ): UiText = Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())
    }
}

/**
 * Resolves this text with [resources]. A text without arguments is read as it is: `getString(id, *emptyArray())` would
 * still run `String.format` and throw on a literal `%` (the unit "%", marked `formatted="false"`). A text with
 * arguments is formatted, so its resource must write a literal percent sign as `%%`.
 */
@Suppress("SpreadOperator") // Resources.getString takes Java varargs; the arrays are a handful of elements.
fun UiText.resolve(resources: Resources): String =
    when (this) {
        is UiText.Res -> {
            if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args.resolveArgs(resources))
        }

        is UiText.Plural -> {
            if (args.isEmpty()) {
                resources.getQuantityString(id, count)
            } else {
                resources.getQuantityString(id, count, *args.resolveArgs(resources))
            }
        }

        is UiText.Verbatim -> {
            value
        }
    }

/** Resolves this text with the resources of the current context. */
@Composable
fun UiText.resolve(): String = resolve(LocalResources.current)

private fun List<Any>.resolveArgs(resources: Resources): Array<Any> = map { if (it is UiText) it.resolve(resources) else it }.toTypedArray()

/**
 * A one-off message for a Snackbar. ViewModels queue messages in their UI state and the screen removes each one
 * after showing it (UI-state based events survive configuration changes).
 *
 * @property text what to show.
 * @property action label of the Snackbar's action button, or null for none (the screen reports a tap on it).
 * @property id unique id used to acknowledge the message.
 */
@Immutable
data class UiMessage(
    val text: UiText,
    val action: UiText? = null,
    val id: Long = nextMessageId.incrementAndGet(),
)

private val nextMessageId = AtomicLong()
