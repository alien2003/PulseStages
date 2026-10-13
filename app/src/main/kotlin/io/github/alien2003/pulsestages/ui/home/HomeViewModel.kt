package io.github.alien2003.pulsestages.ui.home

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alien2003.pulsestages.data.hc.HealthConnectAvailability
import io.github.alien2003.pulsestages.data.hc.HealthConnectAvailabilityChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val availabilityChecker: HealthConnectAvailabilityChecker,
    ) : ViewModel() {
        private val mutableAvailability = MutableStateFlow<HealthConnectAvailability?>(null)
        val availability: StateFlow<HealthConnectAvailability?> = mutableAvailability.asStateFlow()

        fun refresh() {
            mutableAvailability.value = availabilityChecker.check()
        }
    }
