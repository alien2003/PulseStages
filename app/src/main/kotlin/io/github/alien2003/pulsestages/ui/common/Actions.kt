package io.github.alien2003.pulsestages.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.ui.theme.spacing

/** Buttons that wrap onto further lines when the window is narrow or the font is large. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionRow(content: @Composable RowScope.() -> Unit) {
    val spacing = MaterialTheme.spacing
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
        content = { content() },
    )
}

/** A filled button. */
@Composable
fun PrimaryAction(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled) { Text(label) }
}

/** An outlined button; [destructive] uses the error colour. */
@Composable
fun SecondaryAction(
    label: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val colors =
        if (destructive) {
            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.outlinedButtonColors()
        }
    OutlinedButton(onClick = onClick, enabled = enabled, colors = colors) { Text(label) }
}

/**
 * A confirmation dialog: title, explanation, a confirm and a cancel button. The explanation scrolls when it does not
 * fit (large font, landscape), so no part of a warning is ever cut off.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(body)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
