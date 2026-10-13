package io.github.alien2003.pulsestages.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.data.hc.HealthConnectAvailability
import io.github.alien2003.pulsestages.ui.theme.PulseStagesTheme

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val availability by viewModel.availability.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refresh() }
    HomeContent(availability = availability)
}

@Composable
fun HomeContent(
    availability: HealthConnectAvailability?,
    modifier: Modifier = Modifier,
) {
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val horizontalPadding: Dp =
        if (windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)) 48.dp else 16.dp

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .padding(horizontal = horizontalPadding, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
            Text(text = stringResource(R.string.home_body), style = MaterialTheme.typography.bodyLarge)
            Text(text = stringResource(availability.labelRes()), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@StringRes
private fun HealthConnectAvailability?.labelRes(): Int =
    when (this) {
        null -> R.string.hc_status_checking
        HealthConnectAvailability.AVAILABLE -> R.string.hc_status_available
        HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED -> R.string.hc_status_update_required
        HealthConnectAvailability.UNAVAILABLE -> R.string.hc_status_unavailable
    }

@Preview(showBackground = true)
@Composable
private fun HomeContentPreview() {
    PulseStagesTheme(dynamicColor = false) {
        HomeContent(availability = HealthConnectAvailability.AVAILABLE)
    }
}
