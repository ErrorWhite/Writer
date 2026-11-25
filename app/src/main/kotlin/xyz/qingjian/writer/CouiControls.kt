package xyz.qingjian.writer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import io.github.suqi8.coui.kmp.theme.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonMaterial
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.overlay.OverlayDialog
import io.github.suqi8.coui.kmp.window.WindowListPopup

@Composable
internal fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        minWidth = 44.dp, minHeight = 44.dp,
        material = ButtonMaterial.None,
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(
            color = Color.Transparent, disabledColor = Color.Transparent,
            contentColor = WriterTheme.colorScheme.primary
        ),
        content = content
    )
}

@Composable
internal fun Surface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = WriterTheme.colorScheme.surface,
    contentColor: Color = WriterTheme.colorScheme.onSurface,
    content: @Composable () -> Unit
) {
    Box(modifier.clip(shape).background(color)) {
        CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
    }
}

@Composable
internal fun Surface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = WriterTheme.colorScheme.surface,
    contentColor: Color = WriterTheme.colorScheme.onSurface,
    content: @Composable () -> Unit
) {
    Surface(modifier.clip(shape).clickable(onClick = onClick), shape, color, contentColor, content)
}

@Composable
internal fun FilterChip(selected: Boolean, onClick: () -> Unit, label: String) {
    Chip(selected = selected, onClick = onClick, label = label)
}

// Dialog layout follows Eta's COUI OverlayDialog with bounded custom content.
@Composable
internal fun AlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit = {},
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null
) {
    OverlayDialog(
        show = true,
        onDismissRequest = onDismissRequest,
        customContent = {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CompositionLocalProvider(LocalContentColor provides WriterTheme.colorScheme.onSurface) {
                    title()
                    Box(Modifier.heightIn(max = 420.dp)) { text() }
                }
            }
        }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.End
        ) {
            dismissButton?.invoke()
            confirmButton()
        }
    }
}

internal data class MenuEntry(
    val text: String, val enabled: Boolean = true, val onClick: () -> Unit
)

internal class MenuEntries {
    val items = mutableListOf<MenuEntry>()

    fun DropdownMenuItem(text: String, enabled: Boolean = true, onClick: () -> Unit) {
        items.add(MenuEntry(text, enabled, onClick))
    }
}

@Composable
internal fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    entries: MenuEntries.() -> Unit
) {
    if (!expanded) return
    val items = MenuEntries().apply(entries).items
    WindowListPopup(show = true, onDismissRequest = onDismissRequest) {
        ListPopupColumn {
            items.forEachIndexed { index, item ->
                DropdownImpl(
                    text = item.text, optionSize = items.size, index = index,
                    isSelected = false, enabled = item.enabled
                ) { item.onClick() }
            }
        }
    }
}

@Composable
internal fun Checkbox(checked: Boolean, change: (Boolean) -> Unit) {
    io.github.suqi8.coui.kmp.basic.Checkbox(
        state = if (checked) ToggleableState.On else ToggleableState.Off,
        onClick = { change(!checked) }
    )
}

@Composable
internal fun OutlinedButton(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val foreground = WriterTheme.colorScheme.onPrimaryContainer
    Button(onClick, colors = ButtonDefaults.buttonColorsPrimary(
        color = WriterTheme.colorScheme.primaryContainer, contentColor = foreground
    )) {
        CompositionLocalProvider(LocalContentColor provides foreground) { content() }
    }
}

@Composable
internal fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minLines: Int = 1,
    password: Boolean = false,
    label: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.Column(modifier) {
        label()
        TextField(
            value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine, minLines = minLines,
            showPasswordToggle = password,
            keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default
        )
    }
}
