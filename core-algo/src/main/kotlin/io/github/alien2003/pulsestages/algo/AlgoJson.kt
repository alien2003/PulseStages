package io.github.alien2003.pulsestages.algo

import kotlinx.serialization.json.Json

/** Shared JSON configuration for algorithm inputs/outputs exchanged with the CLI and the training scripts. */
val AlgoJson: Json =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
