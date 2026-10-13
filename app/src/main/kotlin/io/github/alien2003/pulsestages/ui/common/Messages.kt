package io.github.alien2003.pulsestages.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources

/**
 * Shows the first of [messages] in [snackbarHostState] and reports it as shown through [onShown], which removes it
 * from the ViewModel's state; the next message follows. A message with an action stays longer and reports a tap on
 * its action through [onAction] (before [onShown]).
 */
@Composable
fun MessageSnackbarEffect(
    messages: List<UiMessage>,
    snackbarHostState: SnackbarHostState,
    onShown: (Long) -> Unit,
    onAction: (Long) -> Unit = {},
) {
    val first = messages.firstOrNull() ?: return
    val resources = LocalResources.current
    LaunchedEffect(first.id) {
        val action = first.action?.resolve(resources)
        val result =
            snackbarHostState.showSnackbar(
                message = first.text.resolve(resources),
                actionLabel = action,
                duration = if (action == null) SnackbarDuration.Short else SnackbarDuration.Long,
            )
        if (result == SnackbarResult.ActionPerformed) onAction(first.id)
        onShown(first.id)
    }
}
