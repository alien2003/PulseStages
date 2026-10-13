package io.github.alien2003.pulsestages.demo

import io.github.alien2003.pulsestages.source.NightsSource
import io.github.alien2003.pulsestages.source.ReferenceSource
import io.github.alien2003.pulsestages.source.ScreenSources
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [ScreenSources] of debug builds: the real sources while demo mode ([enabled]) is off, the synthetic demo sources
 * ([DemoNightsSource], [DemoReferenceSource]) while it is on. Each switch emits the other source, and the screens
 * follow at once. The sources are created when they are first needed; the demo ones are singletons that keep their
 * nights in memory.
 */
class DemoScreenSources(
    enabled: Flow<Boolean>,
    private val realNights: () -> NightsSource,
    private val realReferences: () -> ReferenceSource,
    private val demoNights: () -> NightsSource,
    private val demoReferences: () -> ReferenceSource,
) : ScreenSources {
    private val mode = enabled.distinctUntilChanged()

    override val nights: Flow<NightsSource> = mode.map { demo -> if (demo) demoNights() else realNights() }

    override val references: Flow<ReferenceSource> = mode.map { demo -> if (demo) demoReferences() else realReferences() }
}
