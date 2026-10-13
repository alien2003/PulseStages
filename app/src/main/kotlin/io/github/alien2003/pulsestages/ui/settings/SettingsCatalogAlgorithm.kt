package io.github.alien2003.pulsestages.ui.settings

import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.params.DurationModel
import io.github.alien2003.pulsestages.algo.params.GapPolicy
import io.github.alien2003.pulsestages.algo.params.LowConfidencePolicy
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsLimits
import io.github.alien2003.pulsestages.ui.common.UiText

// Section builders for the algorithm parameters except the HMM (see SettingsCatalogHmm.kt).

/** A decimal number field. */
internal fun numberField(
    path: String,
    title: Int,
    hint: Int,
    read: (AppSettings) -> Double,
    write: (AppSettings, Double) -> AppSettings,
    bounds: UiText? = BOUNDS_NON_NEGATIVE,
    unit: UiText? = null,
): SettingsItem = SettingsItem.Field(DoubleSpec(path, text(title), text(hint), read, write, bounds = bounds, unit = unit))

/** A share or probability (0..1) edited with a slider. */
internal fun shareSlider(
    path: String,
    title: Int,
    hint: Int,
    read: (AppSettings) -> Double,
    write: (AppSettings, Double) -> AppSettings,
): SettingsItem = SettingsItem.Field(DoubleSpec(path, text(title), text(hint), read, write, sliderRange = SettingsLimits.unitInterval))

/** A whole number (field or slider); [texts] is the title and hint resource. */
internal fun intSetting(
    path: String,
    texts: Pair<Int, Int>,
    range: IntRange,
    read: (AppSettings) -> Int,
    write: (AppSettings, Int) -> AppSettings,
    unit: UiText? = null,
    slider: Boolean = false,
    oddOnly: Boolean = false,
): SettingsItem =
    SettingsItem.Field(
        IntSpec(path, text(texts.first), text(texts.second), range, read, write, oddOnly = oddOnly, slider = slider, unit = unit),
    )

/** A Switch. */
internal fun switchSetting(
    path: String,
    title: Int,
    hint: Int,
    read: (AppSettings) -> Boolean,
    write: (AppSettings, Boolean) -> AppSettings,
): SettingsItem = SettingsItem.Field(SwitchSpec(path, text(title), text(hint), read, write))

/** A subheading. */
internal fun subheader(title: Int): SettingsItem = SettingsItem.Subheader(text(title))

private const val PRE = "algo.preprocess"
private const val FEA = "algo.features"
private const val RUL = "algo.rules"
private const val HYB = "algo.hybrid"
private const val POST = "algo.post"
private const val PER = "algo.personal"

/** Preprocessing of the heart-rate series. */
internal val preprocessSection =
    SettingsSection(
        id = "preprocess",
        title = text(R.string.settings_section_preprocess),
        summary = text(R.string.settings_section_preprocess_summary),
        expandedByDefault = false,
        items =
            listOf(
                numberField(
                    "$PRE.minBpm",
                    R.string.set_pre_min_bpm_title,
                    R.string.set_pre_min_bpm_hint,
                    { it.algo.preprocess.minBpm },
                    { s, v -> s.pre { it.copy(minBpm = v) } },
                    BOUNDS_POSITIVE,
                    UNIT_BPM,
                ),
                numberField(
                    "$PRE.maxBpm",
                    R.string.set_pre_max_bpm_title,
                    R.string.set_pre_max_bpm_hint,
                    { it.algo.preprocess.maxBpm },
                    { s, v -> s.pre { it.copy(maxBpm = v) } },
                    BOUNDS_POSITIVE,
                    UNIT_BPM,
                ),
                intSetting(
                    "$PRE.hampelWindow",
                    R.string.set_pre_hampel_window_title to R.string.set_pre_hampel_window_hint,
                    SettingsLimits.hampelWindow,
                    { it.algo.preprocess.hampelWindow },
                    { s, v -> s.pre { it.copy(hampelWindow = v) } },
                    UNIT_SAMPLES,
                    slider = true,
                    oddOnly = true,
                ),
                numberField(
                    "$PRE.hampelThreshold",
                    R.string.set_pre_hampel_threshold_title,
                    R.string.set_pre_hampel_threshold_hint,
                    { it.algo.preprocess.hampelThreshold },
                    { s, v -> s.pre { it.copy(hampelThreshold = v) } },
                    BOUNDS_POSITIVE,
                ),
                numberField(
                    "$PRE.hampelMinScaleBpm",
                    R.string.set_pre_hampel_min_scale_title,
                    R.string.set_pre_hampel_min_scale_hint,
                    { it.algo.preprocess.hampelMinScaleBpm },
                    { s, v -> s.pre { it.copy(hampelMinScaleBpm = v) } },
                    unit = UNIT_BPM,
                ),
                SettingsItem.Field(
                    ChoiceSpec(
                        path = "$PRE.epochSec",
                        title = text(R.string.set_pre_epoch_title),
                        hint = text(R.string.set_pre_epoch_hint),
                        options =
                            SettingsLimits.epochSec.sorted().map {
                                ChoiceOption(
                                    it.toString(),
                                    UiText.of(R.string.format_seconds, it),
                                )
                            },
                        style = ChoiceStyle.SEGMENTED,
                        read = {
                            it.algo.preprocess.epochSec
                                .toString()
                        },
                        write = { s, v -> s.pre { it.copy(epochSec = v.toInt()) } },
                    ),
                ),
                numberField(
                    "$PRE.maxInterpGapMin",
                    R.string.set_pre_max_interp_gap_title,
                    R.string.set_pre_max_interp_gap_hint,
                    { it.algo.preprocess.maxInterpGapMin },
                    { s, v -> s.pre { it.copy(maxInterpGapMin = v) } },
                    boundsRange(0, SettingsLimits.maxInterpGapMin.endInclusive.toInt()),
                    UNIT_MIN,
                ),
                intSetting(
                    "$PRE.medianWindowEpochs",
                    R.string.set_pre_median_window_title to R.string.set_pre_median_window_hint,
                    SettingsLimits.medianWindowEpochs,
                    { it.algo.preprocess.medianWindowEpochs },
                    { s, v -> s.pre { it.copy(medianWindowEpochs = v) } },
                    UNIT_EPOCHS,
                    slider = true,
                    oddOnly = true,
                ),
                intSetting(
                    "$PRE.smoothWindowEpochs",
                    R.string.set_pre_smooth_window_title to R.string.set_pre_smooth_window_hint,
                    SettingsLimits.smoothWindowEpochs,
                    { it.algo.preprocess.smoothWindowEpochs },
                    { s, v -> s.pre { it.copy(smoothWindowEpochs = v) } },
                    UNIT_EPOCHS,
                    slider = true,
                    oddOnly = true,
                ),
                intSetting(
                    "$PRE.contextMarginMin",
                    R.string.set_pre_context_margin_title to R.string.set_pre_context_margin_hint,
                    SettingsLimits.contextMarginMin,
                    { it.algo.preprocess.contextMarginMin },
                    { s, v -> s.pre { it.copy(contextMarginMin = v) } },
                    UNIT_MIN,
                ),
                shareSlider(
                    "$PRE.minCoverage",
                    R.string.set_pre_min_coverage_title,
                    R.string.set_pre_min_coverage_hint,
                    { it.algo.preprocess.minCoverage },
                    { s, v -> s.pre { it.copy(minCoverage = v) } },
                ),
                shareSlider(
                    "$PRE.rulesMinCoverage",
                    R.string.set_pre_rules_min_coverage_title,
                    R.string.set_pre_rules_min_coverage_hint,
                    { it.algo.preprocess.rulesMinCoverage },
                    { s, v -> s.pre { it.copy(rulesMinCoverage = v) } },
                ),
                numberField(
                    "$PRE.minSessionMin",
                    R.string.set_pre_min_session_title,
                    R.string.set_pre_min_session_hint,
                    { it.algo.preprocess.minSessionMin },
                    { s, v -> s.pre { it.copy(minSessionMin = v) } },
                    unit = UNIT_MIN,
                ),
            ),
    )

/** Per-epoch features and night context. */
internal val featuresSection =
    SettingsSection(
        id = "features",
        title = text(R.string.settings_section_features),
        summary = text(R.string.settings_section_features_summary),
        expandedByDefault = false,
        items =
            listOf(
                numberField(
                    "$FEA.normLowPercentile",
                    R.string.set_fea_norm_low_title,
                    R.string.set_fea_norm_low_hint,
                    { it.algo.features.normLowPercentile },
                    { s, v -> s.features { it.copy(normLowPercentile = v) } },
                    boundsRange(0, SettingsLimits.percentile.endInclusive.toInt()),
                    UNIT_PERCENT,
                ),
                numberField(
                    "$FEA.normHighPercentile",
                    R.string.set_fea_norm_high_title,
                    R.string.set_fea_norm_high_hint,
                    { it.algo.features.normHighPercentile },
                    { s, v -> s.features { it.copy(normHighPercentile = v) } },
                    boundsRange(0, SettingsLimits.percentile.endInclusive.toInt()),
                    UNIT_PERCENT,
                ),
                numberField(
                    "$FEA.minNormRangeBpm",
                    R.string.set_fea_min_norm_range_title,
                    R.string.set_fea_min_norm_range_hint,
                    { it.algo.features.minNormRangeBpm },
                    { s, v -> s.features { it.copy(minNormRangeBpm = v) } },
                    BOUNDS_POSITIVE,
                    UNIT_BPM,
                ),
                intSetting(
                    "$FEA.varHalfWindowEpochs",
                    R.string.set_fea_var_half_window_title to R.string.set_fea_var_half_window_hint,
                    SettingsLimits.halfWindowEpochs,
                    { it.algo.features.varHalfWindowEpochs },
                    { s, v -> s.features { it.copy(varHalfWindowEpochs = v) } },
                    UNIT_EPOCHS,
                ),
                intSetting(
                    "$FEA.slopeHalfWindowEpochs",
                    R.string.set_fea_slope_half_window_title to R.string.set_fea_slope_half_window_hint,
                    SettingsLimits.halfWindowEpochs,
                    { it.algo.features.slopeHalfWindowEpochs },
                    { s, v -> s.features { it.copy(slopeHalfWindowEpochs = v) } },
                    UNIT_EPOCHS,
                ),
                numberField(
                    "$FEA.cycleMinDistanceMin",
                    R.string.set_fea_cycle_distance_title,
                    R.string.set_fea_cycle_distance_hint,
                    { it.algo.features.cycleMinDistanceMin },
                    { s, v -> s.features { it.copy(cycleMinDistanceMin = v) } },
                    BOUNDS_POSITIVE,
                    UNIT_MIN,
                ),
                numberField(
                    "$FEA.cycleMinProminenceBpm",
                    R.string.set_fea_cycle_prominence_title,
                    R.string.set_fea_cycle_prominence_hint,
                    { it.algo.features.cycleMinProminenceBpm },
                    { s, v -> s.features { it.copy(cycleMinProminenceBpm = v) } },
                    unit = UNIT_BPM,
                ),
                intSetting(
                    "$FEA.baselineNights",
                    R.string.set_fea_baseline_nights_title to R.string.set_fea_baseline_nights_hint,
                    SettingsLimits.baselineNights,
                    { it.algo.features.baselineNights },
                    { s, v -> s.features { it.copy(baselineNights = v) } },
                    UNIT_NIGHTS,
                ),
                numberField(
                    "$FEA.baselineMinSessionMin",
                    R.string.set_fea_baseline_min_session_title,
                    R.string.set_fea_baseline_min_session_hint,
                    { it.algo.features.baselineMinSessionMin },
                    { s, v -> s.features { it.copy(baselineMinSessionMin = v) } },
                    boundsRange(0, SettingsLimits.sessionThresholdMin.endInclusive.toInt()),
                    UNIT_MIN,
                ),
                numberField(
                    "$FEA.elevatedNightPct",
                    R.string.set_fea_elevated_pct_title,
                    R.string.set_fea_elevated_pct_hint,
                    { it.algo.features.elevatedNightPct },
                    { s, v -> s.features { it.copy(elevatedNightPct = v) } },
                    unit = UNIT_PERCENT,
                ),
                numberField(
                    "$FEA.elevatedAwakeShift",
                    R.string.set_fea_elevated_awake_shift_title,
                    R.string.set_fea_elevated_awake_shift_hint,
                    { it.algo.features.elevatedAwakeShift },
                    { s, v -> s.features { it.copy(elevatedAwakeShift = v) } },
                ),
                shareSlider(
                    "$FEA.elevatedConfidenceFactor",
                    R.string.set_fea_elevated_confidence_title,
                    R.string.set_fea_elevated_confidence_hint,
                    { it.algo.features.elevatedConfidenceFactor },
                    { s, v -> s.features { it.copy(elevatedConfidenceFactor = v) } },
                ),
                subheader(R.string.settings_sub_optional_channels),
                switchSetting(
                    "$FEA.useSpo2",
                    R.string.set_fea_use_spo2_title,
                    R.string.set_fea_use_spo2_hint,
                    { it.algo.features.useSpo2 },
                    { s, v -> s.features { it.copy(useSpo2 = v) } },
                ),
                numberField(
                    "$FEA.spo2DropPct",
                    R.string.set_fea_spo2_drop_title,
                    R.string.set_fea_spo2_drop_hint,
                    { it.algo.features.spo2DropPct },
                    { s, v -> s.features { it.copy(spo2DropPct = v) } },
                    unit = UNIT_PERCENT,
                ),
                numberField(
                    "$FEA.spo2AwakeLightFactor",
                    R.string.set_fea_spo2_factor_title,
                    R.string.set_fea_spo2_factor_hint,
                    { it.algo.features.spo2AwakeLightFactor },
                    { s, v -> s.features { it.copy(spo2AwakeLightFactor = v) } },
                ),
                numberField(
                    "$FEA.spo2MedianWindowMin",
                    R.string.set_fea_spo2_window_title,
                    R.string.set_fea_spo2_window_hint,
                    { it.algo.features.spo2MedianWindowMin },
                    { s, v -> s.features { it.copy(spo2MedianWindowMin = v) } },
                    BOUNDS_POSITIVE,
                    UNIT_MIN,
                ),
                switchSetting(
                    "$FEA.useHrv",
                    R.string.set_fea_use_hrv_title,
                    R.string.set_fea_use_hrv_hint,
                    { it.algo.features.useHrv },
                    { s, v -> s.features { it.copy(useHrv = v) } },
                ),
                numberField(
                    "$FEA.hrvWeight",
                    R.string.set_fea_hrv_weight_title,
                    R.string.set_fea_hrv_weight_hint,
                    { it.algo.features.hrvWeight },
                    { s, v -> s.features { it.copy(hrvWeight = v) } },
                    BOUNDS_POSITIVE,
                ),
                numberField(
                    "$FEA.hrvMaxDistanceMin",
                    R.string.set_fea_hrv_distance_title,
                    R.string.set_fea_hrv_distance_hint,
                    { it.algo.features.hrvMaxDistanceMin },
                    { s, v -> s.features { it.copy(hrvMaxDistanceMin = v) } },
                    unit = UNIT_MIN,
                ),
                switchSetting(
                    "$FEA.useRespiratoryRate",
                    R.string.set_fea_use_rr_title,
                    R.string.set_fea_use_rr_hint,
                    { it.algo.features.useRespiratoryRate },
                    { s, v -> s.features { it.copy(useRespiratoryRate = v) } },
                ),
                numberField(
                    "$FEA.respiratoryRateWeight",
                    R.string.set_fea_rr_weight_title,
                    R.string.set_fea_rr_weight_hint,
                    { it.algo.features.respiratoryRateWeight },
                    { s, v -> s.features { it.copy(respiratoryRateWeight = v) } },
                ),
                intSetting(
                    "$FEA.respiratoryHalfWindowEpochs",
                    R.string.set_fea_rr_half_window_title to R.string.set_fea_rr_half_window_hint,
                    SettingsLimits.halfWindowEpochs,
                    { it.algo.features.respiratoryHalfWindowEpochs },
                    { s, v -> s.features { it.copy(respiratoryHalfWindowEpochs = v) } },
                    UNIT_EPOCHS,
                ),
            ),
    )

/** Thresholds of the rules estimator. */
internal val rulesSection =
    SettingsSection(
        id = "rules",
        title = text(R.string.settings_section_rules),
        summary = text(R.string.settings_section_rules_summary),
        expandedByDefault = false,
        items =
            listOf(
                numberField(
                    "$RUL.deepMaxHrNorm",
                    R.string.set_rul_deep_max_norm_title,
                    R.string.set_rul_deep_max_norm_hint,
                    { it.algo.rules.deepMaxHrNorm },
                    { s, v -> s.rules { it.copy(deepMaxHrNorm = v) } },
                    BOUNDS_ANY,
                ),
                numberField(
                    "$RUL.deepMaxHrVar",
                    R.string.set_rul_deep_max_var_title,
                    R.string.set_rul_deep_max_var_hint,
                    { it.algo.rules.deepMaxHrVar },
                    { s, v -> s.rules { it.copy(deepMaxHrVar = v) } },
                    BOUNDS_ANY,
                ),
                numberField(
                    "$RUL.awakeMinHrNorm",
                    R.string.set_rul_awake_min_norm_title,
                    R.string.set_rul_awake_min_norm_hint,
                    { it.algo.rules.awakeMinHrNorm },
                    { s, v -> s.rules { it.copy(awakeMinHrNorm = v) } },
                    BOUNDS_ANY,
                ),
                numberField(
                    "$RUL.remMinHrNorm",
                    R.string.set_rul_rem_min_norm_title,
                    R.string.set_rul_rem_min_norm_hint,
                    { it.algo.rules.remMinHrNorm },
                    { s, v -> s.rules { it.copy(remMinHrNorm = v) } },
                    BOUNDS_ANY,
                ),
                numberField(
                    "$RUL.remMinHrVar",
                    R.string.set_rul_rem_min_var_title,
                    R.string.set_rul_rem_min_var_hint,
                    { it.algo.rules.remMinHrVar },
                    { s, v -> s.rules { it.copy(remMinHrVar = v) } },
                    BOUNDS_ANY,
                ),
                shareSlider(
                    "$RUL.remMinT",
                    R.string.set_rul_rem_min_t_title,
                    R.string.set_rul_rem_min_t_hint,
                    { it.algo.rules.remMinT },
                    { s, v -> s.rules { it.copy(remMinT = v) } },
                ),
                shareSlider(
                    "$RUL.pseudoPosterior",
                    R.string.set_rul_pseudo_posterior_title,
                    R.string.set_rul_pseudo_posterior_hint,
                    { it.algo.rules.pseudoPosterior },
                    { s, v -> s.rules { it.copy(pseudoPosterior = v) } },
                ),
                numberField(
                    "$RUL.calibrationThresholdShift",
                    R.string.set_rul_calibration_shift_title,
                    R.string.set_rul_calibration_shift_hint,
                    { it.algo.rules.calibrationThresholdShift },
                    { s, v -> s.rules { it.copy(calibrationThresholdShift = v) } },
                ),
            ),
    )

/** The hybrid estimator (classifier + HSMM). */
internal val hybridSection =
    SettingsSection(
        id = "hybrid",
        title = text(R.string.settings_section_hybrid),
        summary = text(R.string.settings_section_hybrid_summary),
        expandedByDefault = false,
        items =
            listOf(
                SettingsItem.Field(
                    ChoiceSpec(
                        path = "$HYB.durationModel",
                        title = text(R.string.set_hyb_duration_title),
                        hint = text(R.string.set_hyb_duration_hint),
                        options =
                            enumOptions<DurationModel> {
                                when (it) {
                                    DurationModel.EXPLICIT -> R.string.option_duration_explicit
                                    DurationModel.GEOMETRIC -> R.string.option_duration_geometric
                                }
                            },
                        style = ChoiceStyle.SEGMENTED,
                        read = { it.algo.hybrid.durationModel.name },
                        write = { s, v -> s.hybrid { it.copy(durationModel = DurationModel.valueOf(v)) } },
                    ),
                ),
                numberField(
                    "$HYB.timePriorWeight",
                    R.string.set_hyb_time_prior_title,
                    R.string.set_hyb_time_prior_hint,
                    { it.algo.hybrid.timePriorWeight },
                    { s, v -> s.hybrid { it.copy(timePriorWeight = v) } },
                ),
                intSetting(
                    "$HYB.maxBoutMin",
                    R.string.set_hyb_max_bout_title to R.string.set_hyb_max_bout_hint,
                    SettingsLimits.maxBoutMin,
                    { it.algo.hybrid.maxBoutMin },
                    { s, v -> s.hybrid { it.copy(maxBoutMin = v) } },
                    UNIT_MIN,
                ),
                numberField(
                    "$HYB.emissionWeight",
                    R.string.set_hyb_emission_weight_title,
                    R.string.set_hyb_emission_weight_hint,
                    { it.algo.hybrid.emissionWeight },
                    { s, v -> s.hybrid { it.copy(emissionWeight = v) } },
                ),
                numberField(
                    "$HYB.elevatedAwakeFactor",
                    R.string.set_hyb_elevated_awake_title,
                    R.string.set_hyb_elevated_awake_hint,
                    { it.algo.hybrid.elevatedAwakeFactor },
                    { s, v -> s.hybrid { it.copy(elevatedAwakeFactor = v) } },
                ),
                numberField(
                    "$HYB.priorExponent",
                    R.string.set_hyb_prior_exponent_title,
                    R.string.set_hyb_prior_exponent_hint,
                    { it.algo.hybrid.priorExponent },
                    { s, v -> s.hybrid { it.copy(priorExponent = v) } },
                ),
            ),
    )

/** Post-processing of the decoded stages. */
internal val postSection =
    SettingsSection(
        id = "post",
        title = text(R.string.settings_section_post),
        summary = text(R.string.settings_section_post_summary),
        expandedByDefault = false,
        items =
            listOf(
                numberField(
                    "$POST.minDeepMin",
                    R.string.set_post_min_deep_title,
                    R.string.set_post_min_deep_hint,
                    { it.algo.post.minDeepMin },
                    { s, v -> s.post { it.copy(minDeepMin = v) } },
                    unit = UNIT_MIN,
                ),
                numberField(
                    "$POST.minRemMin",
                    R.string.set_post_min_rem_title,
                    R.string.set_post_min_rem_hint,
                    { it.algo.post.minRemMin },
                    { s, v -> s.post { it.copy(minRemMin = v) } },
                    unit = UNIT_MIN,
                ),
                numberField(
                    "$POST.minAwakeMin",
                    R.string.set_post_min_awake_title,
                    R.string.set_post_min_awake_hint,
                    { it.algo.post.minAwakeMin },
                    { s, v -> s.post { it.copy(minAwakeMin = v) } },
                    unit = UNIT_MIN,
                ),
                SettingsItem.Field(
                    ChoiceSpec(
                        path = "$POST.gapPolicy",
                        title = text(R.string.set_post_gap_policy_title),
                        hint = text(R.string.set_post_gap_policy_hint),
                        options =
                            enumOptions<GapPolicy> {
                                when (it) {
                                    GapPolicy.UNKNOWN -> R.string.option_gap_unknown
                                    GapPolicy.LIGHT -> R.string.option_gap_light
                                    GapPolicy.NEAREST -> R.string.option_gap_nearest
                                }
                            },
                        style = ChoiceStyle.SEGMENTED,
                        read = { it.algo.post.gapPolicy.name },
                        write = { s, v -> s.post { it.copy(gapPolicy = GapPolicy.valueOf(v)) } },
                    ),
                ),
                shareSlider(
                    "$POST.lowConfidenceThreshold",
                    R.string.set_post_low_conf_threshold_title,
                    R.string.set_post_low_conf_threshold_hint,
                    { it.algo.post.lowConfidenceThreshold },
                    { s, v -> s.post { it.copy(lowConfidenceThreshold = v) } },
                ),
                SettingsItem.Field(
                    ChoiceSpec(
                        path = "$POST.lowConfidencePolicy",
                        title = text(R.string.set_post_low_conf_policy_title),
                        hint = text(R.string.set_post_low_conf_policy_hint),
                        options =
                            enumOptions<LowConfidencePolicy> {
                                when (it) {
                                    LowConfidencePolicy.KEEP -> R.string.option_low_conf_keep
                                    LowConfidencePolicy.LIGHT -> R.string.option_low_conf_light
                                }
                            },
                        style = ChoiceStyle.SEGMENTED,
                        read = { it.algo.post.lowConfidencePolicy.name },
                        write = { s, v -> s.post { it.copy(lowConfidencePolicy = LowConfidencePolicy.valueOf(v)) } },
                    ),
                ),
                subheader(R.string.settings_sub_sanity),
                shareSlider(
                    "$POST.sanityMinCoverage",
                    R.string.set_post_sanity_coverage_title,
                    R.string.set_post_sanity_coverage_hint,
                    { it.algo.post.sanityMinCoverage },
                    { s, v -> s.post { it.copy(sanityMinCoverage = v) } },
                ),
                numberField(
                    "$POST.proportionsMinSessionMin",
                    R.string.set_post_proportions_min_session_title,
                    R.string.set_post_proportions_min_session_hint,
                    { it.algo.post.proportionsMinSessionMin },
                    { s, v -> s.post { it.copy(proportionsMinSessionMin = v) } },
                    boundsRange(0, SettingsLimits.sessionThresholdMin.endInclusive.toInt()),
                    UNIT_MIN,
                ),
                shareSlider(
                    "$POST.sanityDeepMax",
                    R.string.set_post_sanity_deep_max_title,
                    R.string.set_post_sanity_deep_max_hint,
                    { it.algo.post.sanityDeepMax },
                    { s, v -> s.post { it.copy(sanityDeepMax = v) } },
                ),
                shareSlider(
                    "$POST.sanityDeepMin",
                    R.string.set_post_sanity_deep_min_title,
                    R.string.set_post_sanity_deep_min_hint,
                    { it.algo.post.sanityDeepMin },
                    { s, v -> s.post { it.copy(sanityDeepMin = v) } },
                ),
                shareSlider(
                    "$POST.sanityRemMax",
                    R.string.set_post_sanity_rem_max_title,
                    R.string.set_post_sanity_rem_max_hint,
                    { it.algo.post.sanityRemMax },
                    { s, v -> s.post { it.copy(sanityRemMax = v) } },
                ),
                shareSlider(
                    "$POST.sanityConfidenceFactor",
                    R.string.set_post_sanity_confidence_title,
                    R.string.set_post_sanity_confidence_hint,
                    { it.algo.post.sanityConfidenceFactor },
                    { s, v -> s.post { it.copy(sanityConfidenceFactor = v) } },
                ),
                subheader(R.string.settings_sub_calibration),
                switchSetting(
                    "$POST.calibrateProportions",
                    R.string.set_post_calibrate_title,
                    R.string.set_post_calibrate_hint,
                    { it.algo.post.calibrateProportions },
                    { s, v -> s.post { it.copy(calibrateProportions = v) } },
                ),
                SettingsItem.Field(
                    RangeSpec(
                        path = "$POST.targetDeepMin",
                        maxPath = "$POST.targetDeepMax",
                        title = text(R.string.set_post_target_deep_title),
                        hint = text(R.string.set_post_target_deep_hint),
                        readMin = { it.algo.post.targetDeepMin },
                        readMax = { it.algo.post.targetDeepMax },
                        write = { s, min, max -> s.post { it.copy(targetDeepMin = min, targetDeepMax = max) } },
                    ),
                ),
                SettingsItem.Field(
                    RangeSpec(
                        path = "$POST.targetRemMin",
                        maxPath = "$POST.targetRemMax",
                        title = text(R.string.set_post_target_rem_title),
                        hint = text(R.string.set_post_target_rem_hint),
                        readMin = { it.algo.post.targetRemMin },
                        readMax = { it.algo.post.targetRemMax },
                        write = { s, min, max -> s.post { it.copy(targetRemMin = min, targetRemMax = max) } },
                    ),
                ),
                shareSlider(
                    "$POST.targetAwakeMax",
                    R.string.set_post_target_awake_title,
                    R.string.set_post_target_awake_hint,
                    { it.algo.post.targetAwakeMax },
                    { s, v -> s.post { it.copy(targetAwakeMax = v) } },
                ),
                shareSlider(
                    "$POST.calibrationStep",
                    R.string.set_post_calibration_step_title,
                    R.string.set_post_calibration_step_hint,
                    { it.algo.post.calibrationStep },
                    { s, v -> s.post { it.copy(calibrationStep = v) } },
                ),
                intSetting(
                    "$POST.calibrationMaxIterations",
                    R.string.set_post_calibration_iterations_title to R.string.set_post_calibration_iterations_hint,
                    SettingsLimits.iterations,
                    { it.algo.post.calibrationMaxIterations },
                    { s, v -> s.post { it.copy(calibrationMaxIterations = v) } },
                    slider = true,
                ),
            ),
    )

/** Personalization. */
internal val personalSection =
    SettingsSection(
        id = "personal",
        title = text(R.string.settings_section_personal),
        summary = text(R.string.settings_section_personal_summary),
        expandedByDefault = false,
        items =
            listOf(
                switchSetting(
                    "$PER.enabled",
                    R.string.set_per_enabled_title,
                    R.string.set_per_enabled_hint,
                    { it.algo.personal.enabled },
                    { s, v -> s.personal { it.copy(enabled = v) } },
                ),
                intSetting(
                    "$PER.emEveryNights",
                    R.string.set_per_em_every_title to R.string.set_per_em_every_hint,
                    SettingsLimits.emEveryNights,
                    { it.algo.personal.emEveryNights },
                    { s, v -> s.personal { it.copy(emEveryNights = v) } },
                    UNIT_NIGHTS,
                ),
                numberField(
                    "$PER.emPriorStrength",
                    R.string.set_per_em_prior_title,
                    R.string.set_per_em_prior_hint,
                    { it.algo.personal.emPriorStrength },
                    { s, v -> s.personal { it.copy(emPriorStrength = v) } },
                ),
                numberField(
                    "$PER.calibrationRegularization",
                    R.string.set_per_regularization_title,
                    R.string.set_per_regularization_hint,
                    { it.algo.personal.calibrationRegularization },
                    { s, v -> s.personal { it.copy(calibrationRegularization = v) } },
                ),
                shareSlider(
                    "$PER.calibrationHoldout",
                    R.string.set_per_holdout_title,
                    R.string.set_per_holdout_hint,
                    { it.algo.personal.calibrationHoldout },
                    { s, v -> s.personal { it.copy(calibrationHoldout = v) } },
                ),
            ),
    )
