package io.github.alien2003.pulsestages.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Label and icon of an installed app.
 *
 * @property label the app's label, or null when the app is not visible to PulseStages.
 * @property icon the launcher icon, or null.
 */
@Immutable
data class AppInfo(
    val label: String?,
    val icon: ImageBitmap?,
)

/** Looks up the label and icon of apps by package name. */
fun interface AppInfoResolver {
    /** Label and icon of [packageName]; both null when the app is unknown or not visible. Never throws. */
    suspend fun resolve(packageName: String): AppInfo
}

/**
 * [AppInfoResolver] over PackageManager. On Android 11+ an app is visible only through the manifest's `<queries>`:
 * Health Connect, OHealth and every app that declares Health Connect's permission-rationale intent (each Health
 * Connect client must), so the apps that wrote sleep data resolve without QUERY_ALL_PACKAGES. An app that is still
 * not visible (or not installed any more) falls back to its package name. Results are cached for the process lifetime.
 */
@Singleton
class PackageManagerAppInfoResolver
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) : AppInfoResolver {
        private val cache = ConcurrentHashMap<String, AppInfo>()

        override suspend fun resolve(packageName: String): AppInfo =
            cache[packageName] ?: withContext(io) { load(packageName) }.also { cache[packageName] = it }

        private fun load(packageName: String): AppInfo {
            val packageManager = context.packageManager
            val info = applicationInfo(packageManager, packageName) ?: return AppInfo(label = null, icon = null)
            val label = packageManager.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
            val sizePx = (ICON_SIZE_DP * context.resources.displayMetrics.density).roundToInt()
            val icon =
                try {
                    packageManager.getApplicationIcon(info).toBitmap(sizePx, sizePx).asImageBitmap()
                } catch (ignored: IllegalArgumentException) {
                    // A drawable without a usable size: show the placeholder icon instead.
                    null
                }
            return AppInfo(label, icon)
        }

        private fun applicationInfo(
            packageManager: PackageManager,
            packageName: String,
        ): ApplicationInfo? =
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION") // The flags overload exists from API 33 on.
                    packageManager.getApplicationInfo(packageName, 0)
                }
            } catch (ignored: PackageManager.NameNotFoundException) {
                null
            }

        private companion object {
            const val ICON_SIZE_DP = 40
        }
    }
