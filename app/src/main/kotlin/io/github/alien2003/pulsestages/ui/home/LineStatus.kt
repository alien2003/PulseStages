package io.github.alien2003.pulsestages.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.alien2003.pulsestages.R

/** How a status line is marked. */
internal enum class LineStatus {
    /** Fine (check mark). */
    OK,

    /** Needs attention (warning). */
    PROBLEM,

    /** Neutral information. */
    INFO,
}

/** A status line: a check, warning or info icon (with a content description), a headline and supporting text. */
@Composable
internal fun StatusLine(
    headline: String,
    supporting: String?,
    status: LineStatus,
) {
    @DrawableRes val icon: Int
    val description: String
    val tint: Color
    when (status) {
        LineStatus.OK -> {
            icon = R.drawable.ic_check
            description = stringResource(R.string.cd_status_ok)
            tint = MaterialTheme.colorScheme.primary
        }

        LineStatus.PROBLEM -> {
            icon = R.drawable.ic_warning
            description = stringResource(R.string.cd_status_problem)
            tint = MaterialTheme.colorScheme.error
        }

        LineStatus.INFO -> {
            icon = R.drawable.ic_info
            description = stringResource(R.string.cd_status_info)
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { Icon(painter = painterResource(icon), contentDescription = description, tint = tint) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
