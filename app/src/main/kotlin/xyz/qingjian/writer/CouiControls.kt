package xyz.qingjian.writer

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LinearProgressIndicator as ComposeLinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField as ComposeOutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import com.itos.coui.checkbox.Checkbox as CouiCheckbox
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itos.coui.chip.ChipTabRow as KitChipTabRow
import com.itos.coui.chip.CouiChipTabItem
import com.itos.coui.backdrop.couiBackdrop
import com.itos.coui.backdrop.rememberCouiBackdrop
import com.itos.coui.effect.CouiFloatingRenderer
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.layout.Item
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.radiobutton.RadioButton as CouiRadioButton
import com.itos.coui.segmentbutton.SegmentedControl
import com.itos.coui.sheet.BottomSheet
import com.itos.coui.switch.Switch

@Composable
internal fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = WriterTheme.colorScheme
    Row(
        modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).clip(WriterTheme.shapes.medium)
            .couiButtonFeedback(interactionSource = interaction, enabled = enabled)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled,
                role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(LocalContentColor provides
            if (enabled) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.45f)) {
            content()
        }
    }
}

@Composable
internal fun ToolbarTextButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled) { Text(text, style = WriterTheme.typography.labelLarge) }
}

@Composable
internal fun PreferenceGroup(
    title: String,
    content: PreferenceRows.() -> Unit
) {
    val rows = PreferenceRows().apply(content).rows
    Group(title = title) {
        for ((index, row) in rows.withIndex()) {
            androidx.compose.runtime.key(row.first) {
                if (index > 0) Divider()
                row.second()
            }
        }
    }
}

internal class PreferenceRows {
    internal val rows = mutableListOf<Pair<String, @Composable () -> Unit>>()

    fun item(key: String, content: @Composable () -> Unit) {
        rows.add(key to content)
    }
}

@Composable
internal fun ArrowPreference(
    title: String, summary: String? = null, enabled: Boolean = true,
    startAction: (@Composable () -> Unit)? = null,
    endActions: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Item(
        icon = startAction,
        iconSize = 44.dp,
        summary = summary?.let { value -> @Composable { Text(value, style = WriterTheme.typography.bodySmall) } },
        trailing = { if (endActions != null) endActions() else Text("›", color = WriterTheme.colorScheme.onSurfaceVariant) },
        onClick = onClick, enabled = enabled
    ) { Text(title) }
}

@Composable
internal fun SwitchPreference(checked: Boolean, onCheckedChange: (Boolean) -> Unit, title: String) {
    Item(trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        onClick = { onCheckedChange(!checked) }) { Text(title) }
}

@Composable
internal fun RadioButtonPreference(title: String, selected: Boolean, onClick: () -> Unit) {
    Item(trailing = { CouiRadioButton(selected = selected) },
        modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick)) { Text(title) }
}

@Composable
internal fun RadioButton(selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    CouiRadioButton(
        modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        selected = selected, enabled = enabled
    )
}

@Composable
internal fun FilterChip(selected: Boolean, onClick: () -> Unit, label: String) {
    val colors = WriterTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.sizeIn(minHeight = 40.dp).clip(WriterTheme.shapes.large)
            .background(if (selected) colors.primaryContainer else colors.surfaceContainer)
            .couiButtonFeedback(interactionSource = interaction)
            .selectable(selected, interaction, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            style = WriterTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
internal fun ChipTabRow(
    labels: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier, horizontalPadding: Dp = 0.dp, itemSpacing: Dp = 8.dp
) {
    if (labels.isEmpty()) return
    if (Build.VERSION.SDK_INT >= 34) {
        val items = remember(labels) { labels.mapIndexed { index, label -> CouiChipTabItem(index, label) } }
        val renderers = remember(items) {
            runCatching { items.map { CouiFloatingRenderer.standalone() } }.getOrNull()
        }
        if (renderers != null) {
            val backdrop = rememberCouiBackdrop()
            Box(modifier) {
                Box(Modifier.matchParentSize().couiBackdrop(backdrop).background(WriterTheme.colorScheme.background))
                KitChipTabRow(modifier = Modifier.padding(horizontal = horizontalPadding), items = items,
                    backdrop = backdrop, renderers = renderers, selectedKey = selectedIndex,
                    onSelectedKeyChange = { onSelected(it as Int) }, itemSpacing = itemSpacing)
            }
            return
        }
    }
    Row(modifier.horizontalScroll(rememberScrollState()).selectableGroup().padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(itemSpacing)) {
        labels.forEachIndexed { index, label -> FilterChip(index == selectedIndex, { onSelected(index) }, label) }
    }
}

@Composable
internal fun SegmentButton(labels: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT >= 34) {
        SegmentedControl(modifier = modifier, labels = labels, selectedIndex = selectedIndex, onSelected = onSelected)
    } else {
        ChipTabRow(labels, selectedIndex, onSelected, modifier, itemSpacing = 4.dp)
    }
}

@Composable
internal fun PreferenceCategory(title: String, endWidget: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = WriterTheme.typography.labelLarge,
            color = WriterTheme.colorScheme.onSurfaceVariant)
        endWidget()
    }
}

@Composable
internal fun LinearProgressIndicator(modifier: Modifier = Modifier, progress: Float, height: Dp) {
    ComposeLinearProgressIndicator(
        progress = { progress }, modifier = modifier.height(height),
        color = WriterTheme.colorScheme.primary, trackColor = WriterTheme.colorScheme.surfaceContainer
    )
}

@Composable
internal fun EmptyState(
    title: String, subtitle: String? = null, actionText: String? = null, onActionClick: (() -> Unit)? = null,
    titleStyle: TextStyle = WriterTheme.typography.titleMedium, illustration: (@Composable () -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        illustration?.invoke()
        Text(title, style = titleStyle)
        if (subtitle != null) Text(subtitle, color = WriterTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (actionText != null && onActionClick != null) TextButton(onClick = onActionClick) { Text(actionText) }
    }
}

@Composable
internal fun WriterSearch(query: String, onQueryChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    ComposeOutlinedTextField(
        query, onQueryChange, modifier = modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        shape = WriterTheme.shapes.large, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() })
    )
}

@Composable
internal fun AlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit = {},
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null
) {
    val visible = remember { mutableStateOf(true) }
    BottomSheet(visible = visible, onDismissRequest = onDismissRequest, scrollableContent = false,
        dismissDescription = "关闭弹窗", material = null) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            androidx.compose.material3.ProvideTextStyle(WriterTheme.typography.titleLarge, title)
            Box(Modifier.weight(1f, fill = false).heightIn(max = 420.dp)) { text() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                dismissButton?.invoke()
                confirmButton()
            }
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
    val items = MenuEntries().apply(entries).items
    if (items.isEmpty()) return
    Menu(
        expanded = expanded, onDismissRequest = onDismissRequest,
        items = items.mapIndexed { index, item -> CouiMenuItem(index, item.text, enabled = item.enabled) },
        onItemClick = { items[it.value].onClick() }
    )
}

@Composable
internal fun Checkbox(checked: Boolean, change: (Boolean) -> Unit) {
    CouiCheckbox(modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp)
        .toggleable(checked, role = Role.Checkbox, onValueChange = change), checked = checked)
}

@Composable
internal fun OutlinedButton(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val foreground = WriterTheme.colorScheme.onPrimaryContainer
    Button(onClick, colors = ButtonDefaults.buttonColors(
        containerColor = WriterTheme.colorScheme.primaryContainer, contentColor = foreground
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
    enabled: Boolean = true,
    label: @Composable () -> Unit
) {
    var reveal by remember { mutableStateOf(false) }
    ComposeOutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
        singleLine = singleLine, minLines = minLines, label = label, shape = WriterTheme.shapes.medium,
        visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        trailingIcon = if (password) {
            { TextButton(enabled = enabled, onClick = { reveal = !reveal }) { Text(if (reveal) "隐藏" else "显示") } }
        } else null
    )
}
