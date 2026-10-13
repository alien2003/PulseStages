package io.github.alien2003.pulsestages.testutil

import io.github.alien2003.pulsestages.source.NightsSource
import io.github.alien2003.pulsestages.source.ReferenceSource
import io.github.alien2003.pulsestages.source.ScreenSources
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * [ScreenSources] with the sources a test passes in; [replace] swaps one at runtime, like the debug build's demo-mode
 * switch. A source that was not given is never emitted.
 */
class TestScreenSources(
    nights: NightsSource? = null,
    references: ReferenceSource? = null,
) : ScreenSources {
    private val currentNights = MutableStateFlow(nights)
    private val currentReferences = MutableStateFlow(references)

    override val nights: Flow<NightsSource> = currentNights.filterNotNull()

    override val references: Flow<ReferenceSource> = currentReferences.filterNotNull()

    /** The screens show [nights] from now on. */
    fun replace(nights: NightsSource) {
        currentNights.value = nights
    }

    /** The Compare tab shows [references] from now on. */
    fun replace(references: ReferenceSource) {
        currentReferences.value = references
    }
}
