package io.github.alien2003.pulsestages.ui.settings

import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.params.EmissionTable
import io.github.alien2003.pulsestages.algo.params.Gaussian
import io.github.alien2003.pulsestages.algo.params.MeanBouts
import io.github.alien2003.pulsestages.algo.params.StateEmission
import io.github.alien2003.pulsestages.algo.params.TransitionWeights
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsLimits
import io.github.alien2003.pulsestages.ui.common.UiText

// Section builders for the HMM parameters: emission table, transitions, priors and per-night EM.

private const val HMM = "algo.hmm"

/** A state of the HMM, its JSON name and how to read / replace its part of a structure. */
private class HmmState<T>(
    val json: String,
    val label: UiText,
    val get: (T) -> StateEmission,
    val set: (T, StateEmission) -> T,
)

/** A feature of the emission model and how to read / replace its Gaussian. */
private class EmissionFeature(
    val json: String,
    val label: UiText,
    val get: (StateEmission) -> Gaussian,
    val set: (StateEmission, Gaussian) -> StateEmission,
)

private val STATE_AWAKE = text(R.string.stage_awake)
private val STATE_LIGHT = text(R.string.stage_light)
private val STATE_DEEP = text(R.string.stage_deep)
private val STATE_REM = text(R.string.stage_rem)

private val EMISSION_STATES: List<HmmState<EmissionTable>> =
    listOf(
        HmmState("awake", STATE_AWAKE, { it.awake }, { t, e -> t.copy(awake = e) }),
        HmmState("light", STATE_LIGHT, { it.light }, { t, e -> t.copy(light = e) }),
        HmmState("deep", STATE_DEEP, { it.deep }, { t, e -> t.copy(deep = e) }),
        HmmState("rem", STATE_REM, { it.rem }, { t, e -> t.copy(rem = e) }),
    )

private val EMISSION_FEATURES: List<EmissionFeature> =
    listOf(
        EmissionFeature("hrNorm", text(R.string.feature_hr_norm), { it.hrNorm }, { e, g -> e.copy(hrNorm = g) }),
        EmissionFeature("hrVar", text(R.string.feature_hr_var), { it.hrVar }, { e, g -> e.copy(hrVar = g) }),
        EmissionFeature("hrSlope", text(R.string.feature_hr_slope), { it.hrSlope }, { e, g -> e.copy(hrSlope = g) }),
    )

private fun AppSettings.emissions(f: (EmissionTable) -> EmissionTable): AppSettings = hmm { it.copy(emissions = f(it.emissions)) }

private fun emissionCell(
    state: HmmState<EmissionTable>,
    feature: EmissionFeature,
): EmissionCell {
    val base = "$HMM.emissions.${state.json}.${feature.json}"

    fun gaussian(s: AppSettings): Gaussian = feature.get(state.get(s.algo.hmm.emissions))

    fun withGaussian(
        s: AppSettings,
        g: Gaussian,
    ): AppSettings = s.emissions { table -> state.set(table, feature.set(state.get(table), g)) }
    val hint = text(R.string.set_hmm_emissions_hint)
    return EmissionCell(
        feature = feature.label,
        mean =
            DoubleSpec(
                path = "$base.mean",
                title = UiText.of(R.string.set_hmm_emission_mean_title, state.label, feature.label),
                hint = hint,
                read = { gaussian(it).mean },
                write = { s, v -> withGaussian(s, gaussian(s).copy(mean = v)) },
                bounds = BOUNDS_ANY,
            ),
        sd =
            DoubleSpec(
                path = "$base.sd",
                title = UiText.of(R.string.set_hmm_emission_sd_title, state.label, feature.label),
                hint = hint,
                read = { gaussian(it).sd },
                write = { s, v -> withGaussian(s, gaussian(s).copy(sd = v)) },
                bounds = BOUNDS_POSITIVE,
            ),
    )
}

/** The HMM emission table (4 states × 3 features, μ and σ each). */
internal val emissionsSection =
    SettingsSection(
        id = "hmm_emissions",
        title = text(R.string.settings_section_emissions),
        summary = text(R.string.settings_section_emissions_summary),
        expandedByDefault = false,
        items =
            listOf(
                SettingsItem.EmissionTable(
                    hint = text(R.string.set_hmm_emissions_hint),
                    rows =
                        EMISSION_STATES.map { state ->
                            EmissionRow(state.label, EMISSION_FEATURES.map { feature -> emissionCell(state, feature) })
                        },
                ),
            ),
    )

/** One transition weight: JSON name, source and target labels, accessors. */
private class Transition(
    val json: String,
    val from: UiText,
    val to: UiText,
    val get: (TransitionWeights) -> Double,
    val set: (TransitionWeights, Double) -> TransitionWeights,
)

private val TRANSITIONS: List<Transition> =
    listOf(
        Transition("awakeToLight", STATE_AWAKE, STATE_LIGHT, { it.awakeToLight }, { t, v -> t.copy(awakeToLight = v) }),
        Transition("awakeToDeep", STATE_AWAKE, STATE_DEEP, { it.awakeToDeep }, { t, v -> t.copy(awakeToDeep = v) }),
        Transition("awakeToRem", STATE_AWAKE, STATE_REM, { it.awakeToRem }, { t, v -> t.copy(awakeToRem = v) }),
        Transition("lightToAwake", STATE_LIGHT, STATE_AWAKE, { it.lightToAwake }, { t, v -> t.copy(lightToAwake = v) }),
        Transition("lightToDeep", STATE_LIGHT, STATE_DEEP, { it.lightToDeep }, { t, v -> t.copy(lightToDeep = v) }),
        Transition("lightToRem", STATE_LIGHT, STATE_REM, { it.lightToRem }, { t, v -> t.copy(lightToRem = v) }),
        Transition("deepToAwake", STATE_DEEP, STATE_AWAKE, { it.deepToAwake }, { t, v -> t.copy(deepToAwake = v) }),
        Transition("deepToLight", STATE_DEEP, STATE_LIGHT, { it.deepToLight }, { t, v -> t.copy(deepToLight = v) }),
        Transition("deepToRem", STATE_DEEP, STATE_REM, { it.deepToRem }, { t, v -> t.copy(deepToRem = v) }),
        Transition("remToAwake", STATE_REM, STATE_AWAKE, { it.remToAwake }, { t, v -> t.copy(remToAwake = v) }),
        Transition("remToLight", STATE_REM, STATE_LIGHT, { it.remToLight }, { t, v -> t.copy(remToLight = v) }),
        Transition("remToDeep", STATE_REM, STATE_DEEP, { it.remToDeep }, { t, v -> t.copy(remToDeep = v) }),
    )

private fun transitionSpec(t: Transition): DoubleSpec =
    DoubleSpec(
        path = "$HMM.transitions.${t.json}",
        title = UiText.of(R.string.set_hmm_transition_title, t.from, t.to),
        hint = text(R.string.set_hmm_transitions_hint),
        read = { t.get(it.algo.hmm.transitions) },
        write = { s, v -> s.hmm { it.copy(transitions = t.set(it.transitions, v)) } },
        bounds = BOUNDS_NON_NEGATIVE,
    )

/** One mean bout duration: JSON name, stage label, accessors. */
private class Bout(
    val json: String,
    val stage: UiText,
    val get: (MeanBouts) -> Double,
    val set: (MeanBouts, Double) -> MeanBouts,
)

private val BOUTS: List<Bout> =
    listOf(
        Bout("awakeMin", STATE_AWAKE, { it.awakeMin }, { b, v -> b.copy(awakeMin = v) }),
        Bout("lightMin", STATE_LIGHT, { it.lightMin }, { b, v -> b.copy(lightMin = v) }),
        Bout("deepMin", STATE_DEEP, { it.deepMin }, { b, v -> b.copy(deepMin = v) }),
        Bout("remMin", STATE_REM, { it.remMin }, { b, v -> b.copy(remMin = v) }),
    )

private fun boutItem(b: Bout): SettingsItem =
    SettingsItem.Field(
        DoubleSpec(
            path = "$HMM.meanBouts.${b.json}",
            title = UiText.of(R.string.set_hmm_mean_bout_title, b.stage),
            hint = text(R.string.set_hmm_mean_bout_hint),
            read = { b.get(it.algo.hmm.meanBouts) },
            write = { s, v -> s.hmm { it.copy(meanBouts = b.set(it.meanBouts, v)) } },
            bounds = UiText.of(R.string.bounds_above_epoch),
            unit = UNIT_MIN,
        ),
    )

/** Mean bouts (self transitions), the transition weights and the initial distribution. */
internal val transitionsSection =
    SettingsSection(
        id = "hmm_transitions",
        title = text(R.string.settings_section_transitions),
        summary = text(R.string.settings_section_transitions_summary),
        expandedByDefault = false,
        items =
            buildList {
                add(subheader(R.string.settings_sub_mean_bouts))
                BOUTS.forEach { add(boutItem(it)) }
                add(subheader(R.string.settings_sub_transition_weights))
                add(
                    SettingsItem.TransitionWeights(
                        hint = text(R.string.set_hmm_transitions_hint),
                        rows =
                            TRANSITIONS
                                .groupBy { it.from }
                                .map { (from, list) -> TransitionRow(from, list.map(::transitionSpec)) },
                    ),
                )
                add(
                    numberField(
                        "$HMM.epsilon",
                        R.string.set_hmm_epsilon_title,
                        R.string.set_hmm_epsilon_hint,
                        { it.algo.hmm.epsilon },
                        { s, v -> s.hmm { it.copy(epsilon = v) } },
                        UiText.of(R.string.bounds_open_unit),
                    ),
                )
                add(
                    numberField(
                        "$HMM.initialAwake",
                        R.string.set_hmm_initial_awake_title,
                        R.string.set_hmm_initial_awake_hint,
                        { it.algo.hmm.initialAwake },
                        { s, v -> s.hmm { it.copy(initialAwake = v) } },
                    ),
                )
                add(
                    numberField(
                        "$HMM.initialLight",
                        R.string.set_hmm_initial_light_title,
                        R.string.set_hmm_initial_light_hint,
                        { it.algo.hmm.initialLight },
                        { s, v -> s.hmm { it.copy(initialLight = v) } },
                    ),
                )
            },
    )

/** Time-of-night priors, REM lockout, ultradian cycle prior, steps and per-night EM. */
internal val priorsSection =
    SettingsSection(
        id = "hmm_priors",
        title = text(R.string.settings_section_priors),
        summary = text(R.string.settings_section_priors_summary),
        expandedByDefault = false,
        items =
            listOf(
                subheader(R.string.settings_sub_time_priors),
                numberField(
                    "$HMM.deepTimeIntercept",
                    R.string.set_hmm_deep_intercept_title,
                    R.string.set_hmm_deep_intercept_hint,
                    { it.algo.hmm.deepTimeIntercept },
                    { s, v -> s.hmm { it.copy(deepTimeIntercept = v) } },
                ),
                numberField(
                    "$HMM.deepTimeSlope",
                    R.string.set_hmm_deep_slope_title,
                    R.string.set_hmm_deep_slope_hint,
                    { it.algo.hmm.deepTimeSlope },
                    { s, v -> s.hmm { it.copy(deepTimeSlope = v) } },
                ),
                numberField(
                    "$HMM.deepTimeFloor",
                    R.string.set_hmm_deep_floor_title,
                    R.string.set_hmm_deep_floor_hint,
                    { it.algo.hmm.deepTimeFloor },
                    { s, v -> s.hmm { it.copy(deepTimeFloor = v) } },
                ),
                numberField(
                    "$HMM.remTimeIntercept",
                    R.string.set_hmm_rem_intercept_title,
                    R.string.set_hmm_rem_intercept_hint,
                    { it.algo.hmm.remTimeIntercept },
                    { s, v -> s.hmm { it.copy(remTimeIntercept = v) } },
                ),
                numberField(
                    "$HMM.remTimeSlope",
                    R.string.set_hmm_rem_slope_title,
                    R.string.set_hmm_rem_slope_hint,
                    { it.algo.hmm.remTimeSlope },
                    { s, v -> s.hmm { it.copy(remTimeSlope = v) } },
                ),
                subheader(R.string.settings_sub_lockout),
                numberField(
                    "$HMM.remLockoutMin",
                    R.string.set_hmm_rem_lockout_title,
                    R.string.set_hmm_rem_lockout_hint,
                    { it.algo.hmm.remLockoutMin },
                    { s, v -> s.hmm { it.copy(remLockoutMin = v) } },
                    unit = UNIT_MIN,
                ),
                shareSlider(
                    "$HMM.remLockoutFactor",
                    R.string.set_hmm_rem_lockout_factor_title,
                    R.string.set_hmm_rem_lockout_factor_hint,
                    { it.algo.hmm.remLockoutFactor },
                    { s, v -> s.hmm { it.copy(remLockoutFactor = v) } },
                ),
                subheader(R.string.settings_sub_cycle),
                numberField(
                    "$HMM.cyclePriorWeight",
                    R.string.set_hmm_cycle_weight_title,
                    R.string.set_hmm_cycle_weight_hint,
                    { it.algo.hmm.cyclePriorWeight },
                    { s, v -> s.hmm { it.copy(cyclePriorWeight = v) } },
                ),
                shareSlider(
                    "$HMM.cycleDeepPhase",
                    R.string.set_hmm_cycle_deep_phase_title,
                    R.string.set_hmm_cycle_deep_phase_hint,
                    { it.algo.hmm.cycleDeepPhase },
                    { s, v -> s.hmm { it.copy(cycleDeepPhase = v) } },
                ),
                shareSlider(
                    "$HMM.cycleRemPhase",
                    R.string.set_hmm_cycle_rem_phase_title,
                    R.string.set_hmm_cycle_rem_phase_hint,
                    { it.algo.hmm.cycleRemPhase },
                    { s, v -> s.hmm { it.copy(cycleRemPhase = v) } },
                ),
                subheader(R.string.settings_sub_steps),
                intSetting(
                    "$HMM.awakeStepThreshold",
                    R.string.set_hmm_awake_steps_title to R.string.set_hmm_awake_steps_hint,
                    SettingsLimits.awakeStepThreshold,
                    { it.algo.hmm.awakeStepThreshold },
                    { s, v -> s.hmm { it.copy(awakeStepThreshold = v) } },
                    UNIT_STEPS,
                ),
                numberField(
                    "$HMM.fewStepsAwakeFactor",
                    R.string.set_hmm_few_steps_factor_title,
                    R.string.set_hmm_few_steps_factor_hint,
                    { it.algo.hmm.fewStepsAwakeFactor },
                    { s, v -> s.hmm { it.copy(fewStepsAwakeFactor = v) } },
                ),
                subheader(R.string.settings_sub_em),
                switchSetting(
                    "$HMM.emAdapt",
                    R.string.set_hmm_em_adapt_title,
                    R.string.set_hmm_em_adapt_hint,
                    { it.algo.hmm.emAdapt },
                    { s, v -> s.hmm { it.copy(emAdapt = v) } },
                ),
                intSetting(
                    "$HMM.emIterations",
                    R.string.set_hmm_em_iterations_title to R.string.set_hmm_em_iterations_hint,
                    SettingsLimits.iterations,
                    { it.algo.hmm.emIterations },
                    { s, v -> s.hmm { it.copy(emIterations = v) } },
                    slider = true,
                ),
                numberField(
                    "$HMM.emPriorStrength",
                    R.string.set_hmm_em_prior_title,
                    R.string.set_hmm_em_prior_hint,
                    { it.algo.hmm.emPriorStrength },
                    { s, v -> s.hmm { it.copy(emPriorStrength = v) } },
                ),
            ),
    )
