package io.github.alien2003.pulsestages.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** The installed app's version name. */
fun interface AppVersionSource {
    /** `versionName` of this app (taken from the release tag in CI builds); empty when unknown. Never throws. */
    fun versionName(): String
}

/** [AppVersionSource] over PackageManager (for the versions-and-licences card). */
class PackageManagerAppVersionSource
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : AppVersionSource {
        override fun versionName(): String =
            try {
                val packageManager = context.packageManager
                val info =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION") // The flags overload exists from API 33 only.
                        packageManager.getPackageInfo(context.packageName, 0)
                    }
                info.versionName.orEmpty()
            } catch (ignored: PackageManager.NameNotFoundException) {
                ""
            }
    }
