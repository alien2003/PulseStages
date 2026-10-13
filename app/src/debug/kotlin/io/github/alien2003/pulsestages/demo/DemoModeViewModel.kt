package io.github.alien2003.pulsestages.demo

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** The demo-mode switch of the Settings tab (debug builds only). */
@HiltViewModel
class DemoModeViewModel
    @Inject
    constructor(
        private val store: DemoModeStore,
    ) : ViewModel() {
        /** Whether demo mode is on. */
        val enabled: StateFlow<Boolean> = store.enabled

        /** The user flipped the switch: the Nights, night detail and Compare screens follow at once. */
        fun setEnabled(enabled: Boolean) {
            store.setEnabled(enabled)
        }
    }
