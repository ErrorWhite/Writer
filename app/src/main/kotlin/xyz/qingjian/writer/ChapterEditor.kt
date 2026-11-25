package xyz.qingjian.writer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ChapterEditor(
    model: WriterModel, book: BookRow, modifier: Modifier, focus: Boolean = false, toggleFocus: () -> Unit = {}
) {
    val chapter by remember(book.id, book.activeId) { model.library.chapter(book.id, book.activeId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val row = chapter?.takeIf { it.id == book.activeId } ?: return
    key(row.id) { ChapterBody(model, book, row, modifier, focus, toggleFocus) }
}

@Composable
private fun ChapterBody(
    model: WriterModel, book: BookRow, chapter: ChapterRow, modifier: Modifier, focus: Boolean, toggleFocus: () -> Unit
) {
    val draftKey = remember(chapter.bookId, chapter.id) { DraftKey(chapter.bookId, chapter.id) }
    val drafts by model.drafts.collectAsStateWithLifecycle()
    val draft = drafts[draftKey]
    val text = draft?.text ?: chapter.text
    val position = remember { model.writingPosition(draftKey) }
    var editor by remember {
        mutableStateOf(TextFieldValue(text, TextRange(
            position.selectionStart.coerceIn(0, text.length), position.selectionEnd.coerceIn(0, text.length)
        )))
    }
    var sourceMode by rememberSaveable { mutableStateOf(false) }
    var active by remember { mutableStateOf<ActiveBlock?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val viewport = rememberLazyListState(position.block, position.offset)
    val renderSource = active?.source ?: text
    val blocks by produceState(emptyList<EditorBlock>(), renderSource) {
        value = withContext(Dispatchers.Default) { editorBlocks(renderSource) }
    }
    LaunchedEffect(viewport) {
        snapshotFlow {
            WritingPosition(editor.selection.start, editor.selection.end,
                viewport.firstVisibleItemIndex, viewport.firstVisibleItemScrollOffset)
        }.collect { model.saveWritingPosition(draftKey, it) }
    }
    fun finishEditing() {
        active = null
        sourceMode = false
        editor = editor.copy(composition = null)
        keyboard?.hide()
    }
    val undo = remember { ArrayDeque<TextFieldValue>() }
    val redo = remember { ArrayDeque<TextFieldValue>() }
    var lastRecorded by remember { mutableStateOf(0L) }
    var undoSize by remember { mutableStateOf(0) }
    var redoSize by remember { mutableStateOf(0) }
    var find by rememberSaveable { mutableStateOf(false) }
    var formatting by rememberSaveable { mutableStateOf(false) }
    var ai by rememberSaveable { mutableStateOf(false) }
    var paragraphMode by rememberSaveable { mutableStateOf(false) }
    var aiRange by remember { mutableStateOf<TextRange?>(null) }
    var pickedParagraphs by remember { mutableStateOf(setOf<Int>()) }
    var closeChapter by rememberSaveable { mutableStateOf(false) }
    val settings = remember(book.payload) { org.json.JSONObject(book.payload) }
    val bodySize = settings.optInt("fontSize", 17).coerceIn(13, 32).sp
    val goal = settings.optInt("goal")
    val paper = if (settings.string("paper") == "warm" && WriterTheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFFFFF7E8)
    } else WriterTheme.colorScheme.surface
    fun change(next: TextFieldValue, checkpoint: Boolean = false) {
        if (next.text != editor.text) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (checkpoint || lastRecorded == 0L || now - lastRecorded > 650) {
                if (undo.size == 80) undo.removeFirst()
                undo.addLast(editor.copy(composition = null))
            }
            lastRecorded = now
            redo.clear()
            undoSize = undo.size
            redoSize = 0
            model.edit(draftKey, next.text)
        }
        editor = next
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
        if (editor.text != text) {
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

    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 800.dp).fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(chapter.title, Modifier.weight(1f), style = WriterTheme.typography.headlineSmall)
                TextButton(onClick = {
                    if (sourceMode || active != null) finishEditing() else sourceMode = true
                }) { Text(if (sourceMode || active != null) "完成" else "源码") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "章节操作") }
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
                        DropdownMenuItem("逐段操作", onClick = { paragraphMode = true; menu = false })
                        DropdownMenuItem("定稿与下章交接", onClick = { closeChapter = true; menu = false })
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val count by produceState(0, editor.text) {
                    value = withContext(Dispatchers.Default) { manuscriptWordCount(editor.text) }
                }
                Text("$count${if (goal > 0) " / $goal" else ""} 字${if (chapter.complete) " · 定稿" else ""}",
                    color = WriterTheme.colorScheme.onSurfaceVariant, style = WriterTheme.typography.bodySmall)
                if (draft?.state == SaveState.Failed) {
                    TextButton(onClick = { model.retry(draftKey) }) { Text("保存失败，重试") }
                } else {
                    Text(if (draft?.state == SaveState.Saving) "正在保存…" else "已保存到本机",
                        style = WriterTheme.typography.bodySmall, color = WriterTheme.colorScheme.onSurfaceVariant)
                }
            }
            val editing = sourceMode || active != null
            val bodyStyle = WriterTheme.typography.bodyLarge.copy(
                color = WriterTheme.colorScheme.onSurface, fontSize = bodySize,
                lineHeight = bodySize * 1.75f, fontFamily = FontFamily.Serif
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
                        verticalArrangement = Arrangement.spacedBy(16.dp)
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
                                Box(Modifier.fillMaxWidth().clickable {
                                    val previous = active
                                    if (previous != null) {
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
                                    else ManuscriptBlock(node, bodyStyle)
                                }
                            }
                        }
                    }
                }
            }
            if (editing && !paragraphMode) {
                if (formatting) FormatBar(editor, ::applyToolbarEdit)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(enabled = undoSize > 0, onClick = {
                        redo.addLast(editor.copy(composition = null))
                        replaceFromHistory(undo.removeLast())
                    }) { Text("撤销") }
                    TextButton(enabled = redoSize > 0, onClick = {
                        undo.addLast(editor.copy(composition = null))
                        replaceFromHistory(redo.removeLast())
                    }) { Text("重做") }
                    TextButton(onClick = { insertPair("「", "」") }) { Text("「」") }
                    TextButton(onClick = { insertPair("（", "）") }) { Text("（）") }
                    TextButton(onClick = { formatting = !formatting }) { Text("格式") }
                    TextButton(onClick = {
                        aiRange = editor.selection
                        pickedParagraphs = emptySet()
                        finishEditing()
                        ai = true
                    }) { Text("AI") }
                }
            }
        }
    }
    if (details) ChapterDetails(model, chapter) { details = false }
    if (history) HistoryDialog(model, chapter) { history = false }
    if (find) FindDialog(editor, { sourceMode = true; active = null; change(it, true) }) { find = false }
    if (ai) WritingAiDialog(model, book, chapter, aiRange ?: editor.selection, pickedParagraphs) { ai = false }
    if (closeChapter) ManualHandoff(model, chapter) { closeChapter = false }
}

@Composable
private fun ChapterDetails(model: WriterModel, chapter: ChapterRow, dismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(chapter.title) }
    var complete by rememberSaveable { mutableStateOf(chapter.complete) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("章节信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("章名") })
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
                model.perform { updateChapter(chapter.bookId, chapter.id, title.trim(), complete); dismiss() }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }
    )
}

@Composable
private fun HistoryDialog(model: WriterModel, chapter: ChapterRow, dismiss: () -> Unit) {
    val versions by remember(chapter.bookId, chapter.id) { model.library.versions(chapter.bookId, chapter.id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    val restore = versions.firstOrNull { it.id == selected }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (restore == null) "章节还原点" else "还原到此版本？") },
        text = {
            when {
                restore != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前正文会先保存为一个新还原点。")
                    Text(restore.text.take(220).ifBlank { "空白章节" }, maxLines = 6)
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
}

private val words = Regex("[\\p{IsHan}]|[\\p{L}\\p{N}&&[^\\p{IsHan}]]+")
private fun manuscriptWordCount(text: String) = words.findAll(text).count()

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
        modifier = modifier.focusRequester(requester),
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
