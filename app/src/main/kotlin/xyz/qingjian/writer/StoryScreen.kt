package xyz.qingjian.writer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun StoryScreen(model: WriterModel, book: BookRow, openEditor: () -> Unit) {
    val entries by remember(book.id) { model.library.story(book.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var selected by rememberSaveable { mutableStateOf(book.activeId) }
    val chapter = entries.find { it.id == selected } ?: entries.lastOrNull()
    var direction by remember { mutableStateOf<JSONObject?>(null) }
    var undo by remember { mutableStateOf(false) }
    if (chapter == null) {
        Text("尚无定稿章节。可在章节操作中整理定稿交接。")
        return
    }
    val handoff = remember(chapter.payload) { JSONObject(chapter.payload).optJSONObject("handoff") }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEach { item ->
            FilterChip(chapter.id == item.id, { selected = item.id }, label = { Text(item.title) })
        }
    }
    TextButton(onClick = { model.perform { select(book.id, chapter.id); openEditor() } }) { Text("打开 ${chapter.title}") }
    if (handoff == null) {
        Text("本章还没有定稿交接。")
        return
    }
    StoryNote("剧情概括", handoff.string("recap"))
    StoryNote("下一章铺垫", handoff.string("bridge"))
    handoff.records("directions").forEach { option ->
        StoryNote(option.string("title"), option.string("detail")) {
            TextButton(onClick = { direction = option }) { Text("按此方向新建章节") }
        }
    }
    for ((key, label) in listOf("threads" to "线索", "relations" to "人物关系", "events" to "剧情事件", "links" to "因果关系")) {
        val records = handoff.records(key)
        if (records.isEmpty()) continue
        Text(label, style = MaterialTheme.typography.titleLarge)
        records.forEachIndexed { index, record ->
            val relation = listOf(record.string("from"), record.string("to")).filter(String::isNotBlank).joinToString(" → ")
            val heading = record.string("title").ifBlank { relation }
            val description = listOf(record.string("label"), record.string("note"), record.string("quote"))
                .filter(String::isNotBlank).joinToString("\n")
            StoryNote(heading, description) {
                if (key == "links") {
                    if (record.optBoolean("confirmed")) Text("已确认")
                    else TextButton(onClick = {
                        model.perform {
                            val bookSnapshot = export(book.id)
                            val source = bookSnapshot.records("volumes").flatMap { it.records("chapters") }.first { it.string("id") == chapter.id }
                            val proposed = JSONObject(handoff.toString())
                            proposed.getJSONArray("links").getJSONObject(index).put("confirmed", true)
                            closeChapter(book.id, chapter.id, source.string("text"), null, proposed)
                        }
                    }) { Text("确认因果") }
                }
            }
        }
    }
    handoff.optJSONArray("archiveDelta")?.let {
        TextButton(onClick = { undo = true }) { Text("撤销归档") }
    }
    direction?.let { option ->
        NameDialog("按此方向新建章节", "章名", option.string("title"), dismiss = { direction = null }) { title ->
            model.perform {
                addChapter(book.id, chapter.volumeId, title, JSONObject().put("direction", option.string("detail"))
                    .put("handoffFrom", chapter.id))
                openEditor()
            }
        }
    }
    if (undo) AlertDialog(
        onDismissRequest = { undo = false }, title = { Text("撤销本章归档？") },
        text = { Text("已另行编辑的资料与被其他条目引用的记录会保留。") },
        confirmButton = {
            TextButton(onClick = { model.perform { undoChapterArchive(book.id, chapter.id); undo = false } }) { Text("撤销归档") }
        }, dismissButton = { TextButton(onClick = { undo = false }) { Text("取消") } }
    )
}

@Composable
private fun StoryNote(title: String, body: String, action: @Composable () -> Unit = {}) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body)
            action()
        }
    }
}

@Composable
internal fun ManualHandoff(model: WriterModel, chapter: ChapterRow, dismiss: () -> Unit) {
    val stored = remember(chapter.payload) { JSONObject(chapter.payload).optJSONObject("handoff") ?: JSONObject() }
    var recap by rememberSaveable { mutableStateOf(stored.string("recap")) }
    var bridge by rememberSaveable { mutableStateOf(stored.string("bridge")) }
    var lesson by rememberSaveable { mutableStateOf("") }
    var directionTitle by rememberSaveable { mutableStateOf("") }
    var directionDetail by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss, title = { Text("定稿交接") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(recap, { recap = it }, minLines = 3, label = { Text("剧情概括") })
                OutlinedTextField(bridge, { bridge = it }, minLines = 3, label = { Text("下一章必须承接的铺垫") })
                OutlinedTextField(lesson, { lesson = it }, label = { Text("添加写作经验") })
                OutlinedTextField(directionTitle, { directionTitle = it }, label = { Text("补充走向标题") })
                OutlinedTextField(directionDetail, { directionDetail = it }, label = { Text("补充走向说明") })
            }
        },
        confirmButton = {
            TextButton(enabled = recap.isNotBlank() && bridge.isNotBlank(), onClick = {
                model.perform {
                    val proposed = JSONObject(stored.toString()).put("recap", recap.trim()).put("bridge", bridge.trim())
                    if (lesson.isNotBlank()) proposed.array("lessons").put(JSONObject().put("kind", "keep").put("text", lesson.trim()))
                    if (directionTitle.isNotBlank() && directionDetail.isNotBlank()) {
                        proposed.array("directions").put(JSONObject().put("title", directionTitle).put("detail", directionDetail))
                    }
                    closeChapter(chapter.bookId, chapter.id, chapter.text, null, proposed)
                    dismiss()
                }
            }) { Text("完成定稿") }
        }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }
    )
}
