package io.github.alien2003.pulsestages.cli

import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticOptions
import io.github.alien2003.pulsestages.algo.testing.SyntheticHybridModels
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.file.Files
import java.nio.file.Path

/** Helpers for the CLI tests: synthetic night files, the test bundle and in-process runs. */
internal object CliTestSupport {
    const val DAY_MS = 86_400_000L

    class Result(
        val code: Int,
        val out: String,
        val err: String,
    )

    fun cli(vararg args: String): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val code = run(args.toList(), out, err)
        return Result(code, out.toString(), err.toString())
    }

    fun ok(vararg args: String): Result = cli(*args).also { assertEquals(ExitCodes.OK, it.code, "stderr: ${it.err}") }

    /** A labelled synthetic night (seed [seed], [index] days after the default start) for [subject]. */
    fun record(
        seed: Long,
        subject: String,
        index: Int = 0,
        options: SyntheticOptions = SyntheticOptions(),
    ): NightRecord {
        val night =
            SyntheticNightGenerator(seed).generate(
                options.copy(sessionStartMs = SyntheticOptions.DEFAULT_SESSION_START_MS + index * DAY_MS),
            )
        return NightRecord("night-$seed", subject, "synthetic", night.input, night.truth)
    }

    /** Writes [records] as `<id>.night.json` files into [directory]. */
    fun writeNights(
        directory: Path,
        records: List<NightRecord>,
    ): Path {
        Files.createDirectories(directory)
        for (record in records) Files.writeString(directory.resolve("${record.id}.night.json"), NightFiles.toJson(record).toString())
        return directory
    }

    /** The synthetic test bundle written to [file]. */
    fun writeModel(file: Path): Path {
        Files.writeString(file, SyntheticHybridModels.default.toJson())
        return file
    }

    fun readJson(file: Path): JsonObject = Json.parseToJsonElement(Files.readString(file)).jsonObject
}
