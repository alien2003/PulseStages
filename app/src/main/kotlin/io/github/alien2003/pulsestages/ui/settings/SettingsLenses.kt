package io.github.alien2003.pulsestages.ui.settings

import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.FeatureParams
import io.github.alien2003.pulsestages.algo.params.HmmParams
import io.github.alien2003.pulsestages.algo.params.HybridParams
import io.github.alien2003.pulsestages.algo.params.PersonalParams
import io.github.alien2003.pulsestages.algo.params.PostprocessParams
import io.github.alien2003.pulsestages.algo.params.PreprocessParams
import io.github.alien2003.pulsestages.algo.params.RulesParams
import io.github.alien2003.pulsestages.data.local.settings.AppSettings

// Copy helpers that replace one parameter group of the settings (used by the setting specs' write functions).

/** Applies [f] to the algorithm parameters. */
internal inline fun AppSettings.algo(f: (AlgoParams) -> AlgoParams): AppSettings = copy(algo = f(algo))

/** Applies [f] to the preprocessing parameters. */
internal inline fun AppSettings.pre(f: (PreprocessParams) -> PreprocessParams): AppSettings =
    copy(algo = algo.copy(preprocess = f(algo.preprocess)))

/** Applies [f] to the feature parameters. */
internal inline fun AppSettings.features(f: (FeatureParams) -> FeatureParams): AppSettings =
    copy(algo = algo.copy(features = f(algo.features)))

/** Applies [f] to the HMM parameters. */
internal inline fun AppSettings.hmm(f: (HmmParams) -> HmmParams): AppSettings = copy(algo = algo.copy(hmm = f(algo.hmm)))

/** Applies [f] to the rules parameters. */
internal inline fun AppSettings.rules(f: (RulesParams) -> RulesParams): AppSettings = copy(algo = algo.copy(rules = f(algo.rules)))

/** Applies [f] to the hybrid parameters. */
internal inline fun AppSettings.hybrid(f: (HybridParams) -> HybridParams): AppSettings = copy(algo = algo.copy(hybrid = f(algo.hybrid)))

/** Applies [f] to the post-processing parameters. */
internal inline fun AppSettings.post(f: (PostprocessParams) -> PostprocessParams): AppSettings = copy(algo = algo.copy(post = f(algo.post)))

/** Applies [f] to the personalization parameters. */
internal inline fun AppSettings.personal(f: (PersonalParams) -> PersonalParams): AppSettings =
    copy(algo = algo.copy(personal = f(algo.personal)))
