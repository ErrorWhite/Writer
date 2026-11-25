package xyz.qingjian.writer

import io.github.suqi8.coui.kmp.basic.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.ChipTabRow
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.ToolbarTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

private enum class WorkCollection(val label: String, val group: String, val key: String, val body: String) {
    Annotation("批注", "research", "annotations", "text"),
    Foreshadow("伏笔", "research", "foreshadows", "description"),
    Event("剧情事件", "research", "events", "description"),
    Relation("因果", "research", "links", "text"),
    Card("资料卡", "creative", "cards", "body"),
    Reference("参考卡", "creative", "references", "structure"),
    Review("审阅", "creative", "reviews", "body"),
    World("世界书", "world", "entries", "content"),
    Memory("记忆", "context", "memories", "text"),
    Feedback("反馈", "context", "notes", "text")
}

@Composable
internal fun ResearchWorkspace(model: WriterModel, book: BookRow, modifier: Modifier = Modifier) {
    var section by rememberSaveable { mutableStateOf(WorkCollection.Annotation) }
    var query by rememberSaveable { mutableStateOf("") }
    var edit by remember { mutableStateOf<JSONObject?>(null) }
    var delete by remember { mutableStateOf<JSONObject?>(null) }
    val payload = remember(book.payload) { JSONObject(book.payload) }
    val entries = remember(payload, section) { listFor(payload, section).objects() }
    val worldImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) model.importWorldBook(book.id, it)
    }
    val worldExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        if (it != null) model.exportRoleplay(it) {
            payload.obj("creative").obj("worldBook").toString(2).toByteArray(Charsets.UTF_8)
        }
    }
    val title = section.label
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChipTabRow(WorkCollection.entries.map { it.label }, section.ordinal, { index ->
            section = WorkCollection.entries[index]
            query = ""
        }, modifier = Modifier.fillMaxWidth(), horizontalPadding = 0.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            WriterSearch(query, { query = it }, "搜索$title", Modifier.weight(1f))
            ToolbarTextButton(text = "添加", onClick = { edit = JSONObject().put("id", newId()) })
        }
        if (section == WorkCollection.World) Row {
            TextButton(onClick = { worldImport.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                Text("导入世界书")
            }
            TextButton(onClick = { worldExport.launch(safeFileName(book.title) + "-世界书.json") }) { Text("导出世界书") }
        }
        val filtered = entries.filter {
            query.isBlank() || it.string("title").contains(query, ignoreCase = true) ||
                it.string(section.body).contains(query, ignoreCase = true)
        }
        if (filtered.isEmpty()) EmptyState(title = if (query.isBlank()) "还没有$title" else "没有匹配的$title")
        filtered.forEach { item ->
            Surface(shape = WriterTheme.shapes.large, color = WriterTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.string("title").ifBlank { item.string("kind").ifBlank { title } },
                        style = WriterTheme.typography.titleMedium)
                    Text(item.string(section.body), maxLines = 5)
                    if (section == WorkCollection.Foreshadow) {
                        Text(if (item.string("status") == "recovered") "已回收" else "未回收")
                    }
                    Row {
                        TextButton(onClick = { edit = item }) { Text("查看与编辑") }
                        TextButton(onClick = { delete = item }) { Text("删除", color = WriterTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    edit?.let { item ->
        ResearchDialog(model, book, section, item, payload) { edit = null }
    }
    delete?.let { item ->
        AlertDialog(
            onDismissRequest = { delete = null }, title = { Text("删除这条$title？") },
            confirmButton = {
                TextButton(onClick = {
                    val collection = section
                    model.perform {
                        mutateBook(book.id) { bookData ->
                            val records = listFor(bookData, collection)
                            val id = item.string("id")
                            for (index in records.length() - 1 downTo 0) if (records.getJSONObject(index).string("id") == id) {
                                records.remove(index)
                            }
                            if (collection == WorkCollection.Event) {
                                val links = bookData.obj("research").array("links")
                                for (index in links.length() - 1 downTo 0) {
                                    val link = links.getJSONObject(index)
                                    if (link.string("from") == id || link.string("to") == id) links.remove(index)
                                }
                            }
                        }
                        delete = null
                    }
                }) { Text("删除") }
            }, dismissButton = { TextButton(onClick = { delete = null }) { Text("取消") } }
        )
    }
}

private fun listFor(book: JSONObject, collection: WorkCollection): JSONArray =
    if (collection == WorkCollection.World) book.obj("creative").obj("worldBook").array("entries")
    else book.obj(collection.group).array(collection.key)

@Composable
private fun ResearchDialog(
    model: WriterModel, book: BookRow, collection: WorkCollection,
    source: JSONObject, payload: JSONObject, dismiss: () -> Unit
) {
    var title by rememberSaveable { mutableStateOf(source.string("title", source.string("kind"))) }
    var body by rememberSaveable { mutableStateOf(source.string(collection.body)) }
    var keys by rememberSaveable { mutableStateOf(source.strings("keys").joinToString("，")) }
    var secondary by rememberSaveable { mutableStateOf(source.strings("secondary").joinToString("，")) }
    var priority by rememberSaveable { mutableStateOf(source.optInt("priority", 100).toString()) }
    var flag by rememberSaveable { mutableStateOf(source.optBoolean("confirmed", true)) }
    var enabled by rememberSaveable { mutableStateOf(source.optBoolean("enabled", true)) }
    var always by rememberSaveable { mutableStateOf(source.optBoolean("always")) }
    var kind by rememberSaveable { mutableStateOf(source.string("kind", "character")) }
    var from by rememberSaveable { mutableStateOf(source.string("from")) }
    var to by rememberSaveable { mutableStateOf(source.string("to")) }
    var anchorText by rememberSaveable { mutableStateOf("") }
    var anchorRole by rememberSaveable { mutableStateOf("线索") }
    var conflict by rememberSaveable { mutableStateOf(false) }
    val events = payload.optJSONObject("research")?.records("events").orEmpty()
    val anchored = collection in setOf(WorkCollection.Annotation, WorkCollection.Event, WorkCollection.Foreshadow)
    AlertDialog(
        onDismissRequest = dismiss, title = { Text(collection.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (collection != WorkCollection.Relation) {
                    OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("标题") })
                }
                OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), minLines = 3, label = { Text("内容") })
                if (anchored) {
                    OutlinedTextField(anchorText, { anchorText = it }, label = { Text("当前章的原文引句") })
                    source.optJSONObject("anchor")?.let { Text("已有正文锚点") }
                }
                when (collection) {
                    WorkCollection.Relation -> {
                        Text("起点")
                        ChoiceRow(events.map { it.string("id") to it.string("title") }, from) { from = it }
                        Text("终点")
                        ChoiceRow(events.map { it.string("id") to it.string("title") }, to) { to = it }
                    }
                    WorkCollection.Card -> {
                        ChoiceRow(listOf("character" to "人物", "rule" to "世界规则", "scene" to "场景"), kind) { kind = it }
                        CheckOption("已确认，可作为模型上下文", flag) { flag = it }
                    }
                    WorkCollection.World -> {
                        OutlinedTextField(keys, { keys = it }, label = { Text("关键词，以逗号分隔") })
                        OutlinedTextField(secondary, { secondary = it }, label = { Text("次级关键词") })
                        OutlinedTextField(priority, { priority = it }, singleLine = true, label = { Text("优先级") })
                        CheckOption("常驻", always) { always = it }
                        CheckOption("启用", enabled) { enabled = it }
                    }
                    WorkCollection.Foreshadow -> {
                        Text("锚点用途")
                        ChoiceRow(listOf("铺垫", "埋设", "线索", "呼应", "回收").map { it to it }, anchorRole) { anchorRole = it }
                        Text("${source.optJSONArray("anchors")?.length() ?: 0} 个既有锚点")
                    }
                    WorkCollection.Memory, WorkCollection.Reference -> CheckOption("启用", enabled) { enabled = it }
                    else -> Unit
                }
                if (conflict) Text("引句在当前章没有唯一匹配，请复制完整段落再试。", color = WriterTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = body.isNotBlank() && (collection != WorkCollection.Relation || from.isNotBlank() && to.isNotBlank() && from != to),
                onClick = {
                    model.perform {
                        val changed = JSONObject(source.toString()).put("title", title.trim()).put(collection.body, body.trim())
                        when (collection) {
                            WorkCollection.Annotation -> changed.put("kind", title.ifBlank { "批注" })
                            WorkCollection.Relation -> changed.put("from", from).put("to", to)
                            WorkCollection.Card -> changed.put("kind", kind).put("confirmed", flag)
                            WorkCollection.World -> changed.put("keys", keyList(keys)).put("secondary", keyList(secondary))
                                .put("always", always).put("enabled", enabled).put("priority", (priority.toIntOrNull() ?: 100).coerceIn(0, 1000))
                            WorkCollection.Reference, WorkCollection.Memory -> changed.put("enabled", enabled)
                            WorkCollection.Foreshadow -> { changed.array("anchors"); if (!changed.has("status")) changed.put("status", "open") }
                            else -> Unit
                        }
                        fun write(target: JSONObject) {
                            val list = listFor(target, collection)
                            val index = list.objects().indexOfFirst { it.string("id") == changed.string("id") }
                            if (index >= 0) list.put(index, changed) else list.put(changed)
                        }
                        if (anchored && anchorText.isNotBlank()) {
                            val snapshot = export(book.id)
                            val chapter = snapshot.records("volumes").flatMap { it.records("chapters") }
                                .first { it.string("id") == book.activeId }
                            editChapter(chapter, chapter.string("text"), ensureParagraphs = true)
                            val matches = chapter.records("paragraphs").filter { it.string("text").contains(anchorText.trim()) }
                            if (matches.size != 1) { conflict = true; return@perform }
                            val anchor = JSONObject().put("chapterId", book.activeId).put("paragraphId", matches.single().string("id"))
                            if (collection == WorkCollection.Foreshadow) {
                                val anchors = changed.array("anchors")
                                anchors.put(JSONObject().put("id", newId()).put("anchor", anchor)
                                    .put("role", anchorRole).put("quote", anchorText.trim()))
                                if (anchorRole == "回收") changed.put("status", "recovered")
                            } else changed.put("anchor", anchor)
                            mutateChapter(book.id, book.activeId, ::write) { it.put("paragraphs", chapter.array("paragraphs")) }
                        } else {
                            require(collection !in setOf(WorkCollection.Annotation, WorkCollection.Event) || changed.has("anchor")) {
                                "请填写当前章的原文引句作为锚点。"
                            }
                            mutateBook(book.id, ::write)
                        }
                        dismiss()
                    }
                }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }
    )
}

@Composable
private fun ChoiceRow(items: List<Pair<String, String>>, selected: String, change: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (key, name) ->
            FilterChip(selected = selected == key, label = name, onClick = { change(key) })
        }
    }
}

@Composable
internal fun CheckOption(name: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked, change)
        Text(name)
    }
}

private fun keyList(value: String) = JSONArray(value.split(',', '，', '、', '\n').map(String::trim)
    .filter(String::isNotBlank).map { it.take(80) }.distinct().take(32))
