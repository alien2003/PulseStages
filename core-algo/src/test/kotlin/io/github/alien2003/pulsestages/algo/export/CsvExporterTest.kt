package io.github.alien2003.pulsestages.algo.export

import io.github.alien2003.pulsestages.algo.SleepStager
import io.github.alien2003.pulsestages.algo.features.BaseFeatures
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class CsvExporterTest {
    private val night = SyntheticNightGenerator(5).generate(SyntheticOptions(longGapMin = 30))
    private val estimate = SleepStager().estimateWith(EstimatorType.HMM, night.input, AlgoParams())

    @Test
    fun `header lists the fixed columns around the feature names`() {
        val header = CsvExporter.export(estimate).lineSequence().first()

        assertEquals(
            "epoch,start_ms,has_data,hr_raw,hr_smooth,hrNorm,hrVar,hrSlope,t,cyclePhase,steps,forcedAwake,spo2Drop,hrvVar,respVar," +
                "p_awake,p_light,p_deep,p_rem,stage,mode",
            header,
        )
        assertEquals(BaseFeatures.NAMES, estimate.featureNames)
    }

    @Test
    fun `the mode column says NAP on every row of a nap`() {
        val nap = night.input.copy(sessionEndMs = night.input.sessionStartMs + 60 * 60_000L)
        val napEstimate = SleepStager().estimateWith(EstimatorType.HMM, nap, AlgoParams())
        val rows =
            CsvExporter
                .export(napEstimate)
                .trimEnd('\n')
                .split('\n')
                .drop(1)

        assertEquals(SessionMode.NAP, napEstimate.mode)
        assertTrue(rows.isNotEmpty() && rows.all { it.endsWith(",NAP") })
    }

    @Test
    fun `one row per session epoch with a constant column count`() {
        val lines = CsvExporter.export(estimate).trimEnd('\n').split('\n')
        val columns = lines.first().split(',').size

        assertEquals(estimate.perEpoch.size + 1, lines.size)
        lines.forEach { assertEquals(columns, it.split(',').size, it) }
    }

    @Test
    fun `values round-trip, missing values are empty and the locale does not matter`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val rows =
                CsvExporter
                    .export(estimate)
                    .trimEnd('\n')
                    .split('\n')
                    .drop(1)
                    .map { it.split(',') }
            val header = CsvExporter.header(estimate.featureNames).split(',')
            val gapRow = rows.first { it[header.indexOf("has_data")] == "0" }
            val dataRow = rows.first { it[header.indexOf("has_data")] == "1" }
            val epoch = estimate.perEpoch[dataRow[0].toInt()]

            assertEquals("", gapRow[header.indexOf("hr_raw")])
            assertEquals("", gapRow[header.indexOf("hrNorm")])
            assertEquals(epoch.hrRaw, dataRow[header.indexOf("hr_raw")].toDouble())
            assertEquals(epoch.posterior.deep, dataRow[header.indexOf("p_deep")].toDouble())
            assertEquals(epoch.stage, SleepStage.valueOf(dataRow[header.indexOf("stage")]))
            assertTrue(rows.all { it.last() == SessionMode.NIGHT.name })
            assertTrue(rows.all { row -> row.none { it.contains(';') } })
        } finally {
            Locale.setDefault(previous)
        }
    }
}
