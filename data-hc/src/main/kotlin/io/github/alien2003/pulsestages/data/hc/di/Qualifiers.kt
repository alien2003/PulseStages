package io.github.alien2003.pulsestages.data.hc.di

import javax.inject.Qualifier

/** Qualifies the package name of this app (the data origin of our own sessions). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OwnPackageName

/** Qualifies the dispatcher on which Health Connect calls and record mapping run (IO). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HealthConnectDispatcher

/** Qualifies the dispatcher on which CPU-bound stage estimation runs (Default). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EstimationDispatcher

/**
 * Qualifies the repository that talks to Health Connect directly (HealthConnectRepository), behind the unqualified
 * HealthRepository the app uses (BackgroundRuleHealthRepository, which enforces the background rule on top of it).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlatformHealthRepository
