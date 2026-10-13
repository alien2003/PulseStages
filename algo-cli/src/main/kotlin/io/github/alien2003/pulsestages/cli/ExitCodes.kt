package io.github.alien2003.pulsestages.cli

/** Exit codes of the CLI (64–74 follow sysexits.h). */
object ExitCodes {
    /** Success. */
    const val OK = 0

    /** `estimate`: the night was not estimated (no heart rate, low coverage). */
    const val NOT_ESTIMATED = 2

    /** EX_USAGE: wrong command line. */
    const val USAGE = 64

    /** EX_DATAERR: an input file is malformed or inconsistent. */
    const val DATA_ERROR = 65

    /** EX_NOINPUT: an input file does not exist or cannot be read. */
    const val NO_INPUT = 66

    /** EX_IOERR: an output file cannot be written. */
    const val IO_ERROR = 74
}

/**
 * An error that ends the command; its message is printed to stderr.
 *
 * @property exitCode the process exit code (see [ExitCodes]).
 */
class CliException(
    message: String,
    val exitCode: Int,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
