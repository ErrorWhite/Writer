package xyz.qingjian.writer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal val profileFields = linkedMapOf(
    "genre" to "类别", "mainCharacter" to "主角", "pointOfView" to "叙事视角", "premise" to "故事内核",
    "theme" to "主题", "setting" to "时代与背景", "readerPromise" to "读者期待", "storyStages" to "阶段推进",
    "writingStyle" to "写作文风", "styleCorrection" to "文风纠偏", "extra" to "其他要求", "referenceText" to "参考样稿"
)

@Composable
internal fun BookSettings(model: WriterModel, book: BookRow) {
    val payload = remember(book.payload) { JSONObject(book.payload) }
    var profileOpen by rememberSaveable { mutableStateOf(false) }
    var contextOpen by rememberSaveable { mutableStateOf(false) }
    var writingOpen by rememberSaveable { mutableStateOf(false) }
    var snapshotsOpen by rememberSaveable { mutableStateOf(false) }
    var templateOpen by rememberSaveable { mutableStateOf(false) }
    var gitOpen by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PreferenceGroup("作品配置") {
            item("profile") { ArrowPreference("立项与文风", onClick = { profileOpen = true }) }
            item("context") { ArrowPreference("上下文与世界书", onClick = { contextOpen = true }) }
            item("templates") { ArrowPreference("任务提示词模板", onClick = { templateOpen = true }) }
            item("appearance") { ArrowPreference("稿纸、分隔线与字数目标", onClick = { writingOpen = true }) }
        }
        PreferenceGroup("版本历史") {
            item("snapshots") { ArrowPreference("全书还原点", onClick = { snapshotsOpen = true }) }
            item("git") { ArrowPreference("Git 版本历史", onClick = { gitOpen = true }) }
        }
    }
    if (profileOpen) {
        val profile = payload.optJSONObject("aiSetup") ?: JSONObject()
        val chapters by remember(book.id) { model.library.directory(book.id) }
            .collectAsStateWithLifecycle(initialValue = emptyList())
        var fields by remember(profile) { mutableStateOf(profileFields.keys.associateWith { profile.string(it) }) }
        var total by rememberSaveable { mutableStateOf(profile.optInt("wordTarget", 80000).toString()) }
        var chapter by rememberSaveable { mutableStateOf(profile.optInt("chapterTarget", 1500).toString()) }
        var referenceType by rememberSaveable { mutableStateOf(profile.string("referenceType", "chapter")) }
        var referenceChapterId by rememberSaveable { mutableStateOf(profile.string("referenceChapterId")) }
        var referenceMenu by remember { mutableStateOf(false) }
        SettingsDialog("作品立项", { profileOpen = false }, save = {
            model.perform {
                val referenceSnapshot = if (referenceType == "chapter" && referenceChapterId.isNotBlank()) {
                    chapterText(book.id, referenceChapterId).ifBlank {
                        profile.string("referenceSnapshot").takeIf { referenceChapterId == profile.string("referenceChapterId") }.orEmpty()
                    }
                } else ""
                mutateBook(book.id) { data ->
                    val setup = data.obj("aiSetup")
                    fields.forEach { (key, text) -> setup.put(key, text) }
                    setup.put("wordTarget", total.toIntOrNull()?.coerceIn(1, 10_000_000) ?: 80_000)
                        .put("chapterTarget", chapter.toIntOrNull()?.coerceIn(100, 12_000) ?: 1500)
                        .put("configured", true)
                    setup.put("referenceType", referenceType)
                        .put("referenceChapterId", referenceChapterId).put("referenceSnapshot", referenceSnapshot)
                }
                profileOpen = false
            }
        }) {
            OutlinedTextField(total, { total = it }, singleLine = true, label = { Text("全书目标字数") })
            OutlinedTextField(chapter, { chapter = it }, singleLine = true, label = { Text("单章目标字数") })
            profileFields.filterKeys { it != "referenceText" }.forEach { (key, label) ->
                OutlinedTextField(fields[key].orEmpty(), { fields = fields + (key to it) }, label = { Text(label) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(referenceType == "chapter", { referenceType = "chapter" }, label = "参考章")
                FilterChip(referenceType == "paste", { referenceType = "paste" }, label = "粘贴样稿")
            }
            if (referenceType == "paste") {
                OutlinedTextField(fields["referenceText"].orEmpty(),
                    { fields = fields + ("referenceText" to it) }, label = { Text("参考样稿") })
            } else {
                Box {
                    TextButton(onClick = { referenceMenu = true }) {
                        Text(chapters.firstOrNull { it.id == referenceChapterId }?.title
                            ?: if (referenceChapterId.isBlank()) "选择参考章" else "参考章已移除")
                    }
                    DropdownMenu(referenceMenu, { referenceMenu = false }) {
                        DropdownMenuItem("清除参考章", onClick = { referenceChapterId = ""; referenceMenu = false })
                        chapters.forEach { entry ->
                            DropdownMenuItem(entry.title, onClick = { referenceChapterId = entry.id; referenceMenu = false })
                        }
                    }
                }
            }
        }
    }
    if (contextOpen) {
        val context = payload.optJSONObject("context") ?: JSONObject()
        val world = payload.optJSONObject("creative")?.optJSONObject("worldBook") ?: JSONObject()
        var summary by rememberSaveable { mutableStateOf(context.string("summary")) }
        var budget by rememberSaveable { mutableStateOf(context.optInt("budget", 12000).toString()) }
        var includeOutline by rememberSaveable { mutableStateOf(context.optBoolean("outline", true)) }
        var includeMemory by rememberSaveable { mutableStateOf(context.optBoolean("memory", true)) }
        var feedback by rememberSaveable { mutableStateOf(context.optBoolean("feedback")) }
        var worldBudget by rememberSaveable { mutableStateOf(world.optInt("budget", 1800).toString()) }
        var depth by rememberSaveable { mutableStateOf(world.optInt("recursion", 0).toString()) }
        SettingsDialog("上下文", { contextOpen = false }, save = {
            model.perform {
                mutateBook(book.id) { data ->
                    data.obj("context").put("summary", summary).put("outline", includeOutline)
                        .put("memory", includeMemory).put("feedback", feedback)
                        .put("budget", budget.toIntOrNull()?.coerceIn(1000, 100_000) ?: 12_000)
                    data.obj("creative").obj("worldBook")
                        .put("budget", worldBudget.toIntOrNull()?.coerceIn(100, 16_000) ?: 1800)
                        .put("recursion", depth.toIntOrNull()?.coerceIn(0, 3) ?: 0)
                }
                contextOpen = false
            }
        }) {
            OutlinedTextField(summary, { summary = it }, minLines = 3, label = { Text("压缩摘要") })
            OutlinedTextField(budget, { budget = it }, singleLine = true, label = { Text("上下文字符预算") })
            CheckOption("附带大纲", includeOutline) { includeOutline = it }
            CheckOption("附带启用记忆", includeMemory) { includeMemory = it }
            CheckOption("附带作者反馈", feedback) { feedback = it }
            OutlinedTextField(worldBudget, { worldBudget = it }, singleLine = true, label = { Text("世界书字符预算") })
            OutlinedTextField(depth, { depth = it }, singleLine = true, label = { Text("世界书递归层数（0–3）") })
        }
    }
    if (writingOpen) {
        var size by rememberSaveable { mutableStateOf(payload.optInt("fontSize", 17).toString()) }
        var font by rememberSaveable { mutableStateOf(payload.string("bodyFont", "serif")) }
        var spacing by rememberSaveable { mutableStateOf(payload.optDouble("bodyLineSpacing", 1.75).toString()) }
        var goal by rememberSaveable { mutableStateOf(payload.optInt("goal", 1500).toString()) }
        var paper by rememberSaveable {
            mutableStateOf(payload.string("paper", "white").let { if (it == "warm") "cream" else it })
        }
        var divider by rememberSaveable { mutableStateOf(payload.string("dividerStyle", "ornament")) }
        SettingsDialog("写作外观与目标", { writingOpen = false }, save = {
            model.perform {
                mutateBook(book.id) { data ->
                    data.put("fontSize", size.toIntOrNull()?.coerceIn(13, 32) ?: 17)
                        .put("bodyFont", font)
                        .put("bodyLineSpacing", spacing.toDoubleOrNull()?.takeIf(Double::isFinite)?.coerceIn(1.3, 2.5) ?: 1.75)
                        .put("goal", goal.toIntOrNull()?.coerceIn(0, 100_000) ?: 1500)
                        .put("paper", paper)
                        .put("dividerStyle", divider)
                }
                writingOpen = false
            }
        }) {
            OutlinedTextField(size, { size = it }, singleLine = true, label = { Text("正文字号（13–32）") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(font == "serif", { font = "serif" }, label = "衬线")
                FilterChip(font == "sans", { font = "sans" }, label = "无衬线")
            }
            OutlinedTextField(spacing, { spacing = it }, singleLine = true, label = { Text("行距倍数（1.3–2.5）") })
            OutlinedTextField(goal, { goal = it }, singleLine = true, label = { Text("编辑器目标字数") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("white" to "白纸", "cream" to "暖白", "blue" to "雾蓝").forEach { (key, label) ->
                    FilterChip(paper == key, { paper = key }, label = label)
                }
            }
            Text("场景分隔线", style = WriterTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ornament" to "菱形", "stars" to "三星", "line" to "细线").forEach { (key, label) ->
                    FilterChip(divider == key, { divider = key }, label = label)
                }
            }
        }
    }
    if (templateOpen) {
        val templates = payload.optJSONObject("creative")?.optJSONObject("templates") ?: JSONObject()
        val labels = mapOf("summary" to "章节摘要", "chapter" to "初稿", "paragraph" to "段落改稿",
            "reference-analysis" to "样本分析", "chapter-review" to "章节审阅", "state-proposal" to "状态提案")
        var values by remember(templates) { mutableStateOf(labels.keys.associateWith { templates.string(it) }) }
        SettingsDialog("任务提示词模板", { templateOpen = false }, save = {
            model.perform {
                mutateBook(book.id) { data ->
                    val stored = data.obj("creative").obj("templates")
                    values.forEach { (key, value) -> stored.put(key, value) }
                }
                templateOpen = false
            }
        }) {
            labels.forEach { (key, label) ->
                OutlinedTextField(values[key].orEmpty(), { values = values + (key to it) },
                    Modifier.fillMaxWidth(), minLines = 2, label = { Text(label) })
            }
        }
    }
    if (snapshotsOpen) BookVersionDialog(model, book) { snapshotsOpen = false }
    if (gitOpen) GitTimeline(model, book) { gitOpen = false }
}

@Composable
private fun SettingsDialog(title: String, dismiss: () -> Unit, save: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) { content() } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } },
        confirmButton = { TextButton(onClick = save) { Text("保存") } }
    )
}

@Composable
private fun BookVersionDialog(model: WriterModel, book: BookRow, dismiss: () -> Unit) {
    val versions by remember(book.id) { model.library.bookVersions(book.id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var chosen by rememberSaveable { mutableStateOf<Long?>(null) }
    var comparison by remember { mutableStateOf<List<BookSnapshotDifference>?>(null) }
    var difference by remember { mutableStateOf<BookSnapshotDifference?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (chosen == null) "全书还原点" else "还原全书？") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val versionId = chosen
                if (versionId != null) {
                    Text("还原前会为当前全书创建一个还原点。")
                    TextButton(onClick = {
                        model.perform {
                            val current = export(book.id)
                            val stored = readBookVersion(book.id, versionId)
                            comparison = withContext(Dispatchers.Default) { compareBookSnapshots(current, stored) }
                        }
                    }) { Text("与当前全书比较") }
                }
                else if (versions.isEmpty()) Text("尚未保存全书还原点")
                else versions.forEach { version ->
                    TextButton(onClick = { chosen = version.id }) {
                        Text(DateFormat.getDateTimeInstance().format(Date(version.createdAt)))
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = { if (chosen != null) chosen = null else dismiss() }) { Text("取消") } },
        confirmButton = {
            val id = chosen
            if (id == null) TextButton(onClick = { model.perform { snapshotBook(book.id); model.notice("已创建全书还原点") } }) {
                Text("创建还原点")
            } else TextButton(onClick = { model.restoreBookVersion(book.id, id, dismiss) }) { Text("还原") }
        }
    )
    comparison?.let { changes ->
        AlertDialog(
            onDismissRequest = { comparison = null }, title = { Text("全书还原点差异") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (changes.isEmpty()) Text("两个版本一致")
                    changes.forEach { change ->
                        TextButton(onClick = { difference = change }) { Text(change.label) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { comparison = null }) { Text("关闭") } }
        )
    }
    difference?.let { change ->
        ReviewDiff(change.current, change.stored, change.label, "当前书稿", "保存版本", onlyChanges = true) {
            difference = null
        }
    }
}

internal data class BookSnapshotDifference(val label: String, val current: String, val stored: String)

internal fun compareBookSnapshots(current: JSONObject, stored: JSONObject): List<BookSnapshotDifference> {
    fun compare(label: String, before: String, after: String) =
        BookSnapshotDifference(label, before, after).takeIf { before != after }

    fun arrangement(book: JSONObject) = book.records("volumes").joinToString("\n\n") { volume ->
        "# ${volume.string("title")} [${volume.string("id")}]\n" +
            volume.records("chapters").joinToString("\n") { chapter ->
                "${chapter.string("title")} [${chapter.string("id")}]"
            }
    }

    fun chapters(book: JSONObject) =
        book.records("volumes").flatMap { it.records("chapters") }.associateBy { it.string("id") }

    fun settings(book: JSONObject) = JSONObject().apply {
        book.keys().asSequence().sorted().filterNot { it in setOf("volumes", "outline", "notes", "activeId") }
            .forEach { key -> put(key, book.get(key)) }
    }

    val live = chapters(current)
    val saved = chapters(stored)
    return buildList {
        compare("卷章目录", arrangement(current), arrangement(stored))?.let(::add)
        compare("全书大纲", current.string("outline"), stored.string("outline"))?.let(::add)
        compare("人物与世界设定", current.string("notes"), stored.string("notes"))?.let(::add)
        val currentSettings = settings(current)
        val savedSettings = settings(stored)
        if (sortedJson(currentSettings) != sortedJson(savedSettings)) {
            add(BookSnapshotDifference("作品配置与资料", currentSettings.toString(2), savedSettings.toString(2)))
        }
        (live.keys + saved.keys).forEach { id ->
            val before = live[id]
            val after = saved[id]
            val title = before?.string("title") ?: after?.string("title").orEmpty()
            compare(title, before?.string("text").orEmpty(), after?.string("text").orEmpty())?.let(::add)
        }
    }
}
