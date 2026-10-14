package io.github.alien2003.pulsestages.e2e.ui

import io.github.alien2003.pulsestages.e2e.E2e

/**
 * Texts of the INSTALLED app, looked up by resource name at run time: the test never relies on resource ids compiled
 * into the test APK, so it always reads (and matches) exactly what the installed app shows, in the phone's language.
 * An unknown name fails the test.
 */
object AppText {
    /** The id of the resource [name] of [type] in the installed app. */
    fun id(
        name: String,
        type: String = "string",
    ): Int {
        val id = E2e.app.resources.getIdentifier(name, type, E2e.APP_PACKAGE)
        check(id != 0) { "the installed app has no $type resource named $name" }
        return id
    }

    /** The string [name], formatted with [args]. */
    fun string(
        name: String,
        vararg args: Any,
    ): String = if (args.isEmpty()) E2e.app.getString(id(name)) else E2e.app.getString(id(name), *args)

    /**
     * The fixed text of the string [name] before its first format argument (the whole text when it has none), e.g.
     * "Remove " for "Remove %1$s".
     */
    fun prefix(name: String): String {
        val raw = E2e.app.resources.getString(id(name))
        val placeholder = raw.indexOf('%')
        return if (placeholder < 0) raw else raw.substring(0, placeholder)
    }

    /** Whether the string [name] has format arguments. */
    fun isFormat(name: String): Boolean =
        E2e.app.resources
            .getString(id(name))
            .contains('%')
}
