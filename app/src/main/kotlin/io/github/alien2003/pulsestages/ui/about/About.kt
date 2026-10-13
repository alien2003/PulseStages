package io.github.alien2003.pulsestages.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.data.hc.usecase.HybridModelProvider
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.platform.AppVersionSource
import io.github.alien2003.pulsestages.ui.theme.spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the About card shows.
 *
 * @property appVersion the installed version name (empty when unknown).
 * @property algoVersion [ALGO_VERSION] of this build.
 * @property modelId id of the bundled hybrid model, or null while it loads or when there is none.
 * @property modelChecked the bundle was looked up ([modelId] null afterwards = no usable bundle).
 */
@Immutable
data class AboutInfo(
    val appVersion: String,
    val algoVersion: Int = ALGO_VERSION,
    val modelId: String? = null,
    val modelChecked: Boolean = false,
)

/** Loads [AboutInfo] for [VersionsAndLicencesCard]: the version name at once, the model id once the bundle is loaded. */
@HiltViewModel
class AboutViewModel
    @Inject
    constructor(
        version: AppVersionSource,
        private val models: HybridModelProvider,
        private val logger: AppLogger,
    ) : ViewModel() {
        private val mutableAbout = MutableStateFlow(AboutInfo(appVersion = version.versionName()))

        /** The current info. */
        val about: StateFlow<AboutInfo> = mutableAbout.asStateFlow()

        init {
            viewModelScope.launch { mutableAbout.update { it.copy(modelId = modelId(), modelChecked = true) } }
        }

        @Suppress("TooGenericExceptionCaught") // The card must show something whatever the bundle does.
        private suspend fun modelId(): String? =
            try {
                models.hybridModel()?.modelId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "could not load the hybrid model for the versions card", throwable = e)
                null
            }

        private companion object {
            const val TAG = "About"
        }
    }

/**
 * Versions and licences (the last card of the Settings tab): versions of the app, the algorithm and the bundled
 * model, the licence of the code, and the attribution notice that the open datasets' licence (ODC-By 1.0) requires for
 * the model trained on them.
 */
@Composable
fun VersionsAndLicencesCard(
    about: AboutInfo,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val model =
        when {
            about.modelId != null -> about.modelId
            about.modelChecked -> stringResource(R.string.about_model_none)
            else -> stringResource(R.string.value_not_available)
        }
    val version = about.appVersion.ifBlank { stringResource(R.string.value_not_available) }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.medium), verticalArrangement = Arrangement.spacedBy(spacing.small)) {
            Text(
                text = stringResource(R.string.about_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.about_versions, version, about.algoVersion, model), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_estimate), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodySmall)
            Text(
                text = stringResource(R.string.about_datasets_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.about_attribution), style = MaterialTheme.typography.bodySmall)
            Text(
                text = stringResource(R.string.about_citations),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
