package xyz.qingjian.writer

import androidx.compose.foundation.background
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text as MarkdownText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

@Composable
internal fun ChapterEditor(model: WriterModel, book: BookRow, modifier: Modifier) {
    val chapter by remember(book.id, book.activeId) { model.library.chapter(book.id, book.activeId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val row = chapter?.takeIf { it.id == book.activeId } ?: return
    key(row.id) { ChapterBody(model, row, modifier) }
}

@Composable
private fun ChapterBody(model: WriterModel, chapter: ChapterRow, modifier: Modifier) {
    val draftKey = remember(chapter.bookId, chapter.id) { DraftKey(chapter.bookId, chapter.id) }
    val drafts by model.drafts.collectAsStateWithLifecycle()
    val draft = drafts[draftKey]
    val text = draft?.text ?: chapter.text
    var editor by remember { mutableStateOf(TextFieldValue(text)) }
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
                Text("$count 字${if (chapter.complete) " · 定稿" else ""}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (draft?.state == SaveState.Failed) {
                    TextButton(onClick = { model.retry(draftKey) }) { Text("保存失败，重试") }
                } else {
                    Text(if (draft?.state == SaveState.Saving) "正在保存…" else "已保存到本机",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(Modifier.weight(1f).fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                if (preview) {
                    MarkdownPreview(text)
                } else {
                    BasicTextField(
                        value = editor,
                        onValueChange = { next ->
                            val changed = next.text != editor.text
                            editor = next
                            if (changed) model.edit(draftKey, next.text)
                        },
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, lineHeight = 31.sp
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

private val markdownParser = Parser.builder().build()
private data class PreviewBlock(val node: Node, val text: String)

@Composable
private fun MarkdownPreview(text: String) {
    val blocks by produceState(emptyList<PreviewBlock>(), text) {
        value = withContext(Dispatchers.Default) {
            val parsed = markdownParser.parse(manuscriptMarkdown(text))
            generateSequence(parsed.firstChild) { it.next }.map { node ->
                PreviewBlock(node, markdownText(node))
            }.toList()
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (text.isBlank()) item { Text("本章尚无正文") }
        items(blocks) { block ->
            when (block.node) {
                is ThematicBreak -> HorizontalDivider(Modifier.fillMaxWidth(.28f))
                is Heading -> Text(block.text, style = MaterialTheme.typography.titleLarge)
                is FencedCodeBlock, is IndentedCodeBlock -> Text(
                    block.text,
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
                    fontFamily = FontFamily.Monospace
                )
                is BlockQuote -> Text(block.text, Modifier.padding(start = 14.dp), fontStyle = FontStyle.Italic)
                else -> Text(block.text, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 29.sp))
            }
        }
    }
}

private fun markdownText(node: Node): String {
    val children = generateSequence(node.firstChild) { it.next }
    return when (node) {
        is MarkdownText -> node.literal
        is Code -> node.literal
        is FencedCodeBlock -> node.literal.trimEnd()
        is IndentedCodeBlock -> node.literal.trimEnd()
        is SoftLineBreak, is HardLineBreak -> "\n"
        is OrderedList -> children.mapIndexed { index, item ->
            "${(node.markerStartNumber ?: 1) + index}. ${markdownText(item)}"
        }.joinToString("\n")
        is org.commonmark.node.BulletList -> children.joinToString("\n") { "• ${markdownText(it)}" }
        is BlockQuote, is ListItem -> children.joinToString("\n", transform = ::markdownText)
        else -> children.joinToString("", transform = ::markdownText)
    }
}
