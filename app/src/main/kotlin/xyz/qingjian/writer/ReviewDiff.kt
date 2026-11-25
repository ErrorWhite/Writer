package xyz.qingjian.writer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DiffLine(val before: String?, val after: String?) {
    val changed get() = before != after
}

internal fun alignDiff(before: String, after: String): List<DiffLine> {
    val a = before.lines()
    val b = after.lines()
    var prefix = 0
    while (prefix < minOf(a.size, b.size) && a[prefix] == b[prefix]) prefix++
    var suffix = 0
    while (suffix < minOf(a.size, b.size) - prefix && a[a.lastIndex - suffix] == b[b.lastIndex - suffix]) suffix++
    val removed = a.subList(prefix, a.size - suffix)
    val added = b.subList(prefix, b.size - suffix)
    val width = added.size + 1
    val height = removed.size + 1
    val common = if (width.toLong() * height <= 450_000) IntArray(width * height) else null
    val rows = a.take(prefix).map { DiffLine(it, it) }.toMutableList()
    if (common == null) {
        for (index in 0 until maxOf(removed.size, added.size)) rows += DiffLine(removed.getOrNull(index), added.getOrNull(index))
    } else {
        for (x in removed.indices.reversed()) for (y in added.indices.reversed()) {
            val i = x * width + y
            common[i] = if (removed[x] == added[y]) 1 + common[i + width + 1]
                else maxOf(common[i + width], common[i + 1])
        }
        var x = 0
        var y = 0
        val left = mutableListOf<String>()
        val right = mutableListOf<String>()
        fun appendChanged() {
            for (index in 0 until maxOf(left.size, right.size)) rows += DiffLine(left.getOrNull(index), right.getOrNull(index))
            left.clear()
            right.clear()
        }
        while (x < removed.size || y < added.size) {
            when {
                x < removed.size && y < added.size && removed[x] == added[y] -> {
                    appendChanged()
                    rows += DiffLine(removed[x++], added[y++])
                }
                y == added.size || x < removed.size && common[(x + 1) * width + y] >= common[x * width + y + 1] ->
                    left += removed[x++]
                else -> right += added[y++]
            }
        }
        appendChanged()
    }
    rows += a.takeLast(suffix).map { DiffLine(it, it) }
    return rows
}

@Composable
internal fun ReviewDiff(before: String, after: String, dismiss: () -> Unit) {
    var sideBySide by rememberSaveable { mutableStateOf(false) }
    var changesOnly by rememberSaveable { mutableStateOf(false) }
    val rows by produceState<List<DiffLine>?>(null, before, after) {
        value = withContext(Dispatchers.Default) { alignDiff(before, after) }
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("比对原文与候选", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = dismiss) { Text("返回") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(sideBySide, { sideBySide = !sideBySide }, label = { Text(if (sideBySide) "左右" else "上下") })
                    FilterChip(changesOnly, { changesOnly = !changesOnly }, label = { Text("仅改动") })
                }
                if (sideBySide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("原文", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Text("候选", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                }
                val lines = rows
                if (lines == null) Text("正在计算差异…")
                else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(if (changesOnly) lines.filter(DiffLine::changed) else lines) { line ->
                        val removed = if (line.changed) MaterialTheme.colorScheme.errorContainer else Color.Transparent
                        val added = if (line.changed) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        if (sideBySide) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DiffText(line.before, Modifier.weight(1f), removed)
                            DiffText(line.after, Modifier.weight(1f), added)
                        } else if (line.changed) {
                            Column {
                                line.before?.let { DiffText("− $it", Modifier.fillMaxWidth(), removed) }
                                line.after?.let { DiffText("+ $it", Modifier.fillMaxWidth(), added) }
                            }
                        } else DiffText(line.after, Modifier.fillMaxWidth(), Color.Transparent)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffText(text: String?, modifier: Modifier, color: Color) {
    Text(text.orEmpty().ifEmpty { " " }, modifier.background(color, MaterialTheme.shapes.small).padding(6.dp),
        style = MaterialTheme.typography.bodyMedium)
}
