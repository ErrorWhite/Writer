package xyz.qingjian.writer

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject

private enum class BookPage(val label: String, val icon: ImageVector) {
    Edit("写作", Icons.Default.Edit),
    Chapters("目录", Icons.Default.List),
    Desk("工作台", Icons.Default.Settings)
}

@Composable
internal fun BookScreen(model: WriterModel, id: String, back: () -> Unit) {
    val book by remember(id) { model.library.book(id) }.collectAsStateWithLifecycle(initialValue = null)
    val current = book ?: return
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val pages = rememberSaveableStateHolder()
    var page by rememberSaveable { mutableStateOf(BookPage.Edit) }
    val leave: () -> Unit = {
        if (page != BookPage.Edit) page = BookPage.Edit else model.perform { back() }
    }
    BackHandler(onBack = leave)
    BoxWithConstraints {
        val wide = maxWidth >= 840.dp
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { BackBar(current.title, leave) { BookMenu(model, current, back) } },
            bottomBar = {
                if (!keyboardOpen) NavigationBar {
                    val tabs = if (wide) listOf(BookPage.Edit, BookPage.Desk) else BookPage.entries
                    tabs.forEach { item ->
                        NavigationBarItem(
                            selected = page == item || (wide && item == BookPage.Edit && page == BookPage.Chapters),
                            onClick = { page = item },
                            icon = { Icon(item.icon, null) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        ) { padding ->
            val contentModifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            if (page == BookPage.Desk) {
                pages.SaveableStateProvider("desk") { BookDesk(model, current, contentModifier) }
            } else if (wide) {
                Row(contentModifier) {
                    Surface(Modifier.width(300.dp), color = MaterialTheme.colorScheme.surface) {
                        DirectoryScreen(model, current, Modifier.fillMaxSize()) { page = BookPage.Edit }
                    }
                    pages.SaveableStateProvider("edit") {
                        ChapterEditor(model, current, Modifier.weight(1f).fillMaxSize())
                    }
                }
            } else if (page == BookPage.Chapters) {
                pages.SaveableStateProvider("chapters") {
                    DirectoryScreen(model, current, contentModifier) { page = BookPage.Edit }
                }
            } else {
                pages.SaveableStateProvider("edit") { ChapterEditor(model, current, contentModifier) }
            }
        }
    }
}

@Composable
private fun DirectoryScreen(model: WriterModel, book: BookRow, modifier: Modifier, opened: () -> Unit) {
    val volumes by remember(book.id) { model.library.volumes(book.id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val chapters by remember(book.id) { model.library.directory(book.id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val byVolume = remember(chapters) { chapters.groupBy(ChapterEntry::volumeId) }
    var creatingVolume by rememberSaveable { mutableStateOf(false) }
    var newChapterVolume by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(modifier, contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("目录", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                OutlinedButton(onClick = { creatingVolume = true }) { Text("新建卷") }
            }
        }
        volumes.forEach { volume ->
            item(key = "volume:${volume.id}") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(volume.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { newChapterVolume = volume.id }) { Text("新建章") }
                }
            }
            items(byVolume[volume.id].orEmpty(), key = { "chapter:${it.id}" }) { chapter ->
                val selected = chapter.id == book.activeId
                Surface(
                    onClick = { model.perform { select(book.id, chapter.id); opened() } },
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(chapter.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (chapter.complete) Text("定稿", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    if (creatingVolume) NameDialog("新建卷", "卷名", dismiss = { creatingVolume = false }) { name ->
        model.perform { addVolume(book.id, name) }
    }
    newChapterVolume?.let { volume ->
        NameDialog("新建章节", "章名", dismiss = { newChapterVolume = null }) { name ->
            model.perform { addChapter(book.id, volume, name); opened() }
        }
    }
}

@Composable
private fun BookMenu(model: WriterModel, book: BookRow, back: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var rename by rememberSaveable { mutableStateOf(false) }
    var remove by rememberSaveable { mutableStateOf(false) }
    val cover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) model.importCover(book.id, it)
    }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "作品管理") }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("更改书名") }, onClick = { expanded = false; rename = true })
            DropdownMenuItem(text = { Text("更换封面") }, onClick = {
                expanded = false
                cover.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
            })
            if (book.cover.isNotEmpty()) DropdownMenuItem(text = { Text("清除封面") }, onClick = {
                expanded = false
                model.perform { setCover(book.id, "") }
            })
            DropdownMenuItem(text = { Text("删除作品") }, onClick = { expanded = false; remove = true })
        }
    }
    if (rename) NameDialog("更改书名", "书名", book.title, dismiss = { rename = false }) { title ->
        model.perform { this.rename(book.id, title) }
    }
    if (remove) AlertDialog(
        onDismissRequest = { remove = false },
        title = { Text("删除《${book.title}》？") },
        text = { Text("这会删除本机书稿、资料和章节还原点。") },
        confirmButton = {
            TextButton(onClick = {
                remove = false
                model.deleteBook(book.id, back)
            }) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { remove = false }) { Text("取消") } }
    )
}

@Composable
private fun BookDesk(model: WriterModel, book: BookRow, modifier: Modifier) {
    val details = remember(book.payload) { JSONObject(book.payload) }
    var field by rememberSaveable(book.id) { mutableStateOf("outline") }
    val label = if (field == "outline") "大纲" else "设定"
    val existing = details.string(field)
    var value by remember(book.id, field, existing) { mutableStateOf(existing) }
    var changedField by remember { mutableStateOf<String?>(null) }
    val bookFileName = remember(book.title) { safeFileName(book.title) }
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("工作台", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("outline" to "大纲", "notes" to "设定").forEach { (key, name) ->
                    FilterChip(selected = field == key, onClick = {
                        if (field != key) {
                            if (value == existing) field = key else changedField = key
                        }
                    }, label = { Text(name) })
                }
            }
            OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), minLines = 6, label = { Text(label) })
            Button(enabled = value != existing, onClick = { model.perform { updateBookText(book.id, field, value) } }) {
                Text("保存$label")
            }
            Text("导出", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
            ExportFormat.entries.filter { it != ExportFormat.Chapter }.forEach { format ->
                ExportButton(format, bookFileName, onDestination = { uri -> model.writeExport(uri, book.id, format, null) })
            }
        }
    }
    changedField?.let { destination ->
        AlertDialog(
            onDismissRequest = { changedField = null },
            title = { Text("保存$label？") },
            text = { Text("当前内容已经修改。") },
            confirmButton = {
                TextButton(onClick = {
                    model.perform {
                        updateBookText(book.id, field, value)
                        field = destination
                        changedField = null
                    }
                }) { Text("保存并切换") }
            },
            dismissButton = { TextButton(onClick = { changedField = null }) { Text("取消") } }
        )
    }
}

@Composable
internal fun ExportButton(format: ExportFormat, name: String, onDestination: (android.net.Uri) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mime)) {
        if (it != null) onDestination(it)
    }
    OutlinedButton(onClick = { launcher.launch("$name.${format.extension}") }) { Text(format.label) }
}

internal fun safeFileName(title: String): String =
    title.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]"), "_").trim().ifBlank { "书稿" }.take(120)
