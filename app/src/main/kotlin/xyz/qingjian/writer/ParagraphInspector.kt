package xyz.qingjian.writer

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.suqi8.coui.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
internal fun ParagraphInspector(
    model: WriterModel, book: BookRow, chapter: ChapterRow, text: String, close: () -> Unit,
    changed: (String) -> Unit, ask: (TextRange, Set<Int>) -> Unit
) {
    val spans = remember(text) { paragraphSpans(text) }
    val payload = remember(chapter.payload) { JSONObject(chapter.payload) }
    val records = remember(payload) { payload.records("paragraphs") }
    val research = remember(book.payload) { JSONObject(book.payload).optJSONObject("research") }
    var expanded by rememberSaveable { mutableStateOf(-1) }
    var editing by rememberSaveable { mutableStateOf(-1) }
    var commenting by rememberSaveable { mutableStateOf(-1) }
    var requiring by rememberSaveable { mutableStateOf(-1) }
    var multi by rememberSaveable { mutableStateOf(false) }
    var selected by remember(text) { mutableStateOf(setOf<Int>()) }

    fun anchor(index: Int, update: (JSONObject) -> Unit) {
        model.perform {
            mutateChapter(book.id, chapter.id) { row ->
                check(row.string("text") == text) { "正文已更新，请重新选择段落。" }
                editChapter(row, text, ensureParagraphs = true)
                update(row.records("paragraphs")[index])
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = close) { Text("返回正文") }
            TextButton(onClick = { multi = !multi; selected = emptySet() }) { Text(if (multi) "退出多选" else "多选段落") }
            if (multi) TextButton(enabled = selected.isNotEmpty(), onClick = {
                val first = spans[selected.min()]
                val last = spans[selected.max()]
                ask(TextRange(first.start, last.end), selected)
            }) { Text("审阅 ${selected.size} 段") }
        }
        LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(spans, key = { index, span -> records.getOrNull(index)?.string("id") ?: "paragraph:${span.start}" }) { index, span ->
                val row = records.getOrNull(index)?.takeIf { it.string("text") == span.text }
                val paragraphId = row?.string("id")
                val notes = research?.records("annotations").orEmpty().filter { note ->
                    val point = note.optJSONObject("anchor")
                    paragraphId != null && point != null && point.string("paragraphId") == paragraphId &&
                        point.string("chapterId") == chapter.id
                }
                Surface(
                    shape = WriterTheme.shapes.large,
                    color = if (index in selected) WriterTheme.colorScheme.primaryContainer else WriterTheme.colorScheme.surface
                ) {
                    Column(Modifier.fillMaxWidth().combinedClickable(
                        onClick = {
                            if (multi) selected = if (index in selected) selected - index else selected + index
                            else expanded = if (expanded == index) -1 else index
                        },
                        onDoubleClick = { if (!multi) ask(TextRange(span.start, span.end), setOf(index)) },
                        onLongClick = { if (!multi) editing = index }
                    ).padding(12.dp)) {
                        Text(span.text, style = WriterTheme.typography.bodyLarge)
                        if (notes.isNotEmpty()) Text("${notes.size} 条批注", style = WriterTheme.typography.labelSmall)
                        if (!multi && expanded == index) {
                            Row {
                                TextButton(onClick = { editing = index }) { Text("编辑") }
                                TextButton(onClick = { requiring = index }) { Text("提示") }
                                TextButton(onClick = { commenting = index }) { Text("批注") }
                            }
                            Row {
                                TextButton(onClick = { anchor(index) { it.put("vote", if (it.string("vote") == "keep") "" else "keep") } }) {
                                    Text(if (row?.string("vote") == "keep") "已赞" else "赞")
                                }
                                TextButton(onClick = { anchor(index) { it.put("vote", if (it.string("vote") == "revise") "" else "revise") } }) {
                                    Text(if (row?.string("vote") == "revise") "已踩" else "踩")
                                }
                                TextButton(onClick = { ask(TextRange(span.start, span.end), setOf(index)) }) { Text("AI 改稿") }
                            }
                            notes.forEach { note -> Text(note.string("text"), style = WriterTheme.typography.bodySmall) }
                            val original = row?.string("original")
                            if (original != null && original != span.text && original.isNotBlank()) {
                                TextButton(onClick = { changed(text.replaceRange(span.start, span.end, original)) }) { Text("恢复段落原文") }
                            }
                        }
                    }
                }
            }
        }
    }
    spans.getOrNull(editing)?.let { span ->
        ParagraphDialog("编辑段落", span.text, { editing = -1 }) { body ->
            changed(text.replaceRange(span.start, span.end, body.replace(Regex("[\\r\\n]+"), " ")))
            editing = -1
        }
    }
    if (requiring in spans.indices) ParagraphDialog("逐段写作提示", records.getOrNull(requiring)?.string("instruction").orEmpty(),
        { requiring = -1 }) { body ->
        anchor(requiring) { it.put("instruction", body) }
        requiring = -1
    }
    if (commenting in spans.indices) ParagraphDialog("添加批注", "", { commenting = -1 }) { body ->
        val index = commenting
        if (body.isNotBlank()) model.perform {
            val source = export(book.id).records("volumes").flatMap { it.records("chapters") }.first { it.string("id") == chapter.id }
            check(source.string("text") == text) { "正文已更新，请重新选择段落。" }
            editChapter(source, text, ensureParagraphs = true)
            val paragraph = source.records("paragraphs")[index]
            mutateChapter(book.id, chapter.id, bookEdit = { document ->
                val point = JSONObject().put("chapterId", chapter.id).put("paragraphId", paragraph.string("id"))
                document.obj("research").array("annotations").put(JSONObject().put("id", newId())
                    .put("kind", "批注").put("text", body).put("anchor", point))
            }) { it.put("paragraphs", source.array("paragraphs")) }
        }
        commenting = -1
    }
}

@Composable
private fun ParagraphDialog(title: String, initial: String, dismiss: () -> Unit, complete: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss, title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value, { value = it }, minLines = 3, label = { Text(title) })
            }
        },
        confirmButton = { TextButton(onClick = { complete(value) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }
    )
}
