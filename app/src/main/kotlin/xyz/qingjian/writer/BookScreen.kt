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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import io.github.suqi8.coui.kmp.basic.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import io.github.suqi8.coui.kmp.basic.NavigationBar
import io.github.suqi8.coui.kmp.basic.NavigationBarItem
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
    Desk("工作台", Icons.Default.Settings),
    Roleplay("扮演", Icons.Default.Person)
}

@Composable
internal fun BookScreen(model: WriterModel, id: String, back: () -> Unit) {
    val book by remember(id) { model.library.book(id) }.collectAsStateWithLifecycle(initialValue = null)
    val current = book ?: return
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val pages = rememberSaveableStateHolder()
    var page by rememberSaveable { mutableStateOf(BookPage.Edit) }
    var focus by rememberSaveable { mutableStateOf(false) }
    val leave: () -> Unit = {
        when {
            focus -> focus = false
            page != BookPage.Edit -> page = BookPage.Edit
            else -> model.perform { back() }
        }
    }
    BackHandler(onBack = leave)
    BoxWithConstraints {
        val wide = maxWidth >= 840.dp
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { if (!focus) BackBar(current.title, leave) { BookMenu(model, current, back) } },
            bottomBar = {
                if (!keyboardOpen && !focus) NavigationBar {
                    val tabs = if (wide) listOf(BookPage.Edit, BookPage.Desk, BookPage.Roleplay) else BookPage.entries
                    tabs.forEach { item ->
                        NavigationBarItem(
                            selected = page == item || (wide && item == BookPage.Edit && page == BookPage.Chapters),
                            onClick = { page = item },
                            icon = item.icon,
                            label = item.label
                        )
                    }
                }
            }
        ) { padding ->
            val contentModifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            if (page == BookPage.Roleplay) {
                pages.SaveableStateProvider("roleplay") { RoleplayScreen(model, current, contentModifier) }
            } else if (page == BookPage.Desk) {
                pages.SaveableStateProvider("desk") { BookDesk(model, current, contentModifier) { page = BookPage.Edit } }
            } else if (wide && !focus) {
                Row(contentModifier) {
                    Surface(Modifier.width(300.dp), color = MaterialTheme.colorScheme.surface) {
                        DirectoryScreen(model, current, Modifier.fillMaxSize()) { page = BookPage.Edit }
                    }
                    pages.SaveableStateProvider("edit") {
                        ChapterEditor(model, current, Modifier.weight(1f).fillMaxSize(), focus) { focus = !focus }
                    }
                }
            } else if (page == BookPage.Chapters) {
                pages.SaveableStateProvider("chapters") {
                    DirectoryScreen(model, current, contentModifier) { page = BookPage.Edit }
                }
            } else {
                pages.SaveableStateProvider("edit") {
                    ChapterEditor(model, current, contentModifier, focus) { focus = !focus }
                }
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
    var volumeActions by rememberSaveable { mutableStateOf<String?>(null) }
    var chapterActions by rememberSaveable { mutableStateOf<String?>(null) }
    var renamingVolume by rememberSaveable { mutableStateOf<String?>(null) }
    var movingChapter by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingChapter by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    LazyColumn(modifier, contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("目录", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                OutlinedButton(onClick = { creatingVolume = true }) { Text("新建卷") }
            }
        }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("查找章节") }) }
        volumes.forEach { volume ->
            item(key = "volume:${volume.id}") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(volume.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { newChapterVolume = volume.id }) { Text("新建章") }
                    Box {
                        IconButton(onClick = { volumeActions = volume.id }) { Icon(Icons.Default.MoreVert, "分卷操作") }
                        DropdownMenu(volumeActions == volume.id, { volumeActions = null }) {
                            DropdownMenuItem({ Text("重命名") }, onClick = { volumeActions = null; renamingVolume = volume.id })
                            listOf(-1 to "上移", 1 to "下移").forEach { (delta, label) ->
                                DropdownMenuItem({ Text(label) }, onClick = {
                                    volumeActions = null
                                    model.perform { moveVolume(book.id, volume.id, delta) }
                                })
                            }
                            if (byVolume[volume.id].isNullOrEmpty()) DropdownMenuItem({ Text("删除空卷") }, onClick = {
                                volumeActions = null
                                model.perform { removeEmptyVolume(book.id, volume.id) }
                            })
                        }
                    }
                }
            }
            items(byVolume[volume.id].orEmpty().filter { it.title.contains(query.trim(), ignoreCase = true) },
                key = { "chapter:${it.id}" }) { chapter ->
                val selected = chapter.id == book.activeId
                Surface(
                    onClick = { model.perform { select(book.id, chapter.id); opened() } },
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(chapter.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (chapter.complete) Text("定稿", style = MaterialTheme.typography.labelMedium)
                        Box {
                            IconButton(onClick = { chapterActions = chapter.id }) { Icon(Icons.Default.MoreVert, "章节管理") }
                            DropdownMenu(chapterActions == chapter.id, { chapterActions = null }) {
                                listOf(-1 to "上移", 1 to "下移").forEach { (delta, label) ->
                                    DropdownMenuItem({ Text(label) }, onClick = {
                                        chapterActions = null
                                        model.perform { moveChapter(book.id, chapter.id, volume.id, delta) }
                                    })
                                }
                                DropdownMenuItem({ Text("移至其他卷") }, enabled = volumes.size > 1, onClick = {
                                    chapterActions = null
                                    movingChapter = chapter.id
                                })
                                DropdownMenuItem({ Text("删除章节") }, enabled = chapters.size > 1, onClick = {
                                    chapterActions = null
                                    deletingChapter = chapter.id
                                })
                            }
                        }
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
    volumes.find { it.id == renamingVolume }?.let { volume ->
        NameDialog("更改卷名", "卷名", volume.title, dismiss = { renamingVolume = null }) { name ->
            model.perform { renameVolume(book.id, volume.id, name) }
        }
    }
    chapters.find { it.id == movingChapter }?.let { chapter ->
        AlertDialog(
            onDismissRequest = { movingChapter = null }, title = { Text("移至其他卷") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    volumes.filter { it.id != chapter.volumeId }.forEach { target ->
                        TextButton(onClick = {
                            model.perform { moveChapter(book.id, chapter.id, target.id); movingChapter = null }
                        }) { Text(target.title) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { movingChapter = null }) { Text("取消") } }
        )
    }
    chapters.find { it.id == deletingChapter }?.let { chapter ->
        AlertDialog(
            onDismissRequest = { deletingChapter = null }, title = { Text("删除「${chapter.title}」？") },
            text = { Text("章节正文和章节还原点会从本机删除。") },
            confirmButton = {
                TextButton(onClick = {
                    model.perform { removeChapter(book.id, chapter.id); deletingChapter = null }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }, dismissButton = { TextButton(onClick = { deletingChapter = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun BookMenu(model: WriterModel, book: BookRow, back: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var rename by rememberSaveable { mutableStateOf(false) }
    var remove by rememberSaveable { mutableStateOf(false) }
    var upload by rememberSaveable { mutableStateOf(false) }
    val server by model.studioStatus.collectAsStateWithLifecycle()
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
            if (server != null) DropdownMenuItem({ Text("备份副本到服务器") },
                onClick = { expanded = false; upload = true })
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
    if (upload) AlertDialog(
        onDismissRequest = { upload = false }, title = { Text("备份《${book.title}》到服务器？") },
        text = { Text("全书正文和书内资料会上传到已登录的青简服务器，并保存为独立作品。") },
        confirmButton = { TextButton(onClick = { model.uploadStudioCopy(book.id); upload = false }) { Text("上传副本") } },
        dismissButton = { TextButton(onClick = { upload = false }) { Text("取消") } }
    )
}

@Composable
private fun BookDesk(model: WriterModel, book: BookRow, modifier: Modifier, openEditor: () -> Unit) {
    val details = remember(book.payload) { JSONObject(book.payload) }
    var field by rememberSaveable(book.id) { mutableStateOf("outline") }
    val label = if (field == "outline") "大纲" else "设定"
    val existing = details.string(field)
    var value by remember(book.id, field, existing) { mutableStateOf(existing) }
    var changedField by remember { mutableStateOf<String?>(null) }
    var deskPage by rememberSaveable { mutableStateOf("写作") }
    val bookFileName = remember(book.title) { safeFileName(book.title) }
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("工作台", style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("写作", "资料", "剧情", "附件", "配置").forEach { item ->
                    FilterChip(deskPage == item, { deskPage = item }, label = { Text(item) })
                }
            }
            when (deskPage) {
                "资料" -> ResearchWorkspace(model, book)
                "剧情" -> StoryScreen(model, book, openEditor)
                "附件" -> ResourcesScreen(model, book)
                "配置" -> BookSettings(model, book)
                else -> {
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
