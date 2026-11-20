package com.laughing.musicplayer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.laughing.musicplayer.ui.theme.LocalMusicPlayerColors

@Composable
private fun ProvideModalColorScheme(content: @Composable () -> Unit) {
    val colors = LocalMusicPlayerColors.current
    val modalColorScheme = MaterialTheme.colorScheme.copy(
        surface = colors.modalSurface,
        surfaceVariant = colors.modalCardSurface,
        surfaceContainer = colors.modalSurface,
        surfaceContainerHigh = colors.modalCardSurface,
        surfaceContainerHighest = colors.modalCardSurface,
        onSurface = colors.textPrimary,
        onSurfaceVariant = colors.textSecondary,
        outline = colors.modalBorder,
        outlineVariant = colors.modalBorder
    )
    MaterialTheme(
        colorScheme = modalColorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.textPrimary) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier
            .border(1.dp, colors.modalBorder, sheetShape)
            .then(modifier),
        sheetState = sheetState,
        shape = sheetShape,
        containerColor = colors.modalSurface,
        contentColor = colors.textPrimary,
        tonalElevation = 0.dp,
        scrimColor = colors.modalScrim,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (colors.isDark) Color.White.copy(alpha = 0.28f)
                        else Color.Black.copy(alpha = 0.22f)
                    )
            )
        }
    ) {
        ProvideModalColorScheme {
            content()
        }
    }
}

@Composable
fun AppleModalDialogSurface(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dismissOnClickOutside: Boolean = true,
    dismissOnBackPress: Boolean = true,
    maxWidth: Dp = 520.dp,
    cornerRadius: Dp = 24.dp,
    content: @Composable () -> Unit
) {
    val colors = LocalMusicPlayerColors.current

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val view = LocalView.current
        SideEffect {
            val dialogWindow = (view.parent as? DialogWindowProvider)?.window
            runCatching {
                // Scrim is rendered explicitly with colors.modalScrim (0.68f) for cross-GPU consistency.
                dialogWindow?.setDimAmount(0f)
            }
        }

        val scrimInteraction = remember { MutableInteractionSource() }
        val cardInteraction = remember { MutableInteractionSource() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.modalScrim)
                .then(
                    if (dismissOnClickOutside) {
                        Modifier.clickable(
                            interactionSource = scrimInteraction,
                            indication = null,
                            onClick = onDismissRequest
                        )
                    } else {
                        Modifier
                    }
                )
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .widthIn(max = maxWidth)
                    .clickable(
                        interactionSource = cardInteraction,
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(cornerRadius),
                color = colors.modalSurface,
                contentColor = colors.textPrimary,
                border = BorderStroke(1.dp, colors.modalBorder),
                tonalElevation = 0.dp,
                shadowElevation = 16.dp
            ) {
                ProvideModalColorScheme {
                    content()
                }
            }
        }
    }
}

@Composable
fun AppleAlertDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dismissOnClickOutside: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null
) {
    val colors = LocalMusicPlayerColors.current

    AppleModalDialogSurface(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        dismissOnClickOutside = dismissOnClickOutside
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp)
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    icon()
                }
            }
            if (title != null) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = if (icon != null) Alignment.Center else Alignment.CenterStart
                ) {
                    ProvideTextStyle(
                        MaterialTheme.typography.headlineSmall.copy(color = colors.textPrimary)
                    ) {
                        title()
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (text != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                ) {
                    ProvideTextStyle(
                        MaterialTheme.typography.bodyMedium.copy(color = colors.textSecondary)
                    ) {
                        text()
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (dismissButton != null) {
                    dismissButton()
                }
                confirmButton()
            }
        }
    }
}

@Composable
fun AppleDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        containerColor = colors.modalSurface,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, colors.modalBorder)
    ) {
        ProvideModalColorScheme {
            content()
        }
    }
}
