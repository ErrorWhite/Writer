package xyz.qingjian.writer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import org.json.JSONObject

private val profileFields = linkedMapOf(
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
        Text("创作配置", style = WriterTheme.typography.titleLarge)
        OutlinedButton(onClick = { profileOpen = true }) { Text("作品立项与文风") }
        OutlinedButton(onClick = { contextOpen = true }) { Text("上下文与世界书预算") }
        OutlinedButton(onClick = { templateOpen = true }) { Text("任务提示词模板") }
        OutlinedButton(onClick = { writingOpen = true }) { Text("写作外观与目标") }
        OutlinedButton(onClick = { snapshotsOpen = true }) { Text("全书还原点") }
        OutlinedButton(onClick = { gitOpen = true }) { Text("Git 版本历史") }
    }
    if (profileOpen) {
        val profile = payload.optJSONObject("aiSetup") ?: JSONObject()
        var fields by remember(profile) { mutableStateOf(profileFields.keys.associateWith { profile.string(it) }) }
        var total by rememberSaveable { mutableStateOf(profile.optInt("wordTarget", 80000).toString()) }
        var chapter by rememberSaveable { mutableStateOf(profile.optInt("chapterTarget", 1500).toString()) }
        SettingsDialog("作品立项", { profileOpen = false }, save = {
            model.perform {
                mutateBook(book.id) { data ->
                    val setup = data.obj("aiSetup")
                    fields.forEach { (key, text) -> setup.put(key, text) }
                    setup.put("wordTarget", total.toIntOrNull()?.coerceIn(1, 10_000_000) ?: 80_000)
                        .put("chapterTarget", chapter.toIntOrNull()?.coerceIn(100, 12_000) ?: 1500)
                        .put("configured", true)
                    if (!fields["referenceText"].isNullOrBlank()) setup.put("referenceType", "paste")
                }
                profileOpen = false
            }
        }) {
            OutlinedTextField(total, { total = it }, singleLine = true, label = { Text("全书目标字数") })
            OutlinedTextField(chapter, { chapter = it }, singleLine = true, label = { Text("单章目标字数") })
            profileFields.forEach { (key, label) ->
                OutlinedTextField(fields[key].orEmpty(), { fields = fields + (key to it) }, label = { Text(label) })
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
        var goal by rememberSaveable { mutableStateOf(payload.optInt("goal", 1500).toString()) }
        var paper by rememberSaveable { mutableStateOf(payload.string("paper", "white")) }
        SettingsDialog("写作外观与目标", { writingOpen = false }, save = {
            model.perform {
                mutateBook(book.id) { data ->
                    data.put("fontSize", size.toIntOrNull()?.coerceIn(13, 32) ?: 17)
                        .put("goal", goal.toIntOrNull()?.coerceIn(0, 100_000) ?: 1500)
                        .put("paper", paper)
                }
                writingOpen = false
            }
        }) {
            OutlinedTextField(size, { size = it }, singleLine = true, label = { Text("正文字号（13–32）") })
            OutlinedTextField(goal, { goal = it }, singleLine = true, label = { Text("编辑器目标字数") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("white" to "白纸", "warm" to "暖纸").forEach { (key, label) ->
                    FilterChip(paper == key, { paper = key }, label = label)
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
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (chosen == null) "全书还原点" else "还原全书？") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (chosen != null) Text("还原前会为当前全书创建一个还原点。")
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
}
