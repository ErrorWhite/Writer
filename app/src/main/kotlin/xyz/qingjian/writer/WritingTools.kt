package xyz.qingjian.writer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class FormatAction(val label: String, val prefix: String, val suffix: String, val sample: String) {
    Indent("缩进", "", "", "　　"),
    Heading("标题", "# ", "", "标题"),
    Strong("粗体", "**", "**", "正文"),
    Emphasis("斜体", "*", "*", "正文"),
    List("列表", "- ", "", "条目"),
    Task("任务", "- [ ] ", "", "待办"),
    Quote("引用", "> ", "", "引用"),
    Rule("场景分隔", "\n\n", "\n\n", "＊　＊　＊"),
    Code("代码块", "\n\n```text\n", "\n```\n\n", "内容"),
    Table("表格", "\n\n", "\n\n", "| 项目 | 内容 |\n| --- | --- |\n|  |  |"),
    Link("链接", "[", "](https://)", "链接文字")
}

@Composable
internal fun FormatBar(value: TextFieldValue, changed: (TextFieldValue) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        FormatAction.entries.forEach { format ->
            TextButton(onClick = {
                val start = value.selection.min
                val end = value.selection.max
                val selected = value.text.substring(start, end)
                val insert = when (format) {
                    FormatAction.Indent -> format.sample + selected
                    FormatAction.Rule, FormatAction.Table -> format.prefix + format.sample + format.suffix + selected
                    else -> format.prefix + selected.ifEmpty { format.sample } + format.suffix
                }
                val text = value.text.replaceRange(start, end, insert)
                val range = if (selected.isEmpty() && format !in setOf(FormatAction.Indent, FormatAction.Rule, FormatAction.Table)) {
                    TextRange(start + format.prefix.length, start + format.prefix.length + format.sample.length)
                } else TextRange(start + insert.length)
                changed(TextFieldValue(text, range))
            }) { Text(format.label) }
        }
    }
}

@Composable
internal fun FindDialog(value: TextFieldValue, changed: (TextFieldValue) -> Unit, dismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    var confirmAll by rememberSaveable { mutableStateOf(false) }
    val source = value.text
    val result by produceState<LiteralSearchResult?>(null, source, query, matchCase) {
        val term = query
        val sensitive = matchCase
        this.value = withContext(Dispatchers.Default) {
            LiteralSearchResult(source, term, sensitive, countLiteralMatches(source, term, sensitive))
        }
    }
    val count = result?.takeIf { it.source == source && it.query == query && it.matchCase == matchCase }?.count ?: 0
    val canLocate = query.isNotBlank() && count > 0
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("查找与替换") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("查找") })
                OutlinedTextField(replacement, { replacement = it }, label = { Text("替换为") })
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(matchCase, { matchCase = it })
                    Text("区分大小写")
                }
                if (query.isNotBlank()) Text("$count 处匹配")
                TextButton(enabled = canLocate, onClick = {
                    val first = value.text.indexOf(query, value.selection.max, ignoreCase = !matchCase)
                    val index = if (first >= 0) first else value.text.indexOf(query, ignoreCase = !matchCase)
                    changed(value.copy(selection = TextRange(index, index + query.length), composition = null))
                    dismiss()
                }) { Text("定位下一处") }
                TextButton(enabled = canLocate, onClick = {
                    val start = value.selection.min
                    val end = value.selection.max
                    val current = value.text.substring(start, end)
                    val index = if (current.equals(query, ignoreCase = !matchCase)) start else
                        value.text.indexOf(query, end, ignoreCase = !matchCase).takeIf { it >= 0 }
                            ?: value.text.indexOf(query, ignoreCase = !matchCase)
                    val text = value.text.replaceRange(index, index + query.length, replacement)
                    changed(TextFieldValue(text, TextRange(index + replacement.length)))
                }) { Text("替换一处") }
                TextButton(enabled = canLocate, onClick = { confirmAll = true }) {
                    Text("全部替换", color = WriterTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } }
    )
    if (confirmAll) AlertDialog(
        onDismissRequest = { confirmAll = false },
        title = { Text("替换全部 $count 处匹配？") },
        confirmButton = {
                TextButton(enabled = canLocate, onClick = {
                changed(TextFieldValue(value.text.replace(query, replacement, ignoreCase = !matchCase)))
                dismiss()
            }) { Text("全部替换") }
        }, dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("取消") } }
    )
}

private data class LiteralSearchResult(val source: String, val query: String, val matchCase: Boolean, val count: Int)

internal fun countLiteralMatches(source: String, term: String, matchCase: Boolean): Int {
    if (term.isBlank()) return 0
    var count = 0
    var offset = 0
    while (offset < source.length) {
        val next = source.indexOf(term, offset, ignoreCase = !matchCase)
        if (next < 0) break
        count++
        offset = next + term.length
    }
    return count
}
