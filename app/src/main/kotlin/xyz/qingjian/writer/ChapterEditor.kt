package xyz.qingjian.writer

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
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
import org.commonmark.node.Node
import org.commonmark.parser.Parser

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
    var editor by remember { mutableStateOf(TextFieldValue(text)) }
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
    val paper = if (settings.string("paper") == "warm" && MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFFFFF7E8)
    } else MaterialTheme.colorScheme.surface
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
    var preview by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(text) {
        if (editor.text != text) {
            editor = TextFieldValue(text, TextRange(editor.selection.end.coerceAtMost(text.length)))
        }
    }
    LaunchedEffect(chapter.text, draft?.state) { model.acknowledge(draftKey, chapter.text) }

    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 800.dp).fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(chapter.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { preview = !preview }) { Text(if (preview) "编辑" else "预览") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "章节操作") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("编辑章节信息") }, onClick = { details = true; menu = false })
                        DropdownMenuItem(text = { Text("章节还原点") }, onClick = { history = true; menu = false })
                        DropdownMenuItem(text = { Text("查找与替换") }, onClick = { find = true; menu = false })
                        DropdownMenuItem(text = { Text(if (focus) "退出专注模式" else "专注模式") },
                            onClick = { toggleFocus(); menu = false })
                        DropdownMenuItem(text = { Text("AI 写作与审阅") }, onClick = {
                            aiRange = null; pickedParagraphs = emptySet(); ai = true; menu = false
                        })
                        DropdownMenuItem(text = { Text("逐段操作") }, onClick = { paragraphMode = true; menu = false })
                        DropdownMenuItem(text = { Text("定稿与下章交接") }, onClick = { closeChapter = true; menu = false })
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (draft?.state == SaveState.Failed) {
                    TextButton(onClick = { model.retry(draftKey) }) { Text("保存失败，重试") }
                } else {
                    Text(if (draft?.state == SaveState.Saving) "正在保存…" else "已保存到本机",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!preview) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(enabled = undoSize > 0, onClick = {
                    redo.addLast(editor.copy(composition = null))
                    editor = undo.removeLast()
                    model.edit(draftKey, editor.text)
                    lastRecorded = 0
                    redoSize = redo.size
                    undoSize = undo.size
                }) { Text("撤销") }
                TextButton(enabled = redoSize > 0, onClick = {
                    undo.addLast(editor.copy(composition = null))
                    editor = redo.removeLast()
                    model.edit(draftKey, editor.text)
                    lastRecorded = 0
                    redoSize = redo.size
                    undoSize = undo.size
                }) { Text("重做") }
                TextButton(onClick = { formatting = !formatting }) { Text(if (formatting) "收起格式" else "格式") }
            }
            if (formatting && !preview) FormatBar(editor) { change(it, true) }
            Surface(Modifier.weight(1f).fillMaxWidth(), color = paper, shape = MaterialTheme.shapes.large) {
                if (paragraphMode) {
                    ParagraphInspector(model, book, chapter, text, close = { paragraphMode = false },
                        changed = { change(TextFieldValue(it), true) }, ask = { range, indices ->
                            aiRange = range
                            pickedParagraphs = indices
                            ai = true
                        })
                } else if (preview) {
                    MarkdownPreview(text)
                } else {
                    BasicTextField(
                        value = editor,
                        onValueChange = { next -> change(next) },
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface, fontSize = bodySize,
                            lineHeight = bodySize * 1.75f, fontFamily = FontFamily.Serif
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth()) {
                                if (editor.text.isEmpty()) Text("从这里开始写作", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                inner()
                            }
                        }
                    )
                }
            }
        }
    }
    if (details) ChapterDetails(model, chapter) { details = false }
    if (history) HistoryDialog(model, chapter) { history = false }
    if (find) FindDialog(editor, { change(it, true) }) { find = false }
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

private val markdownParser = Parser.builder().extensions(writingExtensions).build()

@Composable
private fun MarkdownPreview(text: String) {
    val blocks by produceState(emptyList<Node>(), text) {
        value = withContext(Dispatchers.Default) {
            val parsed = markdownParser.parse(manuscriptMarkdown(text))
            generateSequence(parsed.firstChild) { it.next }.toList()
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (text.isBlank()) item { Text("本章尚无正文") }
        items(blocks) { node -> ManuscriptBlock(node) }
    }
}
