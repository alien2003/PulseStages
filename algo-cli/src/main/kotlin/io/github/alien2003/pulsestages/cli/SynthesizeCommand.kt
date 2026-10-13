package io.github.alien2003.pulsestages.cli

import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticOptions
import java.util.Locale

/**
 * `synthesize --out <dir> [--count N] [--seed S] [--subjects K]`: writes N labelled synthetic nights (seeds S, S+1, …)
 * as `<id>.night.json` files, assigned round-robin to K subjects, one day apart. For smoke tests of the training
 * pipeline only; synthetic data must never be used to choose parameters.
 */
internal object SynthesizeCommand {
    private const val DEFAULT_COUNT = 10
    private const val DAY_MS = 86_400_000L

    /** Runs `synthesize` with the options [args]; returns the exit code. */
    fun run(
        args: List<String>,
        out: Appendable,
    ): Int {
        val options = Options("synthesize", args, setOf("out", "count", "seed", "subjects"))
        val directory = options.requiredPath("out")
        val count = positiveInt(options, "count") ?: DEFAULT_COUNT
        val seed = options["seed"]?.let { it.toLongOrNull() ?: throw CliException("--seed must be an integer", ExitCodes.USAGE) } ?: 1L
        val subjects = positiveInt(options, "subjects") ?: count
        for (i in 0 until count) {
            val start = SyntheticOptions.DEFAULT_SESSION_START_MS + i * DAY_MS
            val night = SyntheticNightGenerator(seed + i).generate(SyntheticOptions(sessionStartMs = start))
            val id = String.format(Locale.ROOT, "synthetic-%05d", seed + i)
            val subject = String.format(Locale.ROOT, "s%03d", i % subjects)
            val record = NightRecord(id, subject, "synthetic", night.input, night.truth)
            CliFiles.writeText(directory.resolve("$id.night.json"), NightFiles.toJson(record).toString() + "\n")
        }
        out.appendLine("synthesize: $count nights -> $directory")
        return ExitCodes.OK
    }

    private fun positiveInt(
        options: Options,
        name: String,
    ): Int? =
        options[name]?.let { value ->
            value.toIntOrNull()?.takeIf { it > 0 } ?: throw CliException("--$name must be a positive integer", ExitCodes.USAGE)
        }
}
