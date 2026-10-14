package io.github.alien2003.pulsestages.ui.settings

import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.params.DurationModel
import io.github.alien2003.pulsestages.algo.params.LowConfidencePolicy
import io.github.alien2003.pulsestages.algo.params.NapHybridParams
import io.github.alien2003.pulsestages.algo.params.NapModeParams
import io.github.alien2003.pulsestages.algo.params.NapOutputPolicy
import io.github.alien2003.pulsestages.algo.params.NapPostprocessParams
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsLimits
import io.github.alien2003.pulsestages.ui.common.UiText

// The "Naps" group: every field of AlgoParams.napMode (core-algo NapModeParams).

private const val NAP = "algo.napMode"

/** Applies [f] to the nap mode. */
private inline fun AppSettings.nap(f: (NapModeParams) -> NapModeParams): AppSettings = copy(algo = algo.copy(napMode = f(algo.napMode)))

/** Applies [f] to the nap post-processing overrides. */
private inline fun AppSettings.napPost(f: (NapPostprocessParams) -> NapPostprocessParams): AppSettings = nap { it.copy(post = f(it.post)) }

/** Applies [f] to the nap decoder overrides. */
private inline fun AppSettings.napHybrid(f: (NapHybridParams) -> NapHybridParams): AppSettings = nap { it.copy(hybrid = f(it.hybrid)) }

/** Key of the "Same as nights" option of the nap overrides (null in the parameters). */
internal const val SAME_AS_NIGHTS_KEY: String = "SAME_AS_NIGHTS"

/** An optional nap override: empty = the night's value ([inherited]). */
private fun napOverride(
    path: String,
    texts: Pair<Int, Int>,
    read: (AppSettings) -> Double?,
    write: (AppSettings, Double?) -> AppSettings,
    inherited: (AppSettings) -> Double,
    bounds: UiText = BOUNDS_NON_NEGATIVE,
    unit: UiText? = null,
): SettingsItem = SettingsItem.Field(OptionalDoubleSpec(path, text(texts.first), text(texts.second), read, write, inherited, bounds, unit))

/** A share (0..1) override of naps. */
private fun napShare(
    path: String,
    texts: Pair<Int, Int>,
    read: (AppSettings) -> Double?,
    write: (AppSettings, Double?) -> AppSettings,
    inherited: (AppSettings) -> Double,
): SettingsItem = napOverride(path, texts, read, write, inherited, boundsRange(0, 1))

/** "Same as nights" followed by the options of an enum override. */
private fun withSameAsNights(options: List<ChoiceOption>): List<ChoiceOption> =
    listOf(ChoiceOption(SAME_AS_NIGHTS_KEY, text(R.string.option_same_as_nights))) + options

/** The key of a nullable enum override (null = [SAME_AS_NIGHTS_KEY]). */
private fun <E : Enum<E>> keyOf(value: E?): String = value?.name ?: SAME_AS_NIGHTS_KEY

/**
 * Sessions shorter than the nap threshold, the everyday choices: the threshold, what is written for a nap and its
 * estimator. The algorithm values of naps are in the collapsed [napsAlgorithmSection].
 */
internal val napsSection =
    SettingsSection(
        id = "naps",
        title = text(R.string.settings_section_naps),
        summary = text(R.string.settings_section_naps_summary),
        expandedByDefault = true,
        items =
            listOf(
                numberField(
                    "$NAP.maxSessionMin",
                    R.string.set_nap_max_session_title,
                    R.string.set_nap_max_session_hint,
                    { it.algo.napMode.maxSessionMin },
                    { s, v -> s.nap { it.copy(maxSessionMin = v) } },
                    boundsRange(0, SettingsLimits.napMaxSessionMin.endInclusive.toInt()),
                    UNIT_MIN,
                ),
                SettingsItem.Field(
                    ChoiceSpec(
                        path = "$NAP.outputPolicy",
                        title = text(R.string.set_nap_output_title),
                        hint = text(R.string.set_nap_output_hint),
                        options =
                            enumOptions<NapOutputPolicy> {
                                when (it) {
                                    NapOutputPolicy.STAGES -> R.string.option_nap_output_stages
                                    NapOutputPolicy.SLEEP_WAKE -> R.string.option_nap_output_sleep_wake
                                    NapOutputPolicy.SKIP -> R.string.option_nap_output_skip
                                }
                            },
                        // A dropdown: "Asleep / awake" (and its translation) does not fit a third of a phone's width.
                        style = ChoiceStyle.DROPDOWN,
                        read = { it.algo.napMode.outputPolicy.name },
                        write = { s, v -> s.nap { it.copy(outputPolicy = NapOutputPolicy.valueOf(v)) } },
                    ),
                ),
                SettingsItem.Field(
                    ChoiceSpec(
                        path = "$NAP.estimator",
                        title = text(R.string.set_nap_estimator_title),
                        hint = text(R.string.set_nap_estimator_hint),
                        options =
                            withSameAsNights(
                                enumOptions<EstimatorType> {
                                    when (it) {
                                        EstimatorType.RULES -> R.string.estimator_rules
                                        EstimatorType.HMM -> R.string.estimator_hmm
                                        EstimatorType.HYBRID -> R.string.estimator_hybrid
                                    }
                                },
                            ),
                        style = ChoiceStyle.DROPDOWN,
                        read = { keyOf(it.algo.napMode.estimator) },
                        write = { s, v ->
                            s.nap { it.copy(estimator = v.takeIf { key -> key != SAME_AS_NIGHTS_KEY }?.let(EstimatorType::valueOf)) }
                        },
                    ),
                ),
            ),
    )

/** The algorithm values of naps (collapsed, like the other algorithm groups): post-processing and decoder overrides. */
internal val napsAlgorithmSection =
    SettingsSection(
        id = "naps_algorithm",
        title = text(R.string.settings_section_naps_algorithm),
        summary = text(R.string.settings_section_naps_algorithm_summary),
        expandedByDefault = false,
        items =
            listOf(
                subheader(R.string.settings_sub_nap_post),
                switchSetting(
                    "$NAP.post.checkProportions",
                    R.string.set_nap_check_proportions_title,
                    R.string.set_nap_check_proportions_hint,
                    { it.algo.napMode.post.checkProportions },
                    { s, v -> s.napPost { it.copy(checkProportions = v) } },
                ),
                napOverride(
                    "$NAP.post.minDeepMin",
                    R.string.set_nap_min_deep_title to R.string.set_nap_min_deep_hint,
                    { it.algo.napMode.post.minDeepMin },
                    { s, v -> s.napPost { it.copy(minDeepMin = v) } },
                    { it.algo.post.minDeepMin },
                    unit = UNIT_MIN,
                ),
                napOverride(
                    "$NAP.post.minRemMin",
                    R.string.set_nap_min_rem_title to R.string.set_nap_min_rem_hint,
                    { it.algo.napMode.post.minRemMin },
                    { s, v -> s.napPost { it.copy(minRemMin = v) } },
                    { it.algo.post.minRemMin },
                    unit = UNIT_MIN,
                ),
                napOverride(
                    "$NAP.post.minAwakeMin",
                    R.string.set_nap_min_awake_title to R.string.set_nap_min_awake_hint,
                    { it.algo.napMode.post.minAwakeMin },
                    { s, v -> s.napPost { it.copy(minAwakeMin = v) } },
                    { it.algo.post.minAwakeMin },
                    unit = UNIT_MIN,
                ),
                napShare(
                    "$NAP.post.sanityDeepMin",
                    R.string.set_nap_sanity_deep_min_title to R.string.set_nap_sanity_deep_min_hint,
                    { it.algo.napMode.post.sanityDeepMin },
                    { s, v -> s.napPost { it.copy(sanityDeepMin = v) } },
                    { it.algo.post.sanityDeepMin },
                ),
                napShare(
                    "$NAP.post.sanityDeepMax",
                    R.string.set_nap_sanity_deep_max_title to R.string.set_nap_sanity_deep_max_hint,
                    { it.algo.napMode.post.sanityDeepMax },
                    { s, v -> s.napPost { it.copy(sanityDeepMax = v) } },
                    { it.algo.post.sanityDeepMax },
                ),
                napShare(
                    "$NAP.post.sanityRemMax",
                    R.string.set_nap_sanity_rem_max_title to R.string.set_nap_sanity_rem_max_hint,
                    { it.algo.napMode.post.sanityRemMax },
                    { s, v -> s.napPost { it.copy(sanityRemMax = v) } },
                    { it.algo.post.sanityRemMax },
                ),
                napShare(
                    "$NAP.post.lowConfidenceThreshold",
                    R.string.set_nap_low_conf_threshold_title to R.string.set_nap_low_conf_threshold_hint,
                    { it.algo.napMode.post.lowConfidenceThreshold },
                    { s, v -> s.napPost { it.copy(lowConfidenceThreshold = v) } },
                    { it.algo.post.lowConfidenceThreshold },
                ),
                SettingsItem.Field(
                    ChoiceSpec(
                        path = "$NAP.post.lowConfidencePolicy",
                        title = text(R.string.set_nap_low_conf_policy_title),
                        hint = text(R.string.set_nap_low_conf_policy_hint),
                        options =
                            withSameAsNights(
                                enumOptions<LowConfidencePolicy> {
                                    when (it) {
                                        LowConfidencePolicy.KEEP -> R.string.option_low_conf_keep
                                        LowConfidencePolicy.LIGHT -> R.string.option_low_conf_light
                                    }
                                },
                            ),
                        style = ChoiceStyle.DROPDOWN,
                        read = { keyOf(it.algo.napMode.post.lowConfidencePolicy) },
                        write = { s, v ->
                            s.napPost {
                                it.copy(
                                    lowConfidencePolicy =
                                        v
                                            .takeIf { key ->
                                                key != SAME_AS_NIGHTS_KEY
                                            }?.let(LowConfidencePolicy::valueOf),
                                )
                            }
                        },
                    ),
                ),
                subheader(R.string.settings_sub_nap_hybrid),
                SettingsItem.Field(
                    ChoiceSpec(
                        path = "$NAP.hybrid.durationModel",
                        title = text(R.string.set_nap_duration_model_title),
                        hint = text(R.string.set_nap_duration_model_hint),
                        options =
                            withSameAsNights(
                                enumOptions<DurationModel> {
                                    when (it) {
                                        DurationModel.EXPLICIT -> R.string.option_duration_explicit
                                        DurationModel.GEOMETRIC -> R.string.option_duration_geometric
                                    }
                                },
                            ),
                        style = ChoiceStyle.DROPDOWN,
                        read = { keyOf(it.algo.napMode.hybrid.durationModel) },
                        write = { s, v ->
                            s.napHybrid {
                                it.copy(
                                    durationModel =
                                        v
                                            .takeIf { key ->
                                                key != SAME_AS_NIGHTS_KEY
                                            }?.let(DurationModel::valueOf),
                                )
                            }
                        },
                    ),
                ),
                napOverride(
                    "$NAP.hybrid.emissionWeight",
                    R.string.set_nap_emission_weight_title to R.string.set_nap_emission_weight_hint,
                    { it.algo.napMode.hybrid.emissionWeight },
                    { s, v -> s.napHybrid { it.copy(emissionWeight = v) } },
                    { it.algo.hybrid.emissionWeight },
                ),
                napOverride(
                    "$NAP.hybrid.timePriorWeight",
                    R.string.set_nap_time_prior_title to R.string.set_nap_time_prior_hint,
                    { it.algo.napMode.hybrid.timePriorWeight },
                    { s, v -> s.napHybrid { it.copy(timePriorWeight = v) } },
                    { it.algo.hybrid.timePriorWeight },
                ),
            ),
    )
