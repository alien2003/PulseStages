package io.github.alien2003.pulsestages.ui.settings

import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.FeatureParams
import io.github.alien2003.pulsestages.algo.params.HmmParams
import io.github.alien2003.pulsestages.algo.params.HybridParams
import io.github.alien2003.pulsestages.algo.params.PersonalParams
import io.github.alien2003.pulsestages.algo.params.PostprocessParams
import io.github.alien2003.pulsestages.algo.params.PreprocessParams
import io.github.alien2003.pulsestages.algo.params.RulesParams
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.HrSourcesMode
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import io.github.alien2003.pulsestages.data.local.settings.SettingsLimits
import io.github.alien2003.pulsestages.ui.common.UiText
import io.github.alien2003.pulsestages.data.hc.R as DataHcR

/**
 * Every setting of [AppSettings] (including every [AlgoParams] value), grouped into the sections of the Settings
 * screen. The source lists (`sourcePackages`, `sourcePriority`, `manualPackages`) are edited on the Sources screen and
 * appear here as [SettingsItem.SourcesLink]; `SettingsCatalogTest` checks that every other leaf of the settings JSON
 * has exactly one spec.
 */
object SettingsCatalog {
    /** JSON paths edited on the Sources screen instead of here. */
    val SOURCES_SCREEN_PATHS: Set<String> =
        setOf("sources.sourcePackages", "sources.sourcePriority", "sources.manualPackages")

    /** The sections in display order. */
    val sections: List<SettingsSection> by lazy {
        listOf(
            sourcesSection,
            scheduleSection,
            writeSection,
            estimatorSection,
            napsSection,
            preprocessSection,
            featuresSection,
            rulesSection,
            emissionsSection,
            transitionsSection,
            priorsSection,
            hybridSection,
            postSection,
            personalSection,
            debugSection,
        )
    }

    /** Every spec by each of its paths. */
    val specsByPath: Map<String, SettingSpec> by lazy {
        sections
            .flatMap { it.items.specs() }
            .flatMap { spec -> spec.paths.map { it to spec } }
            .toMap()
    }

    /** The spec that edits [path], or null. */
    fun spec(path: String): SettingSpec? = specsByPath[path]
}

// ------------------------------------------------------------------------------------------------ shared texts

/** Accepted values of a number field. */
internal val BOUNDS_POSITIVE: UiText = UiText.of(R.string.bounds_positive)

/** Accepted values of a number field. */
internal val BOUNDS_NON_NEGATIVE: UiText = UiText.of(R.string.bounds_non_negative)

/** Accepted values of a number field. */
internal val BOUNDS_ANY: UiText = UiText.of(R.string.bounds_any)

/** Accepted values of a number field. */
internal fun boundsRange(
    min: Number,
    max: Number,
): UiText = UiText.of(R.string.bounds_range, min.toString(), max.toString())

/** Unit texts. */
internal val UNIT_MIN: UiText = UiText.of(R.string.unit_min)

/** Unit texts. */
internal val UNIT_HOURS: UiText = UiText.of(R.string.unit_hours)

/** Unit texts. */
internal val UNIT_SECONDS: UiText = UiText.of(R.string.unit_seconds)

/** Unit texts. */
internal val UNIT_DAYS: UiText = UiText.of(R.string.unit_days)

/** Unit texts. */
internal val UNIT_BPM: UiText = UiText.of(R.string.unit_bpm)

/** Unit texts. */
internal val UNIT_PERCENT: UiText = UiText.of(R.string.unit_percent)

/** Unit texts. */
internal val UNIT_SAMPLES: UiText = UiText.of(R.string.unit_samples)

/** Unit texts. */
internal val UNIT_EPOCHS: UiText = UiText.of(R.string.unit_epochs)

/** Unit texts. */
internal val UNIT_STEPS: UiText = UiText.of(R.string.unit_steps)

/** Unit texts. */
internal val UNIT_NIGHTS: UiText = UiText.of(R.string.unit_nights)

/** Shorthand for a string resource without arguments. */
internal fun text(id: Int): UiText = UiText.of(id)

/** Options of an enum, labelled by [label]. */
internal inline fun <reified E : Enum<E>> enumOptions(label: (E) -> Int): List<ChoiceOption> =
    enumValues<E>().map { ChoiceOption(it.name, text(label(it))) }

// ------------------------------------------------------------------------------------------------ sections

private val sourcesSection =
    SettingsSection(
        id = "sources",
        title = text(R.string.settings_section_sources),
        summary = text(R.string.settings_section_sources_summary),
        expandedByDefault = true,
        items =
            listOf(
                SettingsItem.SourcesLink,
                SettingsItem.Field(
                    ChoiceSpec(
                        path = "sources.hrSources",
                        title = text(R.string.set_src_hr_sources_title),
                        hint = text(R.string.set_src_hr_sources_hint),
                        options =
                            enumOptions<HrSourcesMode> {
                                when (it) {
                                    HrSourcesMode.SAME_AS_SLEEP -> R.string.option_hr_same_as_sleep
                                    HrSourcesMode.ANY -> R.string.option_hr_any
                                }
                            },
                        style = ChoiceStyle.SEGMENTED,
                        read = { it.sources.hrSources.name },
                        write = { s, v -> s.copy(sources = s.sources.copy(hrSources = HrSourcesMode.valueOf(v))) },
                    ),
                ),
                SettingsItem.Field(
                    IntSpec(
                        path = "sources.minSessionMin",
                        title = text(R.string.set_src_min_session_title),
                        hint = text(R.string.set_src_min_session_hint),
                        range = SettingsLimits.minSessionMin,
                        unit = UNIT_MIN,
                        read = { it.sources.minSessionMin },
                        write = { s, v -> s.copy(sources = s.sources.copy(minSessionMin = v)) },
                    ),
                ),
                SettingsItem.Field(
                    IntSpec(
                        path = "sources.mergeGapMin",
                        title = text(R.string.set_src_merge_gap_title),
                        hint = text(R.string.set_src_merge_gap_hint),
                        range = SettingsLimits.mergeGapMin,
                        unit = UNIT_MIN,
                        read = { it.sources.mergeGapMin },
                        write = { s, v -> s.copy(sources = s.sources.copy(mergeGapMin = v)) },
                    ),
                ),
                SettingsItem.Field(
                    IntSpec(
                        path = "sources.backfillDays",
                        title = text(R.string.set_src_backfill_days_title),
                        hint = text(R.string.set_src_backfill_days_hint),
                        range = SettingsLimits.backfillDays,
                        unit = UNIT_DAYS,
                        read = { it.sources.backfillDays },
                        write = { s, v -> s.copy(sources = s.sources.copy(backfillDays = v)) },
                    ),
                ),
            ),
    )

private val scheduleSection =
    SettingsSection(
        id = "schedule",
        title = text(R.string.settings_section_schedule),
        summary = text(R.string.settings_section_schedule_summary),
        expandedByDefault = true,
        items =
            listOf(
                SettingsItem.Field(
                    TimeSpec(
                        path = "schedule.dailyTimeMinutes",
                        title = text(R.string.set_sch_daily_time_title),
                        hint = text(R.string.set_sch_daily_time_hint),
                        read = { it.schedule.dailyTimeMinutes },
                        write = { s, v -> s.copy(schedule = s.schedule.copy(dailyTimeMinutes = v)) },
                    ),
                ),
                SettingsItem.Field(
                    IntSpec(
                        path = "schedule.periodicHours",
                        title = text(R.string.set_sch_periodic_hours_title),
                        hint = text(R.string.set_sch_periodic_hours_hint),
                        range = SettingsLimits.periodicHours,
                        slider = true,
                        unit = UNIT_HOURS,
                        read = { it.schedule.periodicHours },
                        write = { s, v -> s.copy(schedule = s.schedule.copy(periodicHours = v)) },
                    ),
                ),
                SettingsItem.Field(
                    SwitchSpec(
                        path = "schedule.runOnlyWhenNotLowBattery",
                        title = text(R.string.set_sch_not_low_battery_title),
                        hint = text(R.string.set_sch_not_low_battery_hint),
                        read = { it.schedule.runOnlyWhenNotLowBattery },
                        write = { s, v -> s.copy(schedule = s.schedule.copy(runOnlyWhenNotLowBattery = v)) },
                    ),
                ),
            ),
    )

private val writeSection =
    SettingsSection(
        id = "write",
        title = text(R.string.settings_section_write),
        summary = text(R.string.settings_section_write_summary),
        expandedByDefault = true,
        items =
            listOf(
                SettingsItem.Field(
                    TextSpec(
                        path = "write.titleTemplate",
                        title = text(R.string.set_wr_title_template_title),
                        hint = text(R.string.set_wr_title_template_hint),
                        placeholder = text(DataHcR.string.default_session_title),
                        read = { it.write.titleTemplate },
                        write = { s, v -> s.copy(write = s.write.copy(titleTemplate = v)) },
                    ),
                ),
                SettingsItem.Field(
                    ChoiceSpec(
                        path = "write.overwritePolicy",
                        title = text(R.string.set_wr_overwrite_title),
                        hint = text(R.string.set_wr_overwrite_hint),
                        options =
                            enumOptions<OverwritePolicy> {
                                when (it) {
                                    OverwritePolicy.SKIP_SAME_VERSION -> R.string.option_overwrite_skip_same_version
                                    OverwritePolicy.RECOMPUTE_ALL -> R.string.option_overwrite_recompute_all
                                    OverwritePolicy.NEVER -> R.string.option_overwrite_never
                                }
                            },
                        style = ChoiceStyle.DROPDOWN,
                        read = { it.write.overwritePolicy.name },
                        write = { s, v -> s.copy(write = s.write.copy(overwritePolicy = OverwritePolicy.valueOf(v))) },
                    ),
                ),
                SettingsItem.Field(
                    SwitchSpec(
                        path = "write.awakeAsLight",
                        title = text(R.string.set_wr_awake_as_light_title),
                        hint = text(R.string.set_wr_awake_as_light_hint),
                        read = { it.write.awakeAsLight },
                        write = { s, v -> s.copy(write = s.write.copy(awakeAsLight = v)) },
                    ),
                ),
            ),
    )

private val estimatorSection =
    SettingsSection(
        id = "estimator",
        title = text(R.string.settings_section_estimator),
        summary = text(R.string.settings_section_estimator_summary),
        expandedByDefault = true,
        items =
            listOf(
                SettingsItem.Field(
                    ChoiceSpec(
                        path = "algo.estimator",
                        title = text(R.string.set_alg_estimator_title),
                        hint = text(R.string.set_alg_estimator_hint),
                        options =
                            enumOptions<EstimatorType> {
                                when (it) {
                                    EstimatorType.RULES -> R.string.estimator_rules
                                    EstimatorType.HMM -> R.string.estimator_hmm
                                    EstimatorType.HYBRID -> R.string.estimator_hybrid
                                }
                            },
                        style = ChoiceStyle.SEGMENTED,
                        read = { it.algo.estimator.name },
                        write = { s, v -> s.algo { it.copy(estimator = EstimatorType.valueOf(v)) } },
                    ),
                ),
            ),
    )

private val debugSection =
    SettingsSection(
        id = "debug",
        title = text(R.string.settings_section_debug),
        summary = text(R.string.settings_section_debug_summary),
        expandedByDefault = true,
        items =
            listOf(
                SettingsItem.Field(
                    ChoiceSpec(
                        path = "debug.logLevel",
                        title = text(R.string.set_dbg_log_level_title),
                        hint = text(R.string.set_dbg_log_level_hint),
                        options =
                            enumOptions<LogLevel> {
                                when (it) {
                                    LogLevel.DEBUG -> R.string.log_level_debug
                                    LogLevel.INFO -> R.string.log_level_info
                                    LogLevel.WARN -> R.string.log_level_warn
                                    LogLevel.ERROR -> R.string.log_level_error
                                }
                            },
                        style = ChoiceStyle.DROPDOWN,
                        read = { it.debug.logLevel.name },
                        write = { s, v -> s.copy(debug = s.debug.copy(logLevel = LogLevel.valueOf(v))) },
                    ),
                ),
                SettingsItem.LogLink,
            ),
    )
