package xyz.qingjian.writer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.parser.Parser
import org.commonmark.renderer.text.TextContentRenderer
import org.json.JSONArray
import org.json.JSONObject

internal enum class WritingIntent(val label: String, val instruction: String) {
    Paragraph("逐段改稿", "按作者指令精修目标段落，保留原有 Markdown 和叙述视角，只返回改写后的段落。"),
    Batch("批量改稿", "逐项修改目标段落。只返回 JSON 数组，每项包含原段落 id 和 after 字符串。不要增加或合并段落。"),
    Rewrite("整章改写", "按作者要求改写正文，保持 Markdown 结构、场景分隔、人物和叙述视角，只返回改写正文。"),
    Summary("章节规划", "整理可修改的章节摘要，写清推进、冲突、承接和章末钩子，只返回摘要。"),
    Chapter("生成初稿", "严格依照已确认的章节摘要和作者要求生成完整初稿，只返回正文。"),
    Outline("全书大纲", "按当前立项与作者要求写全书大纲，说明主线、分卷进展、回收与结局。"),
    Settings("创作设定", "按当前立项和已确认资料整理创作设定，列出人物、世界规则和场景。"),
    Review("章节审阅", "审阅正文与创作设定的连续性、节奏、视角、文风，逐条引用正文提出可操作建议。"),
    Close("定稿交接", """只返回 JSON {"recap":"已发生剧情","bridge":"下章必接铺垫","lessons":[{"kind":"keep","text":"经验"}],"directions":[{"title":"走向","detail":"推进建议"}],"threads":[{"title":"线索","quote":"逐字原文","note":"待解问题"}],"relations":[{"from":"人物","to":"人物","label":"关系变化","quote":"包含两个人名的原文"}],"events":[{"title":"事件","quote":"逐字原文","note":"事实说明"}],"links":[{"from":"本章事件标题或以往事件 id","to":"本章事件标题","label":"因果关系","quote":"明确因果的逐字原文"}]}。逐字引用当前章原文，证据不足时返回空数组，推演只写在备选走向。""")
}

private val writingExtensions = listOf(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create())
private val writingParser = Parser.builder().extensions(writingExtensions).build()
private val plainRenderer = TextContentRenderer.builder().build()
private val structureNames = setOf(
    "StrongEmphasis", "Emphasis", "Strikethrough", "Code", "Heading", "FencedCodeBlock",
    "IndentedCodeBlock", "BulletList", "OrderedList", "ListItem", "TaskListItemMarker",
    "BlockQuote", "TableBlock", "TableHead", "TableBody", "TableRow", "TableCell", "Link",
    "Image", "ThematicBreak", "HardLineBreak", "HtmlBlock", "HtmlInline"
)

internal fun safeRevision(before: String, after: String): Boolean {
    if (after.isBlank()) return false
    val beforeScenes = sceneSections(before)
    val afterScenes = sceneSections(after)
    if (beforeScenes.size != afterScenes.size) return false
    val comparisons = beforeScenes.zip(afterScenes).map { (a, b) ->
        writingParser.parse(a) to writingParser.parse(b)
    }
    val beforeProse = comparisons.joinToString(" ") { plainRenderer.render(it.first) }.replace(Regex("[\\s　]+"), " ").trim()
    val afterProse = comparisons.joinToString(" ") { plainRenderer.render(it.second) }.replace(Regex("[\\s　]+"), " ").trim()
    return beforeProse != afterProse && comparisons.all { (a, b) ->
        val expected = markerShape(a)
        expected.isEmpty() || expected == markerShape(b)
    }
}

private fun markerShape(node: Node): List<String> {
    val name = node.javaClass.simpleName
    val children = generateSequence(node.firstChild) { it.next }.flatMap { markerShape(it).asSequence() }.toList()
    if (name !in structureNames) return children
    val attribute = when (node) {
        is Heading -> node.level.toString()
        is OrderedList -> node.markerStartNumber.toString()
        is FencedCodeBlock -> node.info.orEmpty().trim()
        is Link -> node.destination + "|" + node.title
        is Image -> node.destination + "|" + node.title
        else -> node.toString().substringAfter(name)
    }
    return listOf("$name($attribute)") + children + "/$name"
}

internal fun writingContext(
    book: JSONObject, chapter: JSONObject, intent: WritingIntent, instruction: String,
    target: String, selected: JSONArray? = null
): Pair<String, List<ModelMessage>> {
    val context = book.optJSONObject("context")
    val limit = context?.optInt("budget", 12_000)?.coerceIn(1_000, 100_000) ?: 12_000
    val required = JSONObject().put("书名", book.string("title")).put("章节", chapter.string("title"))
        .put("作者要求", instruction).put("目标正文", target)
    if (selected != null) required.put("目标段落", selected)
    val plan = chapter.optJSONObject("plan")?.string("summary").orEmpty()
    if (plan.isNotBlank()) required.put("章节摘要", plan)
    if (intent == WritingIntent.Chapter) require(plan.isNotBlank()) { "请先确认章节规划，再生成初稿。" }
    val chapters = book.records("volumes").flatMap { it.records("chapters") }
    val previous = chapters.getOrNull(chapters.indexOfFirst { it.string("id") == chapter.string("id") } - 1)
    previous?.optJSONObject("handoff")?.let { required.put("上一章交接", it) }
    val optional = linkedMapOf<String, String>()
    book.optJSONObject("aiSetup")?.let { optional["立项"] = it.toString() }
    if (context?.optBoolean("outline", true) != false) optional["全书大纲"] = book.string("outline")
    optional["创作设定"] = book.string("notes")
    if (context?.string("mode") != "raw") optional["上下文摘要"] = context?.string("summary").orEmpty()
    if (context?.optBoolean("memory", true) != false) {
        optional["启用记忆"] = context?.records("memories").orEmpty()
            .filter { it.optBoolean("enabled", true) }.joinToString("\n") { it.string("text") }
    }
    if (context?.optBoolean("feedback") == true) {
        optional["写作反馈"] = context.records("notes").joinToString("\n") { it.string("text") }
    }
    val creative = book.optJSONObject("creative")
    val template = when (intent) {
        WritingIntent.Paragraph, WritingIntent.Batch, WritingIntent.Rewrite -> "paragraph"
        WritingIntent.Summary -> "summary"
        WritingIntent.Chapter -> "chapter"
        WritingIntent.Review -> "chapter-review"
        else -> null
    }
    if (template != null) optional["任务模板"] = creative?.optJSONObject("templates")?.string(template).orEmpty()
    optional["世界书"] = worldBookMatches(creative?.optJSONObject("worldBook"), target + "\n" + instruction, limit)
        .joinToString("\n") { "${it.string("title")}：${it.string("content")}" }
    optional["已确认资料"] = creative?.records("cards").orEmpty().filter {
        it.optBoolean("confirmed") && (it.optBoolean("pinned") ||
            it.string("title").takeIf(String::isNotBlank)?.let { title -> target.contains(title, ignoreCase = true) } == true)
    }.joinToString("\n") { "${it.string("title")}：${it.string("body")}" }
    var remaining = (limit - required.toString().length).coerceAtLeast(0)
    optional.forEach { (name, text) ->
        if (remaining > 32 && text.isNotBlank()) {
            val part = text.take((remaining - name.length - 8).coerceAtLeast(0))
            val serialized = JSONObject().put(name, part).toString().length
            if (serialized <= remaining) {
                required.put(name, part)
                remaining -= serialized
            }
        }
    }
    return intent.instruction to listOf(ModelMessage("user", required.toString(2)))
}

@Composable
internal fun WritingAiDialog(
    model: WriterModel, book: BookRow, chapter: ChapterRow, selection: TextRange, dismiss: () -> Unit
) {
    val runId = "writing:${book.id}:${chapter.id}"
    val runs by model.generations.collectAsStateWithLifecycle()
    val run = runs[runId]
    val chapterPayload = remember(chapter.payload) { JSONObject(chapter.payload) }
    val review = chapterPayload.optJSONObject("nativeReview")
    var intent by rememberSaveable { mutableStateOf(WritingIntent.Paragraph) }
    var instruction by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var change by remember(review?.string("id")) { mutableStateOf(review?.string("after").orEmpty()) }
    var selected by remember(review?.toString()) { mutableStateOf(setOf<String>()) }
    var substantial by rememberSaveable { mutableStateOf(false) }
    val intentForReview = review?.string("intent")?.let { name -> WritingIntent.entries.find { it.name == name } }
    val generating = run?.running == true

    fun generate() {
        val task = intent
        var baseline = ""
        var target = ""
        var spanOffset = 0
        var segments: List<JSONObject> = emptyList()
        model.generate(runId, task.label, prepare = {
            val snapshot = export(book.id)
            val source = snapshot.records("volumes").flatMap { it.records("chapters") }.first { it.string("id") == chapter.id }
            baseline = source.string("text")
            val spans = paragraphSpans(baseline)
            val caret = selection.start.coerceIn(0, baseline.length)
            val span = spans.firstOrNull { caret in it.start..it.end } ?: spans.lastOrNull { it.start <= caret }
            target = if (task == WritingIntent.Paragraph) requireNotNull(span) { "请先在正文中定位一个段落。" }.text
                else if (task == WritingIntent.Batch || task == WritingIntent.Chapter) "" else baseline
            spanOffset = span?.start ?: 0
            if (task == WritingIntent.Batch) {
                check(!selection.collapsed) { "请在正文里选中要批量改写的段落。" }
                editChapter(source, baseline, ensureParagraphs = true)
                val existing = source.records("paragraphs")
                segments = spans.mapIndexedNotNull { index, candidate ->
                    if (candidate.start >= selection.max || candidate.end <= selection.min) null else JSONObject()
                        .put("id", existing[index].string("id")).put("start", candidate.start).put("before", candidate.text)
                }.take(40)
                require(segments.isNotEmpty()) { "选中的范围没有可改写段落。" }
                mutateChapter(book.id, chapter.id) { it.put("paragraphs", source.array("paragraphs")) }
            } else if (task == WritingIntent.Paragraph) {
                editChapter(source, baseline, ensureParagraphs = true)
                val existing = source.records("paragraphs")
                val index = spans.indexOf(span)
                if (index in existing.indices) {
                    segments = listOf(JSONObject().put("id", existing[index].string("id"))
                        .put("start", spanOffset).put("before", target))
                    mutateChapter(book.id, chapter.id) { it.put("paragraphs", source.array("paragraphs")) }
                }
            }
            writingContext(snapshot, source, task, instruction, target,
                if (task == WritingIntent.Batch) JSONArray(segments) else null)
        }, accept = { reply ->
            val id = newId()
            val body = JSONObject().put("id", id).put("intent", task.name).put("baseline", baseline)
                .put("before", target).put("start", spanOffset).put("after", reply)
            val drafts = JSONArray()
            when (task) {
                WritingIntent.Batch -> {
                    val output = JSONArray(stripJsonFence(reply)).objects()
                    require(output.map { it.string("id") }.distinct().size == output.size) { "模型返回了重复段落。" }
                    for (item in output) {
                        val segment = segments.firstOrNull { it.string("id") == item.string("id") }
                            ?: error("模型返回了未选择的段落。")
                        val after = item.string("after").trim()
                        if ('\n' !in after && '\r' !in after && safeRevision(segment.string("before"), after)) {
                            drafts.put(segment.put("after", leadingIndent(segment.string("before")) + after))
                        }
                    }
                    require(drafts.length() > 0) { "没有能保留原文结构的候选段落。" }
                    body.put("items", drafts)
                }
                WritingIntent.Paragraph -> {
                    val after = reply.trim()
                    require('\n' !in after && '\r' !in after && safeRevision(target, after)) { "候选段落改变了格式或结构。" }
                    body.put("after", leadingIndent(target) + after)
                    segments.firstOrNull()?.let { body.put("paragraphId", it.string("id")) }
                }
                WritingIntent.Rewrite -> require(safeRevision(target, reply)) { "候选稿改变了原正文的 Markdown 结构。" }
                WritingIntent.Close -> checkedHandoff(JSONObject(stripJsonFence(reply)), JSONObject().put("text", baseline))
                else -> Unit
            }
            mutateChapter(book.id, chapter.id) { saved ->
                saved.put("nativeReview", body)
                if (task == WritingIntent.Batch) saved.put("batchReview", drafts)
                val paragraphId = body.string("paragraphId")
                if (paragraphId.isNotBlank()) saved.records("paragraphs").find { it.string("id") == paragraphId }?.let { paragraph ->
                    paragraph.array("drafts").put(JSONObject().put("id", id).put("base", target)
                        .put("text", body.string("after")).put("state", "pending"))
                    paragraph.put("activeDraftId", id)
                }
            }
        })
    }

    fun decide(accept: Boolean) {
        val current = review ?: return
        val task = intentForReview ?: return
        model.perform {
            if (task == WritingIntent.Close && accept) {
                closeChapter(book.id, chapter.id, current.string("baseline"), current.string("id"),
                    JSONObject(stripJsonFence(change)))
                model.clearGeneration(runId)
                return@perform
            }
            val applyToBook: ((JSONObject) -> Unit)? = if (!accept) null else when (task) {
                WritingIntent.Outline -> { data -> data.put("outline", change) }
                WritingIntent.Settings -> { data -> data.put("notes", change) }
                WritingIntent.Review -> { data -> data.obj("creative").array("reviews")
                    .put(JSONObject().put("id", newId()).put("chapterId", chapter.id).put("body", change)) }
                else -> null
            }
            mutateChapter(book.id, chapter.id, applyToBook) { saved ->
                check(saved.optJSONObject("nativeReview")?.string("id") == current.string("id")) { "候选稿已经变更。" }
                val baseline = current.string("baseline")
                val before = current.string("before")
                if (accept) when (task) {
                    WritingIntent.Paragraph, WritingIntent.Batch, WritingIntent.Rewrite, WritingIntent.Chapter -> {
                        check(saved.string("text") == baseline) { "正文已变更，请重新生成候选稿。" }
                        when (task) {
                            WritingIntent.Paragraph -> {
                                val offset = current.getInt("start")
                                require('\r' !in change && '\n' !in change && safeRevision(before, change)) { "请保留段落结构。" }
                                val text = baseline.replaceRange(offset, offset + before.length, change)
                                require(safeRevision(baseline, text)) { "请保留正文 Markdown 结构。" }
                                editChapter(saved, text)
                            }
                            WritingIntent.Batch -> {
                                val kept = current.records("items").filter { it.string("id") in selected }
                                require(kept.isNotEmpty()) { "请选择要采用的候选段落。" }
                                val text = kept.sortedByDescending { it.getInt("start") }.fold(baseline) { value, item ->
                                    val start = item.getInt("start")
                                    value.replaceRange(start, start + item.string("before").length, item.string("after"))
                                }
                                require(safeRevision(baseline, text)) { "请保留正文 Markdown 结构。" }
                                editChapter(saved, text)
                                val remaining = current.records("items").filterNot { it.string("id") in selected }
                                if (remaining.isNotEmpty()) {
                                    val paragraphs = saved.records("paragraphs")
                                    val spans = paragraphSpans(text)
                                    val updated = remaining.mapNotNull { item ->
                                        val index = paragraphs.indexOfFirst { it.string("id") == item.string("id") }
                                        spans.getOrNull(index)?.takeIf { it.text == item.string("before") }
                                            ?.let { item.put("start", it.start) }
                                    }
                                    if (updated.isNotEmpty()) {
                                        saved.put("batchReview", JSONArray(updated))
                                        saved.put("nativeReview", current.put("baseline", text).put("items", JSONArray(updated)))
                                        return@mutateChapter
                                    }
                                }
                            }
                            WritingIntent.Rewrite -> {
                                require(safeRevision(before, change)) { "请保留正文 Markdown 结构。" }
                                editChapter(saved, change)
                            }
                            else -> editChapter(saved, change)
                        }
                    }
                    WritingIntent.Summary -> saved.obj("plan").put("summary", change)
                    WritingIntent.Close -> Unit
                    else -> Unit
                }
                if (task == WritingIntent.Batch) saved.remove("batchReview")
                saved.records("paragraphs").find { it.string("id") == current.string("paragraphId") }
                    ?.records("drafts")?.find { it.string("id") == current.string("id") }
                    ?.put("state", if (accept) "accepted" else "rejected")
                    ?.apply { if (accept) put("text", change) }
                saved.remove("nativeReview")
            }
            model.clearGeneration(runId)
        }
    }

    AlertDialog(
        onDismissRequest = dismiss, title = { Text(intentForReview?.label ?: "AI 写作与审阅") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (review == null) {
                    TextButton(enabled = !generating, onClick = { menu = true }) { Text(intent.label) }
                    DropdownMenu(menu, { menu = false }) {
                        WritingIntent.entries.forEach { task ->
                            DropdownMenuItem({ Text(task.label) }, onClick = { intent = task; menu = false })
                        }
                    }
                    if (!generating) OutlinedTextField(instruction, { instruction = it }, Modifier.fillMaxWidth(),
                        label = { Text("作者要求") })
                    if (run != null) {
                        if (run.error != null) Text(run.error, color = MaterialTheme.colorScheme.error)
                        if (run.reasoning.isNotBlank()) {
                            TextButton(onClick = { substantial = !substantial }) { Text("模型推理摘要") }
                            if (substantial) Text(run.reasoning)
                        }
                        if (run.text.isNotBlank()) Text(run.text)
                    }
                } else if (intentForReview == WritingIntent.Batch) {
                    review.records("items").forEach { item ->
                        val id = item.string("id")
                        Row {
                            io.github.suqi8.coui.kmp.basic.Checkbox(id in selected, {
                                selected = if (it) selected + id else selected - id
                            })
                            Column {
                                Text(item.string("before"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(item.string("after"))
                            }
                        }
                    }
                } else {
                    if (review.string("before").isNotBlank()) {
                        Text("原文", style = MaterialTheme.typography.labelLarge)
                        Text(review.string("before"), Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()))
                    }
                    OutlinedTextField(change, { change = it }, Modifier.fillMaxWidth(), minLines = 5,
                        label = { Text("候选稿") })
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = dismiss) { Text("关闭") }
                if (review != null) TextButton(onClick = { decide(false) }) { Text("拒绝") }
            }
        },
        confirmButton = {
            when {
                generating -> TextButton(onClick = { model.stopGeneration(runId) }) { Text("停止") }
                review != null -> TextButton(enabled = if (intentForReview == WritingIntent.Batch) selected.isNotEmpty()
                    else change.isNotBlank(), onClick = { decide(true) }) { Text("采用") }
                else -> TextButton(onClick = ::generate) { Text("生成候选") }
            }
        }
    )
}

private fun leadingIndent(text: String) = text.takeWhile { it in " \t　" }
private fun stripJsonFence(text: String): String = text.trim().removePrefix("```json").removePrefix("```")
    .removeSuffix("```").trim()
