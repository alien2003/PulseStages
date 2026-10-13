package io.github.alien2003.pulsestages.platform

import javax.inject.Qualifier

/** Qualifies the dispatcher for blocking I/O of the app module (files, content resolver, PackageManager). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
