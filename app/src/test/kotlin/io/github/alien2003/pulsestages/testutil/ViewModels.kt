package io.github.alien2003.pulsestages.testutil

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass

/** Clears [viewModel] like its owner would (onCleared, viewModelScope cancelled), so no collector outlives the test. */
fun clear(viewModel: ViewModel) {
    val store = ViewModelStore()
    val factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") // Hands out the one instance under test.
            override fun <T : ViewModel> create(
                modelClass: KClass<T>,
                extras: CreationExtras,
            ): T = viewModel as T
        }
    ViewModelProvider.create(store, factory)[viewModel::class]
    store.clear()
}
