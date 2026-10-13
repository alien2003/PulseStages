package io.github.alien2003.pulsestages.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * `--name value` options of one command. Every option takes exactly one value; unknown or repeated options are usage
 * errors.
 */
internal class Options(
    private val command: String,
    args: List<String>,
    allowed: Set<String>,
) {
    private val values: Map<String, String>

    init {
        val parsed = LinkedHashMap<String, String>()
        var i = 0
        while (i < args.size) {
            val name = args[i]
            if (!name.startsWith("--") || name.removePrefix("--") !in allowed) usage("unknown option '$name' for $command")
            if (i + 1 >= args.size) usage("option $name needs a value")
            if (parsed.put(name.removePrefix("--"), args[i + 1]) != null) usage("option $name given twice")
            i += 2
        }
        values = parsed
    }

    /** The value of `--name`, or null. */
    operator fun get(name: String): String? = values[name]

    /** The value of `--name`; a usage error when it is missing. */
    fun required(name: String): String = values[name] ?: usage("$command needs --$name")

    /** `--name` as a path, or null. */
    fun path(name: String): Path? = values[name]?.let { Paths.get(it) }

    /** `--name` as a path; a usage error when it is missing. */
    fun requiredPath(name: String): Path = Paths.get(required(name))

    private fun usage(message: String): Nothing = throw CliException(message, ExitCodes.USAGE)
}

/** File access with CLI exit codes. */
internal object CliFiles {
    /** The whole text of [path]. */
    fun readText(path: Path): String =
        try {
            Files.readString(path)
        } catch (e: NoSuchFileException) {
            throw CliException("no such file: ${e.file}", ExitCodes.NO_INPUT, e)
        } catch (e: IOException) {
            throw CliException("cannot read $path: ${e.message}", ExitCodes.NO_INPUT, e)
        }

    /** Writes [text] to [path] (parent directories are created). */
    fun writeText(
        path: Path,
        text: String,
    ) {
        writer(path).use { it.write(text) }
    }

    /** A buffered UTF-8 writer for [path] (parent directories are created). */
    fun writer(path: Path): java.io.BufferedWriter =
        try {
            path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.newBufferedWriter(path)
        } catch (e: IOException) {
            throw CliException("cannot write $path: ${e.message}", ExitCodes.IO_ERROR, e)
        }
}
