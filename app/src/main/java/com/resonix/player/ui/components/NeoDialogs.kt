package com.resonix.player.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.resonix.player.ui.neo.NeoPillButton
import com.resonix.player.ui.neo.neoFloating
import com.resonix.player.ui.neo.neoWell
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.NeoType
import kotlinx.coroutines.delay

private const val MAX_NAME_LENGTH = 60

/**
 * A small panel in the middle of the screen over a dimmed backdrop. Drawn as an overlay inside the
 * screen (not a separate window), like the menus. A tap on the backdrop calls [onDismiss].
 */
@Composable
fun NeoDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(120)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.40f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .neoFloating(cornerRadius = 24.dp, elevation = 18.dp)
                    .padding(20.dp)
                    // Swallow taps on the panel itself so they don't reach the backdrop.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                content = content,
            )
        }
    }
}

/** Flat text field in the app's look: a recessed pill with the text inside. */
@Composable
fun NeoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    onDone: () -> Unit = {},
) {
    val neo = LocalNeoColors.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(200)
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
                // The field wasn't attached yet — the user can still tap it.
            }
        }
    }
    Row(
        modifier = modifier
            .height(48.dp)
            .neoWell(cornerRadius = 24.dp, border = true)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { if (it.length <= MAX_NAME_LENGTH) onValueChange(it) },
            singleLine = true,
            textStyle = NeoType.body.copy(color = neo.textPrimary),
            cursorBrush = SolidColor(neo.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(text = placeholder, color = neo.textTertiary, style = NeoType.body, maxLines = 1)
                    }
                    inner()
                }
            },
        )
    }
}

/** Asks for a name (a new playlist, a rename). The confirm button stays off while the name is blank. */
@Composable
fun NameDialog(
    visible: Boolean,
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val neo = LocalNeoColors.current
    var text by remember(visible, initial) { mutableStateOf(initial) }
    NeoDialog(visible = visible, onDismiss = onDismiss, modifier = modifier) {
        Text(text = title, color = neo.textPrimary, style = NeoType.title)
        Spacer(Modifier.height(16.dp))
        NeoTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = "Playlist name",
            modifier = Modifier.fillMaxWidth(),
            autoFocus = visible,
            onDone = { if (text.isNotBlank()) onConfirm(text) },
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeoPillButton(text = "Cancel", onClick = onDismiss, modifier = Modifier.weight(1f))
            NeoPillButton(
                text = confirmLabel,
                onClick = { onConfirm(text) },
                modifier = Modifier.weight(1f),
                accent = true,
                enabled = text.isNotBlank(),
            )
        }
    }
}

/** "Are you sure?" with a message and two buttons. */
@Composable
fun ConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val neo = LocalNeoColors.current
    NeoDialog(visible = visible, onDismiss = onDismiss, modifier = modifier) {
        Text(text = title, color = neo.textPrimary, style = NeoType.title)
        Spacer(Modifier.height(10.dp))
        Text(text = message, color = neo.textSecondary, style = NeoType.bodyRegular)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeoPillButton(text = "Cancel", onClick = onDismiss, modifier = Modifier.weight(1f))
            NeoPillButton(text = confirmLabel, onClick = onConfirm, modifier = Modifier.weight(1f), accent = true)
        }
    }
}
