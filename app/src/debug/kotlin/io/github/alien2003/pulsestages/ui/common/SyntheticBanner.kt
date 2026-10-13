package io.github.alien2003.pulsestages.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.ui.theme.spacing

/**
 * A banner saying that the screen shows synthetic demo data: nothing shown is read from or written to Health
 * Connect. Debug builds only (demo mode); the release build has an empty variant and none of the demo text.
 */
@Composable
fun SyntheticBanner() {
    val spacing = MaterialTheme.spacing
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.medium, vertical = spacing.small),
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_info),
                contentDescription = stringResource(R.string.cd_status_info),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = stringResource(R.string.demo_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}
