package xyz.qingjian.writer

import androidx.compose.material3.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun ChapterEditor(
    model: WriterModel, book: BookRow, modifier: Modifier, focus: Boolean = false,
    planning: Boolean = false, planningStarted: () -> Unit = {},
    requestedPosition: WritingPosition? = null, toggleFocus: () -> Unit = {}
) {
    val chapter by remember(book.id, book.activeId) { model.library.chapter(book.id, book.activeId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val row = chapter?.takeIf { it.id == book.activeId } ?: return
    key(row.id, requestedPosition) {
        ChapterBody(model, book, row, modifier, focus, planning, planningStarted, requestedPosition, toggleFocus)
    }
}

@Composable
private fun ChapterBody(
    model: WriterModel, book: BookRow, chapter: ChapterRow, modifier: Modifier, focus: Boolean,
    planning: Boolean, planningStarted: () -> Unit, requestedPosition: WritingPosition?, toggleFocus: () -> Unit
) {
    val draftKey = remember(chapter.bookId, chapter.id) { DraftKey(chapter.bookId, chapter.id) }
    val drafts by model.drafts.collectAsStateWithLifecycle()
    val draft = drafts[draftKey]
    val text = draft?.text ?: chapter.text
    val position = remember { requestedPosition ?: model.writingPosition(draftKey) }
    var editor by remember {
        mutableStateOf(TextFieldValue(text, TextRange(
            position.selectionStart.coerceIn(0, text.length), position.selectionEnd.coerceIn(0, text.length)
        )))
    }
    var sourceMode by rememberSaveable { mutableStateOf(false) }
    var active by remember { mutableStateOf<ActiveBlock?>(null) }
    var compositionBase by remember { mutableStateOf<TextFieldValue?>(null) }
    DisposableEffect(draftKey) { onDispose { model.setComposing(draftKey, false) } }
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var wasTyping by remember { mutableStateOf(false) }
    val viewport = rememberLazyListState(position.block, position.offset)
    val renderSource = active?.source ?: text
    val projection by produceState("" to emptyList<EditorBlock>(), renderSource) {
        value = withContext(Dispatchers.Default) { renderSource to editorBlocks(renderSource) }
    }
    val (parsedText, blocks) = projection
    LaunchedEffect(viewport) {
        snapshotFlow {
            val selection = compositionBase?.selection ?: editor.selection
            WritingPosition(selection.start, selection.end,
                viewport.firstVisibleItemIndex, viewport.firstVisibleItemScrollOffset)
        }.collect { model.saveWritingPosition(draftKey, it) }
    }
    fun finishEditing() {
        active = null
        sourceMode = false
        editor = compositionBase ?: editor.copy(composition = null)
        compositionBase = null
        model.setComposing(draftKey, false)
        keyboard?.hide()
    }
    LaunchedEffect(keyboardVisible) {
        if (keyboardVisible) wasTyping = true
        else if (wasTyping) {
            wasTyping = false
            if (active != null) finishEditing()
            else if (compositionBase != null) {
                editor = checkNotNull(compositionBase)
                compositionBase = null
                model.setComposing(draftKey, false)
            }
        }
    }
    val undo = remember { ArrayDeque<TextFieldValue>() }
    val redo = remember { ArrayDeque<TextFieldValue>() }
    var lastRecorded by remember { mutableStateOf(0L) }
    var undoSize by remember { mutableStateOf(0) }
    var redoSize by remember { mutableStateOf(0) }
    var find by rememberSaveable { mutableStateOf(false) }
    var formatting by rememberSaveable { mutableStateOf(false) }
    var ai by rememberSaveable { mutableStateOf(false) }
    var initialIntent by rememberSaveable { mutableStateOf<WritingIntent?>(null) }
    LaunchedEffect(planning) {
        if (planning) {
            initialIntent = WritingIntent.Summary
            ai = true
            planningStarted()
        }
    }
    var paragraphMode by rememberSaveable { mutableStateOf(false) }
    var aiRange by remember { mutableStateOf<TextRange?>(null) }
    var pickedParagraphs by remember { mutableStateOf(setOf<Int>()) }
    var closeChapter by rememberSaveable { mutableStateOf(false) }
    var paragraphActions by remember { mutableStateOf<Int?>(null) }
    val settings = remember(book.payload) { org.json.JSONObject(book.payload) }
    val chapterSettings = remember(chapter.payload) { JSONObject(chapter.payload) }
    val review = remember(chapter) { pendingWritingReview(chapterJson(chapter)) }
    val inlineCandidates = remember(review, text) {
        when {
            review == null || review.string("baseline") != text -> emptyList()
            review.string("intent") == WritingIntent.Paragraph.name -> listOf(review)
            review.string("intent") == WritingIntent.Batch.name -> review.records("items")
            else -> emptyList()
        }
    }
    val bodySize = settings.optInt("fontSize", 17).coerceIn(13, 32).sp
    val bodySpacing = settings.optDouble("bodyLineSpacing", 1.75)
        .takeIf(Double::isFinite)?.coerceIn(1.3, 2.5)?.toFloat() ?: 1.75f
    val bodyFont = if (settings.string("bodyFont") == "sans") FontFamily.SansSerif else FontFamily.Serif
    val goal = chapterSettings.optInt("goal").takeIf { it > 0 } ?: bookChapterGoal(settings)
    val light = WriterTheme.colorScheme.surface.luminance() > 0.5f
    val paper = when (settings.string("paper")) {
        "warm", "cream" -> if (light) Color(0xFFFAF5E9) else Color(0xFF282525)
        "blue" -> if (light) Color(0xFFEDF4FC) else Color(0xFF1E2A3A)
        else -> WriterTheme.colorScheme.surface
    }
    fun change(next: TextFieldValue, checkpoint: Boolean = false) {
        if (next.composition != null) {
            if (compositionBase == null) {
                compositionBase = editor.copy(composition = null)
                model.setComposing(draftKey, true)
            }
            editor = next
            return
        }
        val before = compositionBase ?: editor
        if (before.text != next.text) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (checkpoint || compositionBase != null || lastRecorded == 0L || now - lastRecorded > 650) {
                    undo.rememberEdit(before)
            }
            lastRecorded = now
            redo.clear()
            undoSize = undo.size
            redoSize = 0
            model.edit(draftKey, next.text)
        }
        editor = next
        if (compositionBase != null) {
            compositionBase = null
            model.setComposing(draftKey, false)
        }
    }
    fun updateInside(next: TextFieldValue) {
        val binding = active ?: return
        val previousLength = binding.end - binding.start
        val body = editor.text.replaceRange(binding.start, binding.end, next.text)
        if (next.text.length != previousLength) active = binding.copy(end = binding.start + next.text.length)
        change(TextFieldValue(body,
            TextRange(binding.start + next.selection.start, binding.start + next.selection.end),
            next.composition?.let { TextRange(binding.start + it.start, binding.start + it.end) }))
    }
    var details by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(text) {
        if (compositionBase == null && editor.text != text) {
            active = null
            editor = TextFieldValue(text, TextRange(editor.selection.end.coerceAtMost(text.length)))
        }
    }
    LaunchedEffect(chapter.text, draft?.state) { model.acknowledge(draftKey, chapter.text) }

    fun bindEdit(next: TextFieldValue) {
        val binding = active ?: return
        val suffix = editor.text.substring(binding.end)
        val end = next.text.length - suffix.length
        if (end < binding.start || !next.text.startsWith(editor.text.substring(0, binding.start)) ||
            !next.text.endsWith(suffix)) {
            active = null
            sourceMode = true
        } else {
            active = binding.copy(end = end)
        }
    }
    fun applyToolbarEdit(next: TextFieldValue) {
        bindEdit(next)
        change(next, true)
    }
    fun replaceFromHistory(next: TextFieldValue) {
        bindEdit(next)
        editor = next
        model.edit(draftKey, editor.text)
        lastRecorded = 0
        undoSize = undo.size
        redoSize = redo.size
    }
    fun insertPair(open: String, close: String) {
        val start = editor.selection.min
        val end = editor.selection.max
        val decorated = open + editor.text.substring(start, end) + close
        val caret = if (start == end) start + open.length else start + decorated.length
        applyToolbarEdit(TextFieldValue(editor.text.replaceRange(start, end, decorated), TextRange(caret)))
    }

    Surface(modifier, color = paper) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
            val editing = sourceMode || active != null
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = if (keyboardVisible) 0.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(chapter.title, Modifier.weight(1f), maxLines = if (keyboardVisible) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    style = if (keyboardVisible) WriterTheme.typography.titleLarge else WriterTheme.typography.chapterTitle)
                ToolbarTextButton(text = if (editing) "完成" else "源码", onClick = {
                    if (sourceMode || active != null) finishEditing() else sourceMode = true
                })
                Box {
                    IconButton(onClick = { menu = true }, enabled = compositionBase == null) { Icon(Icons.Default.MoreVert, "章节操作") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem("编辑章节信息", onClick = { details = true; menu = false })
                        DropdownMenuItem("章节还原点", onClick = { history = true; menu = false })
                        DropdownMenuItem("查找与替换", onClick = { find = true; menu = false })
                        DropdownMenuItem(if (focus) "退出专注模式" else "专注模式",
                            onClick = { toggleFocus(); menu = false })
                        DropdownMenuItem("AI 写作与审阅", onClick = {
                            aiRange = editor.selection
                            pickedParagraphs = emptySet()
                            finishEditing()
                            ai = true
                            menu = false
                        })
                        DropdownMenuItem("章节规划", onClick = {
                            initialIntent = WritingIntent.Summary
                            finishEditing()
                            ai = true
                            menu = false
                        })
                        DropdownMenuItem("整章源码", onClick = {
                            active = null
                            sourceMode = true
                            menu = false
                        })
                        DropdownMenuItem("逐段操作", onClick = { paragraphMode = true; menu = false })
                        DropdownMenuItem("定稿与下章交接", onClick = { closeChapter = true; menu = false })
                    }
                }
            }
            if (!keyboardVisible || draft?.state == SaveState.Failed) {
                val count by produceState(0, editor.text) {
                    value = withContext(Dispatchers.Default) { manuscriptWordCount(editor.text) }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("$count${if (goal > 0) " / $goal" else ""} 字${if (chapter.complete) " · 定稿" else ""}",
                        style = WriterTheme.typography.bodySmall, color = WriterTheme.colorScheme.onSurfaceVariant)
                    when (draft?.state) {
                        SaveState.Failed -> ToolbarTextButton(text = "重试保存", onClick = { model.retry(draftKey) })
                        SaveState.Saving -> Text("保存中", style = WriterTheme.typography.bodySmall,
                            color = WriterTheme.colorScheme.onSurfaceVariant)
                        else -> Text("已保存", style = WriterTheme.typography.bodySmall,
                            color = WriterTheme.colorScheme.onSurfaceVariant)
                    }
                  }
                  if (goal > 0 && !focus) LinearProgressIndicator(Modifier.fillMaxWidth(),
                      progress = (count.toFloat() / goal).coerceIn(0f, 1f), height = 2.dp)
                }
            }
            val bodyStyle = WriterTheme.typography.bodyLarge.copy(
                color = WriterTheme.colorScheme.onSurface, fontSize = bodySize,
                lineHeight = bodySize * bodySpacing, fontFamily = bodyFont
            )
            Surface(Modifier.weight(1f).fillMaxWidth(), color = paper) {
                when {
                    paragraphMode -> ParagraphInspector(model, book, chapter, text,
                        close = { paragraphMode = false },
                        changed = { change(TextFieldValue(it), true) }, ask = { range, indices ->
                            aiRange = range
                            pickedParagraphs = indices
                            ai = true
                        })
                    sourceMode -> WritingField(
                        editor, { change(it) }, bodyStyle,
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
                    )
                    else -> LazyColumn(
                        Modifier.fillMaxSize(), state = viewport,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        itemsIndexed(blocks, key = { index, _ -> index }) { index, block ->
                            val binding = active
                            if (binding != null && binding.index == index) {
                                val local = TextFieldValue(
                                    editor.text.substring(binding.start, binding.end),
                                    TextRange((editor.selection.start - binding.start).coerceIn(0, binding.end - binding.start),
                                        (editor.selection.end - binding.start).coerceIn(0, binding.end - binding.start)),
                                    editor.composition?.let {
                                        TextRange((it.start - binding.start).coerceAtLeast(0),
                                            (it.end - binding.start).coerceAtMost(binding.end - binding.start))
                                    }
                                )
                                WritingField(local, ::updateInside, bodyStyle, Modifier.fillMaxWidth())
                            } else {
                                Column(Modifier.fillMaxWidth()) {
                                    val highlighted = requestedPosition?.block == index && editor.selection.start == position.selectionStart
                                    val blockColor = if (highlighted) WriterTheme.colorScheme.primaryContainer else Color.Transparent
                                    Box(Modifier.fillMaxWidth().background(blockColor, WriterTheme.shapes.small)
                                        .clickable(enabled = parsedText == renderSource) {
                                        if (active != null) {
                                            finishEditing()
                                            return@clickable
                                        }
                                        val selected = editor.selection.takeIf {
                                            it.min >= block.start && it.max <= block.end
                                        } ?: TextRange(block.end)
                                        editor = editor.copy(selection = selected, composition = null)
                                        active = ActiveBlock(index, block.start, block.end, editor.text)
                                    }.padding(vertical = 2.dp)) {
                                        val node = block.node
                                        if (node == null) Text("点这里开始写作",
                                            color = WriterTheme.colorScheme.onSurfaceVariant, style = bodyStyle)
                                        else ManuscriptBlock(node, bodyStyle, settings.string("dividerStyle", "ornament"))
                                    }
                                    inlineCandidates.firstOrNull { it.optInt("start", -1) in block.start until block.end }
                                        ?.let { candidate ->
                                            Surface(Modifier.fillMaxWidth(), shape = WriterTheme.shapes.medium,
                                                color = WriterTheme.colorScheme.surfaceContainer) {
                                                Column(Modifier.padding(10.dp)) {
                                                    Text(candidate.string("editedAfter", candidate.string("after")),
                                                        maxLines = 4, style = WriterTheme.typography.bodyMedium)
                                                    TextButton(onClick = {
                                                        aiRange = TextRange(candidate.optInt("start"))
                                                        pickedParagraphs = emptySet()
                                                        ai = true
                                                    }) { Text("审阅候选") }
                                                }
                                            }
                                        }
                                }
                            }
                        }
                    }
                }
            }
            if (editing && !paragraphMode && compositionBase == null) {
                if (formatting) FormatBar(editor, ::applyToolbarEdit)
                Surface(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = WriterTheme.colorScheme.surfaceContainer, shape = WriterTheme.shapes.large) {
                  Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    TextButton(enabled = undoSize > 0, onClick = {
                        redo.rememberEdit(editor)
                        replaceFromHistory(undo.removeLast())
                    }) { Text("撤销") }
                    TextButton(enabled = redoSize > 0, onClick = {
                        undo.rememberEdit(editor)
                        replaceFromHistory(redo.removeLast())
                    }) { Text("重做") }
                    TextButton(onClick = { insertPair("「", "」") }) { Text("「」") }
                    TextButton(onClick = { insertPair("（", "）") }) { Text("（）") }
                    TextButton(onClick = {
                        aiRange = editor.selection
                        pickedParagraphs = emptySet()
                        finishEditing()
                        ai = true
                    }) { Text("AI") }
                    TextButton(onClick = {
                        val index = paragraphSpans(editor.text).indexOfFirst {
                            editor.selection.min in it.start..it.end
                        }
                        if (index < 0) model.notice("请先将光标移到正文段落。")
                        else {
                            paragraphActions = index
                            finishEditing()
                        }
                    }) { Text("段落") }
                    TextButton(onClick = { formatting = !formatting }) { Text("格式") }
                  }
                }
            }
        }
      }
    }
    if (details) ChapterDetails(model, chapter) { details = false }
    if (history) ChapterHistoryDialog(model, chapter) { history = false }
    if (find) FindDialog(editor, { sourceMode = true; active = null; change(it, true) }) { find = false }
    if (ai) WritingAiDialog(model, book, chapter, aiRange ?: editor.selection, pickedParagraphs, initialIntent) {
        ai = false
        initialIntent = null
    }
    if (closeChapter) ManualHandoff(model, chapter) { closeChapter = false }
    paragraphActions?.let { index ->
        InlineParagraphActions(model, book, chapter, editor.text, index) { paragraphActions = null }
    }
}

@Composable
private fun InlineParagraphActions(
    model: WriterModel, book: BookRow, chapter: ChapterRow, body: String, index: Int, dismiss: () -> Unit
) {
    val span = remember(body, index) { paragraphSpans(body).getOrNull(index) } ?: return
    val row = remember(chapter.payload, index) { JSONObject(chapter.payload).records("paragraphs").getOrNull(index) }
    val related = remember(book.payload, row) {
        val research = JSONObject(book.payload).obj("research")
        fun matches(point: JSONObject?) = point != null && row != null &&
            point.string("chapterId") == chapter.id && point.string("paragraphId") == row.string("id")
        research.records("annotations").filter { matches(it.optJSONObject("anchor")) }.map { it.string("text") } +
            research.records("foreshadows").filter { item ->
                item.records("anchors").any { matches(it.optJSONObject("anchor")) }
            }.map { it.string("title") }
    }
    var action by rememberSaveable { mutableStateOf("") }
    fun persist(type: String, value: String) = model.perform {
        var point: JSONObject? = null
        mutateChapter(book.id, chapter.id, bookEdit = if (type in setOf("批注", "伏笔")) { document ->
            val anchor = requireNotNull(point)
            val research = document.obj("research")
            if (type == "批注") research.array("annotations").put(JSONObject()
                .put("id", newId()).put("kind", "批注").put("text", value).put("anchor", anchor))
            else research.array("foreshadows").put(JSONObject().put("id", newId()).put("title", value.take(100))
                .put("description", value).put("status", "open").put("anchors", org.json.JSONArray()
                    .put(JSONObject().put("id", newId()).put("role", "铺垫").put("quote", span.text).put("anchor", anchor))))
        } else null) { source ->
            check(source.string("text") == body) { "正文已更新，请重新选择段落。" }
            editChapter(source, body, ensureParagraphs = true)
            val paragraph = source.records("paragraphs")[index]
            point = JSONObject().put("chapterId", chapter.id).put("paragraphId", paragraph.string("id"))
            when (type) {
                "提示" -> paragraph.put("instruction", value)
                "反馈" -> paragraph.put("vote", if (paragraph.string("vote") == value) "" else value)
            }
        }
    }
    if (action.isNotEmpty()) {
        ParagraphDialog(action, if (action == "提示") row?.string("instruction").orEmpty() else "", dismiss) { value ->
            if (action == "提示" || value.isNotBlank()) persist(action, value)
            dismiss()
        }
    } else AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("段落", style = WriterTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(span.text, maxLines = 4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf("提示", "批注", "伏笔").forEach { name ->
                        TextButton(onClick = { action = name }) { Text(name) }
                    }
                    TextButton(onClick = { persist("反馈", "keep"); dismiss() }) { Text("赞") }
                    TextButton(onClick = { persist("反馈", "revise"); dismiss() }) { Text("踩") }
                }
                related.forEach { Text(it, style = WriterTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("完成") } }
    )
}

@Composable
private fun ChapterDetails(model: WriterModel, chapter: ChapterRow, dismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(chapter.title) }
    var complete by rememberSaveable { mutableStateOf(chapter.complete) }
    var goal by rememberSaveable {
        mutableStateOf(JSONObject(chapter.payload).optInt("goal").takeIf { it > 0 }?.toString().orEmpty())
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("章节信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("章名") })
                OutlinedTextField(goal, { goal = it.filter(Char::isDigit).take(7) },
                    singleLine = true, label = { Text("本章目标字数") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(complete, { complete = it })
                    Text("标记为定稿")
                }
                ExportButton(ExportFormat.Chapter, safeFileName(chapter.title)) { uri ->
                    model.writeExport(uri, chapter.bookId, ExportFormat.Chapter, chapter.id)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                model.perform {
                    updateChapter(chapter.bookId, chapter.id, title.trim(), complete,
                        goal.toIntOrNull()?.takeIf { it > 0 }?.coerceAtMost(1_000_000))
                    dismiss()
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }
    )
}

@Composable
internal fun ChapterHistoryDialog(model: WriterModel, chapter: ChapterRow, dismiss: () -> Unit) {
    val versions by remember(chapter.bookId, chapter.id) { model.library.versions(chapter.bookId, chapter.id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var comparison by remember { mutableStateOf<Pair<String, String>?>(null) }
    val restore = versions.firstOrNull { it.id == selected }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (restore == null) "章节还原点" else "还原到此版本？") },
        text = {
            when {
                restore != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前正文会先保存为一个新还原点。")
                    Text(restore.text.take(220).ifBlank { "空白章节" }, maxLines = 6)
                    TextButton(onClick = {
                        model.perform {
                            comparison = chapterText(chapter.bookId, chapter.id) to restore.text
                        }
                    }) { Text("与当前正文比较") }
                }
                versions.isEmpty() -> Text("可以保存当前正文，之后从这里恢复。")
                else -> LazyColumn {
                    items(versions, key = ChapterVersion::id) { version ->
                        TextButton(onClick = { selected = version.id }) {
                            Text(DateFormat.getDateTimeInstance().format(Date(version.createdAt)))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (restore == null) model.perform { snapshot(chapter.bookId, chapter.id); model.notice("已创建还原点") }
                else model.restoreChapter(chapter.bookId, chapter.id, restore.id, dismiss)
            }) { Text(if (restore == null) "创建还原点" else "还原正文") }
        },
        dismissButton = { TextButton(onClick = { if (restore == null) dismiss() else selected = null }) { Text("取消") } }
    )
    comparison?.let { (current, saved) ->
        ReviewDiff(current, saved, "比较章节还原点", "当前正文", "保存版本", onlyChanges = true) {
            comparison = null
        }
    }
}

private fun ArrayDeque<TextFieldValue>.rememberEdit(value: TextFieldValue) {
    var characters = sumOf { it.text.length.toLong() } + value.text.length
    while (isNotEmpty() && (size >= 80 || characters > 4_000_000L)) {
        characters -= removeFirst().text.length
    }
    addLast(value.copy(composition = null))
}

private data class ActiveBlock(val index: Int, val start: Int, val end: Int, val source: String)

@Composable
private fun WritingField(
    value: TextFieldValue, change: (TextFieldValue) -> Unit,
    style: androidx.compose.ui.text.TextStyle, modifier: Modifier
) {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = value, onValueChange = change,
        modifier = modifier.focusRequester(requester).semantics { contentDescription = "章节正文" },
        textStyle = style,
        cursorBrush = SolidColor(WriterTheme.colorScheme.primary),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth()) {
                if (value.text.isEmpty()) Text("从这里开始写作", color = WriterTheme.colorScheme.onSurfaceVariant)
                inner()
            }
        }
    )
    LaunchedEffect(requester) {
        requester.requestFocus()
        keyboard?.show()
    }
}
