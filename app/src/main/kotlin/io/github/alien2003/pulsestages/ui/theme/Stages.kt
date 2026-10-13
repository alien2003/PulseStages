package io.github.alien2003.pulsestages.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import io.github.alien2003.pulsestages.algo.model.SleepStage

/** Colour of [stage] in hypnograms and stage-share bars, taken from the current colour scheme. */
fun ColorScheme.stageColor(stage: SleepStage): Color =
    when (stage) {
        SleepStage.AWAKE -> error
        SleepStage.REM -> tertiary
        SleepStage.LIGHT -> primary.copy(alpha = 0.55f)
        SleepStage.DEEP -> primary
        SleepStage.UNKNOWN -> outlineVariant
    }
