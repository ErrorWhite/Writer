package xyz.qingjian.writer

import android.os.Build
import com.itos.coui.effect.CouiFloatingRenderer
import com.itos.coui.navigation.CouiNavigationItem
import com.itos.coui.navigation.NavigationBar
import androidx.compose.material3.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.itos.coui.layout.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal enum class BookPage(val label: String, val icon: ImageVector) {
    Edit("写作", Icons.Default.Edit),
    Chapters("目录", Icons.Default.List),
    Desk("工作台", Icons.Default.Settings),
    Roleplay("扮演", Icons.Default.Person)
}

@Composable
internal fun BookScreen(
    model: WriterModel, id: String, initialPage: BookPage = BookPage.Edit,
    legacyRecovery: (() -> Unit)? = null, legacyPreview: (() -> Unit)? = null, back: () -> Unit
) {
    val book by remember(id) { model.library.book(id) }.collectAsStateWithLifecycle(initialValue = null)
    val synchronization by model.syncStatus.collectAsStateWithLifecycle()
    LaunchedEffect(synchronization[id]) {
        if (synchronization[id] == "服务器已删除书稿") back()
    }
    val current = book ?: return
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val pages = rememberSaveableStateHolder()
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var focus by rememberSaveable { mutableStateOf(false) }
    var planChapter by rememberSaveable { mutableStateOf<String?>(null) }
    var anchorJump by remember { mutableStateOf<Pair<String, WritingPosition>?>(null) }
    val navigate: (BookPage) -> Unit = { destination ->
        if (destination != page) {
            anchorJump = null
            page = destination
        }
    }
    val leave: () -> Unit = {
        when {
            focus -> focus = false
            page == BookPage.Edit && anchorJump != null -> {
                anchorJump = null
                page = BookPage.Desk
            }
            page == initialPage -> back()
            page != BookPage.Edit -> page = BookPage.Edit
            else -> back()
        }
    }
    BackHandler(enabled = !keyboardOpen, onBack = leave)
    BoxWithConstraints {
        val wide = maxWidth >= 840.dp
        Scaffold(
            containerColor = WriterTheme.colorScheme.background,
            topBar = {
                if (!focus) BackBar(if (page == BookPage.Edit) current.title else page.label, leave) {
                    if (page == BookPage.Edit && !keyboardOpen) {
                        if (wide) for (tool in listOf(BookPage.Desk, BookPage.Roleplay)) {
                            IconButton(onClick = { navigate(tool) }) { Icon(tool.icon, tool.label) }
                        }
                        BookMenu(model, current, back, legacyRecovery, legacyPreview)
                    }
                }
            },
            bottomBar = {
                if (!wide && !focus && !keyboardOpen) CompactBookNavigation(page, navigate)
            }
        ) { padding ->
            val contentModifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            if (page == BookPage.Roleplay) {
                pages.SaveableStateProvider("roleplay") { RoleplayScreen(model, current, contentModifier) }
            } else if (page == BookPage.Desk) {
                pages.SaveableStateProvider("desk") {
                    BookDesk(model, current, contentModifier, jump = { chapterId, position ->
                        anchorJump = chapterId to position
                        page = BookPage.Edit
                    }) { page = BookPage.Edit }
                }
            } else if (wide && !focus) {
                Row(contentModifier) {
                    Surface(Modifier.width(300.dp), color = WriterTheme.colorScheme.surface) {
                        DirectoryScreen(model, current, Modifier.fillMaxSize()) {
                            planChapter = it
                            anchorJump = null
                            page = BookPage.Edit
                        }
                    }
                    pages.SaveableStateProvider("edit") {
                        ChapterEditor(model, current, Modifier.weight(1f).fillMaxSize(), focus,
                            planChapter == current.activeId, { planChapter = null },
                            anchorJump?.takeIf { it.first == current.activeId }?.second) { focus = !focus }
                    }
                }
            } else if (page == BookPage.Chapters) {
                pages.SaveableStateProvider("chapters") {
                    DirectoryScreen(model, current, contentModifier) {
                        planChapter = it
                        anchorJump = null
                        page = BookPage.Edit
                    }
                }
            } else {
                pages.SaveableStateProvider("edit") {
                    ChapterEditor(model, current, contentModifier, focus,
                        planChapter == current.activeId, { planChapter = null },
                        anchorJump?.takeIf { it.first == current.activeId }?.second) { focus = !focus }
                }
            }
        }
    }
}

@Composable
private fun CompactBookNavigation(selected: BookPage, navigate: (BookPage) -> Unit) {
    if (Build.VERSION.SDK_INT >= 34) {
        val renderer = remember { runCatching { CouiFloatingRenderer() }.getOrNull() }
        if (renderer != null) {
            val items = remember { BookPage.entries.map { CouiNavigationItem(it.label, it.icon) } }
            NavigationBar(
                renderer = renderer, items = items, selectedIndex = selected.ordinal,
                selectedColor = WriterTheme.colorScheme.primary,
                unselectedColor = WriterTheme.colorScheme.onSurfaceVariant,
                onSelection = { navigate(BookPage.entries[it]) }
            )
            return
        }
    }
    Surface(color = WriterTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .selectableGroup().padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            for (destination in BookPage.entries) {
                val chosen = destination == selected
                val color = if (chosen) WriterTheme.colorScheme.primary else WriterTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier.weight(1f).selectable(selected = chosen, role = Role.Tab, onClick = { navigate(destination) })
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(destination.icon, contentDescription = null, tint = color)
                    Text(destination.label, color = color, style = WriterTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun DirectoryScreen(model: WriterModel, book: BookRow, modifier: Modifier, opened: (String?) -> Unit) {
    val volumes by remember(book.id) { model.library.volumes(book.id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val chapters by remember(book.id) { model.library.directory(book.id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val byVolume = remember(chapters) { chapters.groupBy(ChapterEntry::volumeId) }
    val totalWords = remember(chapters) { chapters.sumOf { it.wordCount.toLong() } }
    val settings = remember(book.payload) { JSONObject(book.payload) }
    val chapterGoal = bookChapterGoal(settings)
    val bookGoal = settings.optJSONObject("aiSetup")?.optInt("wordTarget")?.coerceAtLeast(0) ?: 0
    var creatingVolume by rememberSaveable { mutableStateOf(false) }
    var newChapterVolume by rememberSaveable { mutableStateOf<String?>(null) }
    var volumeActions by rememberSaveable { mutableStateOf<String?>(null) }
    var chapterActions by rememberSaveable { mutableStateOf<String?>(null) }
    var renamingVolume by rememberSaveable { mutableStateOf<String?>(null) }
    var renamingChapter by rememberSaveable { mutableStateOf<String?>(null) }
    var movingChapter by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingChapter by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val term = query.trim()
    val matches by remember(book.id, term) {
        if (term.isEmpty()) flowOf(emptyList()) else model.library.searchChapters(book.id, term)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val found = remember(matches) { matches.toSet() }
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            WriterSearch(query, { query = it }, "搜索目录与正文")
        }
        if (term.isNotEmpty() && found.isEmpty()) item { EmptyState(title = "没有匹配的章节") }
        if (term.isEmpty()) item {
            PreferenceCategory("${volumes.size} 卷 · ${chapters.size} 章", endWidget = {
                ToolbarTextButton(text = "新建卷", onClick = { creatingVolume = true })
            })
            Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (bookGoal > 0) "全书 $totalWords / $bookGoal 字" else "全书 $totalWords 字",
                    style = WriterTheme.typography.bodySmall, color = WriterTheme.colorScheme.onSurfaceVariant)
                if (bookGoal > 0) LinearProgressIndicator(Modifier.fillMaxWidth(),
                    progress = (totalWords.toFloat() / bookGoal).coerceIn(0f, 1f), height = 2.dp)
            }
        }
        volumes.forEach { volume ->
            val visible = byVolume[volume.id].orEmpty().filter { term.isEmpty() || it.id in found }
            if (term.isNotEmpty() && visible.isEmpty()) return@forEach
            item(key = "volume:${volume.id}") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(volume.title, Modifier.weight(1f).padding(start = 6.dp), style = WriterTheme.typography.titleLarge,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { newChapterVolume = volume.id }) { Text("新建章") }
                    Box {
                        IconButton(onClick = { volumeActions = volume.id }) { Icon(Icons.Default.MoreVert, "分卷操作") }
                        DropdownMenu(volumeActions == volume.id, { volumeActions = null }) {
                            DropdownMenuItem("重命名", onClick = { volumeActions = null; renamingVolume = volume.id })
                            listOf(-1 to "上移", 1 to "下移").forEach { (delta, label) ->
                                DropdownMenuItem(label, onClick = {
                                    volumeActions = null
                                    model.perform { moveVolume(book.id, volume.id, delta) }
                                })
                            }
                            if (byVolume[volume.id].isNullOrEmpty()) DropdownMenuItem("删除空卷", onClick = {
                                volumeActions = null
                                model.perform { removeEmptyVolume(book.id, volume.id) }
                            })
                        }
                    }
                }
            }
            items(visible,
                key = { "chapter:${it.id}" }) { chapter ->
                val selected = chapter.id == book.activeId
                Surface(
                    onClick = { model.perform(requiresSaved = false) {
                        if (term.isNotEmpty()) {
                            val body = chapterText(book.id, chapter.id)
                            val start = body.indexOf(term, ignoreCase = true)
                            if (start >= 0) {
                                val block = withContext(Dispatchers.Default) {
                                    editorBlocks(body).indexOfFirst { start in it.start until it.end }
                                }
                                model.saveWritingPosition(DraftKey(book.id, chapter.id),
                                    WritingPosition(start, start + term.length, block.coerceAtLeast(0), 0))
                            }
                        }
                        select(book.id, chapter.id)
                        opened(null)
                    } },
                    color = if (selected) WriterTheme.colorScheme.primaryContainer else WriterTheme.colorScheme.surface,
                    shape = WriterTheme.shapes.medium
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(chapter.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val goal = chapter.goal.takeIf { it > 0 } ?: chapterGoal
                            val count = if (goal > 0) "${chapter.wordCount} / $goal 字" else "${chapter.wordCount} 字"
                            Text("${if (chapter.complete) "定稿" else "草稿"} · $count",
                                style = WriterTheme.typography.labelMedium, color = WriterTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { chapterActions = chapter.id }) { Icon(Icons.Default.MoreVert, "章节管理") }
                            DropdownMenu(chapterActions == chapter.id, { chapterActions = null }) {
                                DropdownMenuItem("重命名", onClick = {
                                    chapterActions = null
                                    renamingChapter = chapter.id
                                })
                                listOf(-1 to "上移", 1 to "下移").forEach { (delta, label) ->
                                    DropdownMenuItem(label, onClick = {
                                        chapterActions = null
                                        model.perform { moveChapter(book.id, chapter.id, volume.id, delta) }
                                    })
                                }
                                DropdownMenuItem("移至其他卷", enabled = volumes.size > 1, onClick = {
                                    chapterActions = null
                                    movingChapter = chapter.id
                                })
                                DropdownMenuItem("删除章节", enabled = chapters.size > 1, onClick = {
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
        var title by rememberSaveable(volume) { mutableStateOf("") }
        var plan by rememberSaveable(volume) { mutableStateOf(false) }
        val dismiss = { newChapterVolume = null }
        AlertDialog(
            onDismissRequest = dismiss, title = { Text("新建章节") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("章名") })
                    CheckOption("用 AI 规划章节摘要", plan) { plan = it }
                }
            },
            confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = {
                val name = title.trim()
                val startPlanning = plan
                model.perform {
                    val chapterId = addChapter(book.id, volume, name, if (startPlanning) chapterPlan() else null)
                    dismiss()
                    opened(chapterId.takeIf { startPlanning })
                }
            }) { Text("创建") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }
        )
    }
    volumes.find { it.id == renamingVolume }?.let { volume ->
        NameDialog("更改卷名", "卷名", volume.title, dismiss = { renamingVolume = null }) { name ->
            model.perform { renameVolume(book.id, volume.id, name) }
        }
    }
    chapters.find { it.id == renamingChapter }?.let { chapter ->
        NameDialog("更改章名", "章名", chapter.title, dismiss = { renamingChapter = null }) { name ->
            model.perform { renameChapter(book.id, chapter.id, name) }
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
                }) { Text("删除", color = WriterTheme.colorScheme.error) }
            }, dismissButton = { TextButton(onClick = { deletingChapter = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun BookMenu(
    model: WriterModel, book: BookRow, back: () -> Unit,
    legacyRecovery: (() -> Unit)?, legacyPreview: (() -> Unit)?
) {
    var expanded by remember { mutableStateOf(false) }
    var rename by rememberSaveable { mutableStateOf(false) }
    var remove by rememberSaveable { mutableStateOf(false) }
    var upload by rememberSaveable { mutableStateOf(false) }
    val server by model.studioStatus.collectAsStateWithLifecycle()
    val synced by model.syncedBooks.collectAsStateWithLifecycle()
    val synchronization by model.syncStatus.collectAsStateWithLifecycle()
    val isSynced = book.id in synced
    val pendingDeletion = synced[book.id]?.deleteDigest != null
    var confirmSync by rememberSaveable { mutableStateOf(false) }
    val cover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) model.importCover(book.id, it)
    }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "作品管理") }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem("更改书名", onClick = { expanded = false; rename = true })
            DropdownMenuItem("更换封面", onClick = {
                expanded = false
                cover.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
            })
            if (book.cover.isNotEmpty()) DropdownMenuItem("清除封面", onClick = {
                expanded = false
                model.perform { setCover(book.id, "") }
            })
            if (server != null) DropdownMenuItem("备份副本到服务器",
                onClick = { expanded = false; upload = true })
            if (!pendingDeletion && (server != null || isSynced)) DropdownMenuItem(
                if (isSynced) "停止同步此书" else "开启跨端同步", onClick = {
                    expanded = false
                    if (isSynced) model.toggleBookSync(book.id) else confirmSync = true
                })
            if (isSynced) DropdownMenuItem(synchronization[book.id] ?: "等候同步", enabled = false, onClick = {})
            if (legacyRecovery != null) DropdownMenuItem("恢复旧工作区草稿", onClick = {
                expanded = false
                model.perform { legacyRecovery() }
            })
            if (legacyPreview != null) DropdownMenuItem("旧工作区回归", onClick = {
                expanded = false
                model.perform { legacyPreview() }
            })
            if (pendingDeletion) DropdownMenuItem("取消待同步删除", onClick = {
                expanded = false
                model.cancelSynchronizedDeletion(book.id)
            })
            else DropdownMenuItem("删除作品", onClick = { expanded = false; remove = true })
        }
    }
    if (rename) NameDialog("更改书名", "书名", book.title, dismiss = { rename = false }) { title ->
        model.perform { this.rename(book.id, title) }
    }
    if (remove) AlertDialog(
        onDismissRequest = { remove = false },
        title = { Text("删除《${book.title}》？") },
        text = {
            Text(if (isSynced) "这会删除同步书稿。离线时先保留本机稿件，恢复连接后再同步删除。"
                else "这会删除本机书稿、资料和章节还原点。")
        },
        confirmButton = {
            TextButton(onClick = {
                remove = false
                model.deleteBook(book.id, back)
            }) { Text("删除", color = WriterTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { remove = false }) { Text("取消") } }
    )
    if (upload) AlertDialog(
        onDismissRequest = { upload = false }, title = { Text("备份《${book.title}》到服务器？") },
        text = { Text("全书正文和书内资料会上传到已登录的青简服务器，并保存为独立作品。") },
        confirmButton = { TextButton(onClick = { model.uploadStudioCopy(book.id); upload = false }) { Text("上传副本") } },
        dismissButton = { TextButton(onClick = { upload = false }) { Text("取消") } }
    )
    if (confirmSync) AlertDialog(
        onDismissRequest = { confirmSync = false }, title = { Text("开启跨端同步") },
        text = { Text("当前书稿、附件和书内会话会与已登录的青简服务器同步。") },
        confirmButton = { TextButton(onClick = { confirmSync = false; model.toggleBookSync(book.id) }) { Text("开启同步") } },
        dismissButton = { TextButton(onClick = { confirmSync = false }) { Text("取消") } }
    )
}

@Composable
private fun BookDesk(
    model: WriterModel, book: BookRow, modifier: Modifier,
    jump: (String, WritingPosition) -> Unit, openEditor: () -> Unit
) {
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
            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val tabs = listOf("写作", "资料", "审阅", "剧情", "关系图", "附件", "配置")
            ChipTabRow(tabs, tabs.indexOf(deskPage).coerceAtLeast(0), { deskPage = tabs[it] },
                modifier = Modifier.fillMaxWidth(), horizontalPadding = 0.dp, itemSpacing = 6.dp)
            when (deskPage) {
                "资料" -> ResearchWorkspace(model, book, jump = jump)
                "审阅" -> WorkbenchReview(model, book)
                "剧情" -> StoryScreen(model, book, openEditor)
                "关系图" -> LibraryGraph(model, book, openEditor)
                "附件" -> ResourcesScreen(model, book)
                "配置" -> BookSettings(model, book)
                else -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("outline" to "大纲", "notes" to "设定").forEach { (key, name) ->
                    FilterChip(selected = field == key, onClick = {
                        if (field != key) {
                            if (value == existing) field = key else changedField = key
                        }
                    }, label = name)
                }
            }
            OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), minLines = 6, label = { Text(label) })
            Button(enabled = value != existing, onClick = { model.perform { updateBookText(book.id, field, value) } }) {
                Text("保存$label")
            }
            PreferenceGroup("导出") {
                ExportFormat.entries.filter { it != ExportFormat.Chapter }.forEach { format ->
                    item(format.name) {
                        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mime)) {
                            it?.let { uri -> model.writeExport(uri, book.id, format, null) }
                        }
                        ArrowPreference(format.label, onClick = {
                            launcher.launch("$bookFileName.${format.extension}")
                        })
                    }
                }
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
private fun WorkbenchReview(model: WriterModel, book: BookRow) {
    var intent by rememberSaveable(book.id, book.activeId) { mutableStateOf<WritingIntent?>(null) }
    var versions by rememberSaveable(book.id, book.activeId) { mutableStateOf(false) }
    var usage by rememberSaveable(book.id) { mutableStateOf(false) }
    val hasChapter = book.activeId.isNotBlank()
    PreferenceGroup("当前章节") {
        item("extract") {
            ArrowPreference("AI 整理资料", enabled = hasChapter, onClick = { intent = WritingIntent.Extract })
        }
        item("review") {
            ArrowPreference("审阅与比稿", enabled = hasChapter, onClick = { intent = WritingIntent.Review })
        }
        item("versions") {
            ArrowPreference("章节版本", enabled = hasChapter, onClick = { versions = true })
        }
        item("usage") {
            ArrowPreference("AI 使用量", onClick = { usage = true })
        }
    }
    if (intent != null || versions) {
        val chapter by remember(book.id, book.activeId) { model.library.chapter(book.id, book.activeId) }
            .collectAsStateWithLifecycle(initialValue = null)
        chapter?.takeIf { it.id == book.activeId }?.let { current ->
            val requested = intent
            if (requested != null) {
                WritingAiDialog(model, book, current, TextRange.Zero, emptySet(), requested) { intent = null }
            } else ChapterHistoryDialog(model, current) { versions = false }
        }
    }
    if (usage) ModelUsageDialog(model) { usage = false }
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
