package xyz.qingjian.writer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
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
    Review("章节审阅", """检查设定一致性、跨章衔接和文风。只返回 {"items":[{"role":"continuity|transition|style","paragraphId":"原段落编号","quote":"逐字原文","note":"建议"}]}。最多 8 条。"""),
    Extract("整理资料", """从正文提取可确认资料。只返回 {"items":[{"kind":"character|rule|scene|foreshadow|event|annotation","title":"标题","body":"说明","paragraphId":"原段落编号","quote":"逐字原文"}]}。最多 8 条。"""),
    State("状态提案", """根据正文为目标条目提出可证实的状态变更。只返回 {"items":[{"kind":"character|rule|foreshadow","targetId":"目标编号","paragraphId":"原段落编号","quote":"逐字原文","note":"说明","value":"新状态"}]}。"""),
    Reference("样本分析", """从作者提供的样本中抽象叙事手法。只返回包含 structure、rhythm、viewpoint、conflict、hook 五个字符串的 JSON 对象。"""),
    Profile("立项建议", """从作者要求、书稿构思与可用的样稿提出作品立项候选。只返回 JSON 对象，可用字符串字段 genre、mainCharacter、pointOfView、premise、theme、setting、readerPromise、storyStages、extra、writingStyle。缺少样稿时省略 writingStyle。"""),
    Volume("下一卷规划", """按已确认的剧情设计下一卷。只返回 JSON {"title":"卷名","direction":"主要冲突、阶段推进、卷末落点、未回收伏笔"}。"""),
    Context("压缩上下文", "把提供的书稿及记忆压缩为可用于续写的上下文摘要。分别保留设定、已确认事件和未回收伏笔。"),
    Close("定稿交接", """只返回 JSON {"recap":"已发生剧情","bridge":"下章必接铺垫","lessons":[{"kind":"keep","text":"经验"}],"directions":[{"title":"走向","detail":"推进建议"}],"threads":[{"title":"线索","quote":"逐字原文","note":"待解问题"}],"relations":[{"from":"人物","to":"人物","label":"关系变化","quote":"包含两个人名的原文"}],"events":[{"title":"事件","quote":"逐字原文","note":"事实说明"}],"links":[{"from":"本章事件标题或以往事件 id","to":"本章事件标题","label":"因果关系","quote":"明确因果的逐字原文"}]}。逐字引用当前章原文，证据不足时返回空数组，推演只写在备选走向。""")
}

internal val writingExtensions = listOf(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create())
private val writingParser = Parser.builder().extensions(writingExtensions).build()
private val plainRenderer = TextContentRenderer.builder().build()

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
    val children = generateSequence(node.firstChild) { it.next }.flatMap { markerShape(it).asSequence() }.toList()
    val type = when (node) {
        is StrongEmphasis -> "strong"
        is Emphasis -> "emphasis"
        is Strikethrough -> "deleted"
        is Code -> "inline-code"
        is Heading -> "heading:${node.level}"
        is OrderedList -> "ordered:${node.markerStartNumber}"
        is BulletList -> "list"
        is ListItem -> "item"
        is TaskListItemMarker -> "task:${node.isChecked}"
        is FencedCodeBlock -> "fence:${node.info.orEmpty().trim()}"
        is IndentedCodeBlock -> "indented-code"
        is BlockQuote -> "quote"
        is TableBlock -> "table"
        is TableHead -> "table-head"
        is TableBody -> "table-body"
        is TableRow -> "table-row"
        is TableCell -> "table-cell:${node.alignment}"
        is Link -> "link:${node.destination}|${node.title}"
        is Image -> "image:${node.destination}|${node.title}"
        is ThematicBreak -> "rule"
        is HardLineBreak -> "break"
        is HtmlBlock -> "html-block"
        is HtmlInline -> "html-inline:${node.literal}"
        else -> return children
    }
    return listOf(type) + children + "/$type"
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
    if (intent == WritingIntent.Batch && selected != null) required.put("审阅原文", JSONArray(selected.objects().map {
        JSONObject().put("chapterId", chapter.string("id")).put("paragraphId", it.string("id")).put("text", it.string("before"))
    }))
    if (intent in evidenceIntents) required.put("审阅原文", JSONArray(evidenceFor(chapter).filter { it.string("text").isNotBlank() }))
    if (intent == WritingIntent.Extract) required.put("资料类型", JSONArray(evidenceKinds))
    if (intent == WritingIntent.State) required.put("可提案条目", JSONArray(stateTargets(book)))
    if (intent == WritingIntent.Reference) required.put("样本", instruction.ifBlank { target })
    if (intent == WritingIntent.Profile) required.put("作者构思", instruction.ifBlank { target })
    if (intent == WritingIntent.Close) required.put("目标段落", target)
    val plan = chapter.optJSONObject("plan")?.string("summary").orEmpty()
    val chapterPlan = chapter.optJSONObject("plan")
    chapterPlan?.string("direction")?.takeIf(String::isNotBlank)?.let { required.put("已选走向", it) }
    if (plan.isNotBlank()) required.put("章节摘要", plan)
    if (intent == WritingIntent.Chapter) require(plan.isNotBlank()) { "请先确认章节规划，再生成初稿。" }
    val chapters = book.records("volumes").flatMap { it.records("chapters") }
    val previous = chapters.firstOrNull { it.string("id") == chapterPlan?.string("handoffFrom") }
        ?: chapters.getOrNull(chapters.indexOfFirst { it.string("id") == chapter.string("id") } - 1)
    previous?.optJSONObject("handoff")?.let { required.put("上一章交接", it) }
    val optional = linkedMapOf<String, String>()
    book.optJSONObject("aiSetup")?.let { profile ->
        val metadata = JSONObject(profile.toString())
        metadata.remove("referenceText")
        metadata.remove("referenceSnapshot")
        optional["立项"] = metadata.toString()
        if (intent == WritingIntent.Profile || profile.string("writingStyle").isBlank()) {
            val sample = if (profile.string("referenceType") == "paste") profile.string("referenceText") else {
                chapters.firstOrNull { it.string("id") == profile.string("referenceChapterId") }
                    ?.string("text").orEmpty().ifBlank { profile.string("referenceSnapshot") }
            }
            optional["文风样稿"] = sample.take(if (intent == WritingIntent.Profile) 2_400 else 720)
        }
    }
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
        WritingIntent.State -> "state-proposal"
        WritingIntent.Reference -> "reference-analysis"
        else -> null
    }
    if (template != null) optional["任务模板"] = creative?.optJSONObject("templates")?.string(template).orEmpty()
    if (intent in setOf(WritingIntent.Paragraph, WritingIntent.Batch, WritingIntent.Rewrite, WritingIntent.Chapter)) {
        optional["写作技能"] = skillPrompt(book, template.orEmpty())
    }
    val worldQuery = buildList {
        add(target)
        add(instruction)
        add(plan)
        selected?.objects()?.forEach { add(it.string("before")) }
    }.joinToString("\n")
    optional["世界书"] = worldBookMatches(creative?.optJSONObject("worldBook"), worldQuery, limit)
        .joinToString("\n") { "${it.string("title")}：${it.string("content")}" }
    optional["附件摘要"] = creative?.records("assets").orEmpty().filter { it.optBoolean("context") }
        .take(6).joinToString("\n") { "${it.string("title")}：${it.string("summary").take(400)}" }
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
    model: WriterModel, book: BookRow, chapter: ChapterRow, selection: TextRange,
    pickedParagraphs: Set<Int>, initialIntent: WritingIntent? = null, dismiss: () -> Unit
) {
    val runId = "writing:${book.id}:${chapter.id}"
    val runs by model.generations.collectAsStateWithLifecycle()
    val run = runs[runId]
    val chapterPayload = remember(chapter) { chapterJson(chapter) }
    val review = remember(chapterPayload) { pendingWritingReview(chapterPayload) }
    val interruptedDrafts = chapterPayload.records("nativeInterrupted")
    val previousReview = chapterPayload.optJSONObject("nativePreviousReview")
    var intent by rememberSaveable {
        mutableStateOf(initialIntent ?: if (pickedParagraphs.size > 1) WritingIntent.Batch else WritingIntent.Paragraph)
    }
    var instruction by rememberSaveable { mutableStateOf("") }
    val acceptedSummary = chapterPayload.optJSONObject("plan")?.string("summary").orEmpty()
    var summary by rememberSaveable(chapter.id, acceptedSummary) { mutableStateOf(acceptedSummary) }
    var menu by remember { mutableStateOf(false) }
    var change by remember(review?.string("id"), review?.string("after"), review?.string("editedAfter")) {
        mutableStateOf(review?.let { it.string("editedAfter", it.string("after")) }.orEmpty())
    }
    var selected by remember(review?.string("id"), review?.records("items")?.map {
        it.string("candidateId", it.string("id"))
    }) { mutableStateOf(setOf<String>()) }
    var batchEdits by remember(review?.toString()) { mutableStateOf(emptyMap<String, String>()) }
    var substantial by rememberSaveable { mutableStateOf(false) }
    var fullDiff by rememberSaveable { mutableStateOf(false) }
    var contextPreview by remember { mutableStateOf<String?>(null) }
    var interruptedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showPrevious by rememberSaveable { mutableStateOf(false) }
    val intentForReview = review?.string("intent")?.let { name -> WritingIntent.entries.find { it.name == name } }
    val profileProposals = remember(review) {
        if (intentForReview != WritingIntent.Profile) emptyMap()
        else {
            val fields = runCatching { JSONObject(stripJsonFence(review?.string("after").orEmpty())) }.getOrNull()
            profileFields.keys.filterNot { it == "referenceText" || it == "styleCorrection" }
                .mapNotNull { key -> (fields?.opt(key) as? String)?.trim()?.takeIf(String::isNotBlank)?.let { key to it } }
                .toMap()
        }
    }
    val generating = run?.running == true

    fun candidateText(item: JSONObject) =
        batchEdits[item.string("id")] ?: item.string("editedAfter", item.string("after"))

    fun withAuthorEdits(candidate: JSONObject): JSONObject = JSONObject(candidate.toString()).apply {
        if (intentForReview == WritingIntent.Batch) {
            put("items", JSONArray(candidate.records("items").map { item ->
                JSONObject(item.toString()).apply {
                    batchEdits[item.string("id")]?.let { put("editedAfter", it) }
                }
            }))
        } else if (change != candidate.string("after")) {
            put("editedAfter", change)
        } else remove("editedAfter")
    }

    suspend fun Library.prepareWriting(
        task: WritingIntent, authorRequest: String, recordAnchors: Boolean, replacing: JSONObject? = null
    ): WritingRequest {
        val snapshot = export(book.id)
        val source = snapshot.records("volumes").flatMap { it.records("chapters") }.first { it.string("id") == chapter.id }
        val baseline = source.string("text")
        val sourceSummary = source.optJSONObject("plan")?.string("summary").orEmpty()
        val spans = paragraphSpans(baseline)
        val caret = if (task == WritingIntent.Paragraph && replacing != null) {
            val id = replacing.string("paragraphId")
            val index = source.records("paragraphs").indexOfFirst { id.isNotEmpty() && it.string("id") == id }
            spans.getOrNull(index)?.start ?: run {
                check(baseline == replacing.string("baseline")) { "原段落已移动，请在正文重新选段。" }
                replacing.optInt("start")
            }
        } else selection.start.coerceIn(0, baseline.length)
        val span = spans.firstOrNull { caret in it.start..it.end } ?: spans.lastOrNull { it.start <= caret }
        val target = if (task == WritingIntent.Paragraph) requireNotNull(span) { "请先在正文中定位一个段落。" }.text
            else if (task == WritingIntent.Batch || task == WritingIntent.Chapter) ""
            else if (task == WritingIntent.Summary) sourceSummary else baseline
        val spanOffset = span?.start ?: 0
        var segments: List<JSONObject> = emptyList()
        if (task == WritingIntent.Batch) {
            check(replacing != null || pickedParagraphs.isNotEmpty() || !selection.collapsed) { "请在正文里选中要批量改写的段落。" }
            editChapter(source, baseline, ensureParagraphs = true)
            val existing = source.records("paragraphs")
            val previousIds = replacing?.records("items")?.map { it.string("id") }?.toSet()
            segments = spans.mapIndexedNotNull { index, candidate ->
                if (index !in existing.indices) return@mapIndexedNotNull null
                val chosen = when {
                    previousIds != null -> existing[index].string("id") in previousIds
                    pickedParagraphs.isNotEmpty() -> index in pickedParagraphs
                    else -> candidate.start < selection.max && candidate.end > selection.min
                }
                if (!chosen) null else JSONObject()
                    .put("id", existing[index].string("id")).put("start", candidate.start).put("before", candidate.text)
            }.take(40)
            require(segments.isNotEmpty()) { "选中的范围没有可改写段落。" }
            check(previousIds == null || segments.map { it.string("id") }.toSet() == previousIds) {
                "有候选段落已移动，请在正文重新选段。"
            }
        } else if (task in evidenceIntents) {
            editChapter(source, baseline, ensureParagraphs = true)
            require(evidenceFor(source).any { it.string("text").isNotBlank() }) { "请先写下用于审阅的正文。" }
        } else if (task == WritingIntent.Paragraph) {
            editChapter(source, baseline, ensureParagraphs = true)
            val existing = source.records("paragraphs")
            val index = spans.indexOf(span)
            if (index in existing.indices) {
                segments = listOf(JSONObject().put("id", existing[index].string("id"))
                    .put("start", spanOffset).put("before", target))
            }
        }
        val prompt = writingContext(snapshot, source, task, authorRequest, target,
            if (task == WritingIntent.Batch) JSONArray(segments) else null)
        if (recordAnchors && (segments.isNotEmpty() || task in evidenceIntents)) {
            mutateChapter(book.id, chapter.id) {
                check(it.string("text") == baseline) { "准备期间正文已更新，请重新生成。" }
                it.put("paragraphs", source.array("paragraphs"))
            }
        }
        return WritingRequest(snapshot, source, baseline, sourceSummary, target, spanOffset, segments, prompt)
    }

    fun generate(replacing: JSONObject? = null) {
        val task = if (replacing == null) intent else intentForReview ?: return
        val authorRequest = instruction.ifBlank { replacing?.string("instruction").orEmpty() }
        val retainedReview = replacing?.let(::withAuthorEdits)
        var request: WritingRequest? = null
        model.generate(runId, task.label, skillBookId = book.id, prepare = {
            prepareWriting(task, authorRequest, recordAnchors = true, replacing = replacing).also { request = it }.prompt
        }, interrupted = { partial ->
            request?.let { source ->
                val result = writingCandidate(task, authorRequest, source, partial)
                mutateChapter(book.id, chapter.id) { saved -> saved.array("nativeInterrupted").put(result) }
            }
        }, accept = { reply ->
            val source = checkNotNull(request)
            val (sourceBook, sourceChapter, baseline, sourceSummary, target, _, segments) = source
            val body = writingCandidate(task, authorRequest, source, reply)
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
                }
                WritingIntent.Rewrite -> require(safeRevision(target, reply)) { "候选稿改变了原正文的 Markdown 结构。" }
                WritingIntent.Chapter -> {
                    require(baseline.isBlank() || safeRevision(baseline, reply)) { "初稿改变了原正文的 Markdown 结构。" }
                    body.put("before", baseline)
                }
                WritingIntent.Close -> checkedHandoff(JSONObject(stripJsonFence(reply)), JSONObject().put("text", baseline))
                WritingIntent.Review, WritingIntent.Extract, WritingIntent.State -> {
                    val items = JSONObject(stripJsonFence(reply)).getJSONArray("items")
                    val verified = checkedEvidence(items, task, sourceChapter, sourceBook)
                    body.put("items", JSONArray(verified))
                }
                WritingIntent.Reference, WritingIntent.Profile, WritingIntent.Volume -> JSONObject(stripJsonFence(reply))
                else -> Unit
            }
            mutateChapter(book.id, chapter.id) { saved ->
                val previous = pendingWritingReview(saved)
                check(previous?.toString() == replacing?.toString()) { "候选稿已经在别处更新。" }
                if (task == WritingIntent.Summary || task == WritingIntent.Chapter) {
                    check(saved.string("text") == baseline &&
                        saved.optJSONObject("plan")?.string("summary").orEmpty() == sourceSummary) {
                        "生成期间摘要或正文已更新，请重新生成。"
                    }
                }
                if (task == WritingIntent.Chapter) rememberChapterDraft(saved, baseline, reply)
                if (retainedReview != null) saved.put("nativePreviousReview", retainedReview)
                saved.put("nativeReview", body)
                if (task == WritingIntent.Batch) saved.put("batchReview", drafts)
                val paragraphId = body.string("paragraphId")
                if (paragraphId.isNotBlank()) saved.records("paragraphs").find { it.string("id") == paragraphId }?.let { paragraph ->
                    val id = body.string("id")
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
        val archived = withAuthorEdits(current).put("state", if (accept) "accepted" else "rejected")
        model.perform {
            if (task == WritingIntent.Close && accept) {
                closeChapter(book.id, chapter.id, current.string("baseline"), current,
                    JSONObject(stripJsonFence(change)), archived)
                model.clearGeneration(runId)
                return@perform
            }
            if (accept && task in evidenceIntents) {
                val snapshot = export(book.id)
                val source = snapshot.records("volumes").flatMap { it.records("chapters") }.first { it.string("id") == chapter.id }
                val items = current.records("items").filter { it.string("candidateId") in selected }
                require(items.isNotEmpty()) { "请选择候选条目。" }
                val verified = checkedEvidence(JSONArray(items), task, source, snapshot)
                mutateChapter(book.id, chapter.id, { useEvidence(it, task, verified) }) { saved ->
                    check(saved.optJSONObject("nativeReview")?.toString() == current.toString()) { "候选已经变更。" }
                    val remaining = current.records("items").filterNot { it.string("candidateId") in selected }
                    if (remaining.isEmpty()) {
                        saved.remove("nativeReview")
                        saved.put("nativePreviousReview", archived)
                    }
                    else saved.put("nativeReview", JSONObject(current.toString()).put("items", JSONArray(remaining)))
                }
                model.clearGeneration(runId)
                return@perform
            }
            if (accept && task == WritingIntent.Volume) {
                acceptVolumePlan(book.id, chapter.id, current, archived, JSONObject(stripJsonFence(change)))
                model.clearGeneration(runId)
                return@perform
            }
            val applyToBook: ((JSONObject) -> Unit)? = if (!accept) null else when (task) {
                WritingIntent.Outline -> { data -> data.put("outline", change) }
                WritingIntent.Settings -> { data -> data.put("notes", change) }
                WritingIntent.Context -> { data -> data.obj("context").put("summary", change) }
                WritingIntent.Reference -> { data ->
                    val sample = JSONObject(stripJsonFence(change))
                    val record = JSONObject().put("id", newId()).put("title", "AI 参考卡").put("enabled", true)
                    listOf("structure", "rhythm", "viewpoint", "conflict", "hook").forEach { field ->
                        require(sample.string(field).isNotBlank()) { "参考卡缺少 $field。" }
                        record.put(field, sample.string(field))
                    }
                    data.obj("creative").array("references").put(record)
                }
                WritingIntent.Profile -> { data ->
                    val accepted = profileProposals.filterKeys { it in selected }
                    require(accepted.isNotEmpty()) { "请选择要采用的立项字段。" }
                    val profile = data.obj("aiSetup")
                    accepted.forEach { (key, value) -> profile.put(key, value) }
                    profile.put("configured", true)
                }
                else -> null
            }
            mutateChapter(book.id, chapter.id, applyToBook) { saved ->
                check(pendingWritingReview(saved)?.toString() == current.toString()) { "候选稿已经变更。" }
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
                                val text = applyBatchCandidates(baseline, kept, ::candidateText)
                                require(safeRevision(baseline, text)) { "请保留正文 Markdown 结构。" }
                                editChapter(saved, text)
                                val remaining = current.records("items").filterNot { it.string("id") in selected }
                                if (remaining.isNotEmpty()) {
                                    val paragraphs = saved.records("paragraphs")
                                    val spans = paragraphSpans(text)
                                    val updated = remaining.mapNotNull { item ->
                                        val index = paragraphs.indexOfFirst { it.string("id") == item.string("id") }
                                        spans.getOrNull(index)?.takeIf { it.text == item.string("before") }
                                            ?.let { span ->
                                                JSONObject(item.toString()).put("start", span.start).apply {
                                                    batchEdits[item.string("id")]?.let { put("editedAfter", it) }
                                                }
                                            }
                                    }
                                    check(updated.size == remaining.size) { "无法保留其余候选的段落位置，请重新选择。" }
                                    saved.put("batchReview", JSONArray(updated))
                                    saved.put("nativeReview", JSONObject(current.toString()).put("baseline", text)
                                        .put("items", JSONArray(updated)))
                                    return@mutateChapter
                                }
                            }
                            WritingIntent.Rewrite -> {
                                require(safeRevision(before, change)) { "请保留正文 Markdown 结构。" }
                                editChapter(saved, change)
                            }
                            WritingIntent.Chapter -> {
                                require(baseline.isBlank() || safeRevision(baseline, change)) { "请保留正文 Markdown 结构。" }
                                decideChapterDraft(saved, change, accepted = true)
                            }
                            else -> Unit
                        }
                    }
                    WritingIntent.Summary -> {
                        check(saved.string("text") == baseline &&
                            saved.optJSONObject("plan")?.string("summary").orEmpty() == before) {
                            "摘要或正文已更新，请重新生成。"
                        }
                        setPlanningSummary(saved, change)
                    }
                    WritingIntent.Close -> Unit
                    else -> Unit
                }
                if (!accept && task == WritingIntent.Chapter) decideChapterDraft(saved, change, accepted = false)
                if (task == WritingIntent.Batch) saved.remove("batchReview")
                saved.records("paragraphs").find { it.string("id") == current.string("paragraphId") }
                    ?.records("drafts")?.find { it.string("id") == current.string("id") }
                    ?.put("state", if (accept) "accepted" else "rejected")
                    ?.apply {
                        if (change != current.string("after")) {
                            put("generatedText", current.string("after"))
                            put("editedText", change)
                            if (accept) put("text", change)
                        }
                    }
                saved.put("nativePreviousReview", archived)
                saved.remove("nativeReview")
            }
            model.clearGeneration(runId)
            if (accept && task == WritingIntent.Summary) intent = WritingIntent.Chapter
        }
    }

    fun confirmSummary() = model.perform {
        val draft = summary
        mutateChapter(book.id, chapter.id) { saved ->
            check(saved.optJSONObject("plan")?.string("summary").orEmpty() == acceptedSummary) {
                "摘要已在其他设备更新，请重新打开编辑。"
            }
            setPlanningSummary(saved, draft)
        }
        intent = WritingIntent.Chapter
    }

    fun resolveInterrupted(id: String, keep: Boolean) = model.perform {
        mutateChapter(book.id, chapter.id) { resolveInterruptedWriting(it, id, keep) }
        model.clearGeneration(runId)
        interruptedId = null
    }

    fun saveAuthorEdits(candidate: JSONObject, edited: JSONObject) = model.perform {
        mutateChapter(book.id, chapter.id) { saved ->
            check(pendingWritingReview(saved)?.toString() == candidate.toString()) { "候选稿已经变更。" }
            saved.put("nativeReview", edited)
            if (edited.string("intent") == WritingIntent.Batch.name) saved.put("batchReview", edited.getJSONArray("items"))
        }
    }

    AlertDialog(
        onDismissRequest = dismiss, title = { Text(intentForReview?.label ?: "AI 写作与审阅") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (run != null && !run.completed) {
                    if (run.stage.isNotBlank()) Text(run.stage, style = WriterTheme.typography.labelLarge)
                    if (run.error != null) Text(run.error, color = WriterTheme.colorScheme.error)
                    if (run.reasoning.isNotBlank()) {
                        TextButton(onClick = { substantial = !substantial }) { Text("模型推理摘要") }
                        if (substantial) Text(run.reasoning)
                    }
                    if (run.text.isNotBlank() && (generating || interruptedDrafts.none { it.string("after") == run.text })) {
                        SelectionContainer { Text(run.text) }
                    }
                }
                interruptedDrafts.asReversed().forEachIndexed { index, draft ->
                    val task = WritingIntent.entries.find { it.name == draft.string("intent") }
                    TextButton(enabled = !generating, onClick = { interruptedId = draft.string("id") }) {
                        Text(if (index == 0) "查看最近中断的输出" else "查看中断的输出 · ${task?.label.orEmpty()}")
                    }
                }
                if (previousReview != null && !generating) {
                    TextButton(onClick = { showPrevious = true }) { Text("查看上一份候选") }
                }
                if (review != null && intentForReview != null) {
                    TextButton(enabled = !generating, onClick = { generate(review) }) { Text("重新生成") }
                    val changed = if (intentForReview == WritingIntent.Batch) review.records("items").any { item ->
                        candidateText(item) != item.string("editedAfter", item.string("after"))
                    } else change != review.string("editedAfter", review.string("after"))
                    if (!generating && changed) {
                        TextButton(onClick = { saveAuthorEdits(review, withAuthorEdits(review)) }) { Text("保存手改稿") }
                    }
                }
                if (review == null) {
                    CurrentPlanNotice(model)
                    ModelPicker(model)
                    TextButton(enabled = !generating, onClick = { menu = true }) { Text(intent.label) }
                    DropdownMenu(menu, { menu = false }) {
                        WritingIntent.entries.forEach { task ->
                            DropdownMenuItem(task.label, onClick = { intent = task; menu = false })
                        }
                    }
                    if (intent == WritingIntent.Summary && !generating) {
                        OutlinedTextField(summary, { summary = it }, Modifier.fillMaxWidth(), minLines = 3,
                            label = { Text("手写或修改摘要") })
                        TextButton(enabled = summary.isNotBlank(), onClick = { confirmSummary() }) { Text("确认手写摘要") }
                    } else if (intent == WritingIntent.Chapter && acceptedSummary.isNotBlank()) {
                        Text("已确认摘要", style = WriterTheme.typography.labelLarge)
                        Text(acceptedSummary, maxLines = 5)
                        TextButton(enabled = !generating, onClick = { intent = WritingIntent.Summary }) { Text("修改摘要") }
                    }
                    if (!generating) OutlinedTextField(instruction, { instruction = it }, Modifier.fillMaxWidth(),
                        label = { Text("作者要求") })
                    if (!generating) TextButton(onClick = {
                        val task = intent
                        model.perform {
                            val (system, messages) = prepareWriting(task, instruction, recordAnchors = false).prompt
                            contextPreview = (listOf(system) + messages.map { it.content }).joinToString("\n\n")
                        }
                    }) { Text("预览发送上下文") }
                } else if (intentForReview == WritingIntent.Profile) {
                    if (profileProposals.isEmpty()) Text("没有可采用的立项字段。")
                    profileProposals.forEach { (key, value) ->
                        CheckOption("${profileFields.getValue(key)}：$value", key in selected) {
                            selected = if (it) selected + key else selected - key
                        }
                    }
                } else if (intentForReview in evidenceIntents) {
                    review.records("items").forEach { item ->
                        val id = item.string("candidateId")
                        Row {
                            Checkbox(id in selected) { selected = if (it) selected + id else selected - id }
                            Column {
                                Text(item.string("title").ifBlank { item.string("role", item.string("kind")) },
                                    style = WriterTheme.typography.titleMedium)
                                Text(item.string("body").ifBlank { item.string("note") })
                                Text(item.string("quote"), color = WriterTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else if (intentForReview == WritingIntent.Batch) {
                    TextButton(onClick = { fullDiff = true }) { Text("全屏比对") }
                    review.records("items").forEach { item ->
                        val id = item.string("id")
                        Row {
                            Checkbox(id in selected, {
                                selected = if (it) selected + id else selected - id
                            })
                            Column {
                                Text(item.string("before"), color = WriterTheme.colorScheme.onSurfaceVariant)
                                if (id in selected) {
                                    OutlinedTextField(candidateText(item), { batchEdits = batchEdits + (id to it) },
                                        Modifier.fillMaxWidth(), enabled = !generating, label = { Text("编辑候选段落") })
                                } else Text(candidateText(item))
                            }
                        }
                    }
                } else {
                    if (review.string("before").isNotBlank()) {
                        TextButton(onClick = { fullDiff = true }) { Text("全屏比对") }
                        Text("原文", style = WriterTheme.typography.labelLarge)
                        Text(review.string("before"), Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()))
                    }
                    OutlinedTextField(change, { change = it }, Modifier.fillMaxWidth(), enabled = !generating, minLines = 5,
                        label = { Text(if (intentForReview == WritingIntent.Summary) "修改章节摘要" else "候选稿") })
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = dismiss) { Text("关闭") }
                if (review != null) TextButton(enabled = !generating, onClick = { decide(false) }) { Text("拒绝") }
            }
        },
        confirmButton = {
            when {
                generating -> TextButton(onClick = { model.stopGeneration(runId) }) { Text("停止") }
                review != null -> TextButton(enabled = if (intentForReview == WritingIntent.Batch ||
                    intentForReview == WritingIntent.Profile || intentForReview in evidenceIntents) selected.isNotEmpty()
                    else change.isNotBlank(), onClick = { decide(true) }) {
                    Text(if (intentForReview == WritingIntent.Summary) "确认摘要" else "采用")
                }
                else -> TextButton(onClick = { generate() }) {
                    Text(when (intent) {
                        WritingIntent.Summary -> "生成摘要"
                        WritingIntent.Chapter -> "按摘要生成初稿"
                        else -> "生成候选"
                    })
                }
            }
        }
    )
    interruptedDrafts.find { it.string("id") == interruptedId }?.let { draft ->
        val canReview = review == null && draft.string("intent") in reviewableInterruptedIntents
        AlertDialog(
            onDismissRequest = { interruptedId = null }, title = { Text("中断的模型输出") },
            text = {
                SelectionContainer {
                    Text(draft.string("after"), Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { interruptedId = null }) { Text("关闭") }
                    TextButton(onClick = { resolveInterrupted(draft.string("id"), keep = false) }) { Text("丢弃") }
                }
            },
            confirmButton = {
                if (canReview) {
                    TextButton(onClick = { resolveInterrupted(draft.string("id"), keep = true) }) { Text("转为候选") }
                }
            }
        )
    }
    if (showPrevious && previousReview != null) {
        val items = previousReview.records("items")
        AlertDialog(
            onDismissRequest = { showPrevious = false }, title = { Text("上一份候选") },
            text = {
                SelectionContainer {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val drafts = if (previousReview.string("intent") == WritingIntent.Batch.name) items
                            else listOf(previousReview)
                        drafts.forEach { draft ->
                            draft.string("before").takeIf(String::isNotBlank)?.let { Text("原文\n$it") }
                            draft.string("after").takeIf(String::isNotBlank)?.let { Text("生成稿\n$it") }
                            draft.string("editedAfter").takeIf(String::isNotBlank)?.let { Text("手改稿\n$it") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPrevious = false }) { Text("关闭") } }
        )
    }
    contextPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { contextPreview = null }, title = { Text("发送上下文预览") },
            text = { Text(preview, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { contextPreview = null }) { Text("关闭") } }
        )
    }
    if (fullDiff && review != null) {
        if (intentForReview == WritingIntent.Batch) {
            val baseline = review.string("baseline")
            val candidates = review.records("items").filter { selected.isEmpty() || it.string("id") in selected }
            val revised = runCatching { applyBatchCandidates(baseline, candidates, ::candidateText) }.getOrNull()
            if (revised != null) ReviewDiff(baseline, revised) { fullDiff = false }
            else AlertDialog(onDismissRequest = { fullDiff = false }, title = { Text("无法预览候选") },
                text = { Text("请检查候选是否为空或改变了原段落的结构。") },
                confirmButton = { TextButton(onClick = { fullDiff = false }) { Text("返回编辑") } })
        } else ReviewDiff(review.string("before"), change) { fullDiff = false }
    }
}

internal fun applyBatchCandidates(
    baseline: String, candidates: List<JSONObject>, candidateText: (JSONObject) -> String
): String {
    var boundary = baseline.length
    return candidates.sortedByDescending { it.optInt("start", -1) }.fold(baseline) { body, item ->
        val start = item.optInt("start", -1)
        val before = item.string("before")
        val after = candidateText(item)
        require(start in 0..boundary && before.isNotEmpty() && before.length <= boundary - start &&
            baseline.startsWith(before, start)) { "候选段落的位置已变更。" }
        require('\n' !in after && '\r' !in after && safeRevision(before, after)) { "请保留段落结构。" }
        boundary = start
        body.replaceRange(start, start + before.length, after)
    }
}

private data class WritingRequest(
    val book: JSONObject, val chapter: JSONObject, val baseline: String, val summary: String,
    val target: String, val spanOffset: Int, val segments: List<JSONObject>,
    val prompt: Pair<String, List<ModelMessage>>
)

private fun writingCandidate(task: WritingIntent, instruction: String, request: WritingRequest, text: String) =
    JSONObject().put("id", newId()).put("intent", task.name).put("baseline", request.baseline)
        .put("before", if (task == WritingIntent.Chapter) request.baseline else request.target)
        .put("start", request.spanOffset).put("after", text).put("instruction", instruction)
        .put("sourceSummary", request.summary).apply {
            if (task == WritingIntent.Paragraph) {
                request.segments.firstOrNull()?.let { put("paragraphId", it.string("id")) }
            }
        }

private val reviewableInterruptedIntents = setOf(
    WritingIntent.Paragraph.name, WritingIntent.Rewrite.name, WritingIntent.Chapter.name, WritingIntent.Summary.name
)

internal fun resolveInterruptedWriting(chapter: JSONObject, id: String, keep: Boolean) {
    val drafts = chapter.records("nativeInterrupted")
    val draft = requireNotNull(drafts.find { it.string("id") == id }) { "中断输出已处理。" }
    if (keep) {
        check(pendingWritingReview(chapter) == null) { "请先处理当前候选稿。" }
        val task = draft.string("intent")
        require(task in reviewableInterruptedIntents) { "请重新生成完整的结构化输出。" }
        val baseline = draft.string("baseline")
        val after = draft.string("after")
        check(chapter.string("text") == baseline) { "正文已更新，请复制需要保留的文字或重新生成。" }
        val candidate = JSONObject(draft.toString())
        when (task) {
            WritingIntent.Paragraph.name -> {
                val original = draft.string("before")
                val start = draft.optInt("start", -1)
                check(original.isNotBlank() && start in 0..baseline.length && baseline.startsWith(original, start)) {
                    "原段落的位置已变更。"
                }
                val rewritten = leadingIndent(original) + after.trim()
                require('\n' !in rewritten && '\r' !in rewritten && safeRevision(original, rewritten)) {
                    "请复制需要保留的文字并在正文编辑。"
                }
                candidate.put("after", rewritten)
            }
            WritingIntent.Rewrite.name ->
                require(safeRevision(baseline, after)) { "中断的输出改变了原正文结构。" }
            WritingIntent.Chapter.name -> {
                check(chapter.optJSONObject("plan")?.string("summary").orEmpty() == draft.string("sourceSummary")) {
                    "章节摘要已更新，请重新生成初稿。"
                }
                require(baseline.isBlank() || safeRevision(baseline, after)) { "中断的输出改变了原正文结构。" }
                rememberChapterDraft(chapter, baseline, after)
            }
            WritingIntent.Summary.name ->
                check(chapter.optJSONObject("plan")?.string("summary").orEmpty() == draft.string("before")) {
                    "章节摘要已更新，请重新生成。"
                }
        }
        chapter.put("nativeReview", candidate)
    }
    val remaining = drafts.filterNot { it.string("id") == id }
    if (remaining.isEmpty()) chapter.remove("nativeInterrupted")
    else chapter.put("nativeInterrupted", JSONArray(remaining))
}

private fun leadingIndent(text: String) = text.takeWhile { it in " \t　" }
internal fun stripJsonFence(text: String): String = text.trim().removePrefix("```json").removePrefix("```")
    .removeSuffix("```").trim()
