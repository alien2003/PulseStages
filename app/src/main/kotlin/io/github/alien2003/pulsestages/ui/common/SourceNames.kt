package io.github.alien2003.pulsestages.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.data.local.settings.OHEALTH_PACKAGE

/**
 * Display name of a sleep source: its label from PackageManager when known, the known name of OHealth when it is not
 * installed (any more), otherwise the package name.
 */
@Composable
fun sourceName(
    packageName: String,
    label: String?,
): String =
    when {
        label != null -> label
        packageName == OHEALTH_PACKAGE -> stringResource(R.string.source_label_ohealth)
        else -> packageName
    }
