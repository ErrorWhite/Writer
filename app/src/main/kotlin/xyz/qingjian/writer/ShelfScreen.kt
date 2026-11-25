package xyz.qingjian.writer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Person
import io.github.suqi8.coui.kmp.basic.FloatingActionButton
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.TopAppBar
import io.github.suqi8.coui.kmp.basic.COUIScrollBehavior
import io.github.suqi8.coui.kmp.basic.SegmentButton
import io.github.suqi8.coui.kmp.basic.ToolbarTextButton
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.RadioButtonPreference
import io.github.suqi8.coui.kmp.preference.RadioButtonLocation
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val lightBookCovers = listOf(
    Color(0xFFD8E3F0) to Color(0xFFB8CDE2),
    Color(0xFFDCE8E0) to Color(0xFFB6CEC0),
    Color(0xFFF0E7D5) to Color(0xFFDAC6A6),
    Color(0xFFE7E0EC) to Color(0xFFCBBBD8)
)

private val darkBookCovers = listOf(
    Color(0xFF3A526D) to Color(0xFF263A54),
    Color(0xFF3B574F) to Color(0xFF293B35),
    Color(0xFF574C3A) to Color(0xFF3E3428),
    Color(0xFF594C64) to Color(0xFF3C3146)
)

@Composable
internal fun ShelfScreen(model: WriterModel, open: (String) -> Unit, settings: () -> Unit, roleplay: () -> Unit) {
    val books by model.library.shelf.collectAsStateWithLifecycle(initialValue = null)
    val studio by model.studioStatus.collectAsStateWithLifecycle()
    val remote by model.studioBooks.collectAsStateWithLifecycle()
    val cards by model.shelfCards.collectAsStateWithLifecycle()
    val lastOpened by model.lastOpenedBook.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var importing by rememberSaveable { mutableStateOf(false) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) model.previewImport(it)
    }
    val pickBackup = { importPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
    val scroll = COUIScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = WriterTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = "书架",
                color = WriterTheme.colorScheme.background,
                largeTitleColor = WriterTheme.colorScheme.onBackground,
                showDivider = false,
                actions = {
                    IconButton(onClick = roleplay) { Icon(Icons.Default.Person, "文本扮演") }
                    IconButton(onClick = settings) { Icon(Icons.Default.Settings, "设置") }
                },
                scrollBehavior = scroll
            )
        },
        floatingActionButton = {
            if (books?.isNotEmpty() == true) FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, "新建作品")
            }
        }
    ) { padding ->
        val entries = books
        if (entries != null && entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(
                        title = "开始写作",
                        subtitle = "新建作品，或导入青简全书备份。",
                        actionText = "新建作品",
                        onActionClick = { creating = true },
                        titleStyle = WriterTheme.typography.headlineSmall,
                        illustration = { Image(painterResource(R.drawable.qingjian_icon), null, Modifier.size(64.dp)) }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = pickBackup) { Text("导入备份") }
                        if (studio != null) TextButton(onClick = model::listStudioBooks) { Text("服务器书架") }
                    }
                }
            }
        } else if (entries != null) {
            val recent = entries.firstOrNull { it.id == lastOpened }
            val remaining = if (recent == null) entries else entries.filter { it.id != recent.id }
            val tools: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (recent != null) RecentBook(recent, open)
                    if (remaining.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("作品", Modifier.weight(1f), style = WriterTheme.typography.titleLarge)
                            SegmentButton(listOf("封面", "列表"), if (cards) 0 else 1, { model.setShelfCards(it == 0) },
                                modifier = Modifier.width(134.dp))
                            Box {
                                ToolbarTextButton(text = "导入", onClick = {
                                    if (studio == null) pickBackup() else importing = true
                                })
                                DropdownMenu(importing, { importing = false }) {
                                    DropdownMenuItem("导入书稿备份", onClick = { importing = false; pickBackup() })
                                    DropdownMenuItem("服务器书架", onClick = { importing = false; model.listStudioBooks() })
                                }
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            ToolbarTextButton(text = "导入备份", onClick = pickBackup)
                            if (studio != null) ToolbarTextButton(text = "服务器书架", onClick = model::listStudioBooks)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                val listModifier = Modifier.widthIn(max = 980.dp).fillMaxSize()
                val gutters = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp)
                if (cards) LazyVerticalGrid(
                    columns = GridCells.Adaptive(142.dp), modifier = listModifier, contentPadding = gutters,
                    verticalArrangement = Arrangement.spacedBy(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) { tools() }
                    items(remaining, key = ShelfEntry::id) { book -> BookTile(book, true, open) }
                } else {
                    LazyColumn(listModifier, contentPadding = gutters, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { tools() }
                        items(remaining, key = ShelfEntry::id) { book -> BookTile(book, false, open) }
                    }
                }
            }
        }
    }
    if (creating) NameDialog("新建作品", "书名", dismiss = { creating = false }) { title ->
        model.perform { open(create(title)) }
    }
    remote?.let { entries ->
        AlertDialog(
            onDismissRequest = model::closeStudioBooks, title = { Text("服务器书架") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (entries.isEmpty()) Text("服务器没有作品")
                    entries.forEach { book ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(book.title, style = WriterTheme.typography.titleMedium)
                            Row {
                                TextButton(onClick = { model.previewStudioBook(book.id) }) { Text("导入副本") }
                                TextButton(onClick = { model.openSyncedBook(book.id, open) }) { Text("同步并打开") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = model::closeStudioBooks) { Text("关闭") } }
        )
    }
}

@Composable
private fun RecentBook(book: ShelfEntry, open: (String) -> Unit) {
    val colors = WriterTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(), onClick = { open(book.id) },
        insideMargin = PaddingValues(0.dp), cornerRadius = 24.dp,
        colors = CardDefaults.defaultColors(color = colors.surface, contentColor = colors.onSurface)
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Cover(book, Modifier.width(76.dp).aspectRatio(0.69f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("继续写作", style = WriterTheme.typography.labelMedium, color = colors.primary)
                Text(book.title, style = WriterTheme.typography.chapterTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.activeChapterTitle.orEmpty().ifBlank { "${book.chapterCount} 章" },
                    style = WriterTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = colors.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun BookTile(book: ShelfEntry, grid: Boolean, open: (String) -> Unit) {
    Card(onClick = { open(book.id) }, modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
        cornerRadius = if (grid) 16.dp else 18.dp,
        colors = CardDefaults.defaultColors(color = if (grid) Color.Transparent else WriterTheme.colorScheme.surface)) {
        val details: @Composable () -> Unit = {
            Text(book.title, style = WriterTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${book.chapterCount} 章", style = WriterTheme.typography.bodySmall,
                color = WriterTheme.colorScheme.onSurfaceVariant)
        }
        if (grid) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Cover(book, Modifier.fillMaxWidth().aspectRatio(0.76f))
                Column(Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) { details() }
            }
        } else {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                Cover(book, Modifier.size(width = 52.dp, height = 68.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { details() }
            }
        }
    }
}

@Composable
private fun Cover(book: ShelfEntry, shape: Modifier) {
    val bytes by produceState<ByteArray?>(null, book.cover) {
        value = withContext(Dispatchers.Default) { coverImage(book.cover) }
    }
    val coverShape = RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp, topEnd = 12.dp, bottomEnd = 12.dp)
    val modifier = shape.clip(coverShape)
    if (bytes != null) {
        AsyncImage(bytes, null, modifier, contentScale = ContentScale.Crop)
    } else {
        val stock = if (COUITheme.isDark) darkBookCovers else lightBookCovers
        val (highlight, shade) = stock[(book.id.hashCode() and Int.MAX_VALUE) % stock.size]
        Box(modifier.background(Brush.linearGradient(listOf(highlight, shade)))
            .border(1.dp, Color.Black.copy(alpha = .08f), coverShape), contentAlignment = Alignment.Center) {
            Box(
                Modifier.align(Alignment.CenterStart).fillMaxHeight().width(6.dp)
                    .background(Color.Black.copy(alpha = .11f))
            )
            Box(
                Modifier.align(Alignment.CenterStart).padding(start = 8.dp, top = 1.dp, bottom = 1.dp)
                    .fillMaxHeight().width(1.dp).background(Color.White.copy(alpha = .27f))
            )
            Image(painterResource(R.drawable.qingjian_icon), null, Modifier.size(26.dp), alpha = .78f)
        }
    }
}

@Composable
internal fun SettingsScreen(model: WriterModel, mode: ThemeMode, back: () -> Unit, providers: () -> Unit) {
    Scaffold(containerColor = WriterTheme.colorScheme.background, topBar = { BackBar("设置", back) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PreferenceGroup("写作与模型") {
                item("providers") {
                    ArrowPreference("模型供应商", onClick = providers, startAction = {
                        Icon(Icons.Default.Settings, null, tint = WriterTheme.colorScheme.primary)
                    })
                }
            }
            PreferenceGroup("外观") {
                ThemeMode.entries.forEach { option ->
                    item(option.name) {
                        RadioButtonPreference(option.label, mode == option, { model.setTheme(option) },
                            radioButtonLocation = RadioButtonLocation.End)
                    }
                }
            }
        }
    }
}
