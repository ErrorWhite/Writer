package xyz.qingjian.writer

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.TextField

@Composable
internal fun OutlinedButton(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val foreground = MaterialTheme.colorScheme.onPrimaryContainer
    Button(onClick, colors = ButtonDefaults.buttonColorsPrimary(
        color = MaterialTheme.colorScheme.primaryContainer, contentColor = foreground
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
