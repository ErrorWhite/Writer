package xyz.qingjian.writer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Person
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.FloatingActionButton
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.TopAppBar
import io.github.suqi8.coui.kmp.basic.COUIScrollBehavior
import io.github.suqi8.coui.kmp.basic.CardListGroup
import io.github.suqi8.coui.kmp.preference.RadioButtonPreference
import io.github.suqi8.coui.kmp.preference.RadioButtonLocation
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ShelfScreen(model: WriterModel, open: (String) -> Unit, settings: () -> Unit, roleplay: () -> Unit) {
    val books by model.library.shelf.collectAsStateWithLifecycle(initialValue = null)
    val studio by model.studioStatus.collectAsStateWithLifecycle()
    val remote by model.studioBooks.collectAsStateWithLifecycle()
    val cards by model.shelfCards.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
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
                title = "青简书架",
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
            Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), contentAlignment = Alignment.TopCenter) {
                Card(colors = CardDefaults.defaultColors(color = WriterTheme.colorScheme.surface)) {
                    Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Image(painterResource(R.drawable.qingjian_icon), null, Modifier.size(48.dp))
                        Text("书架上还没有作品", style = WriterTheme.typography.headlineSmall)
                        Text("可以新建作品，或导入青简全书备份。")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { creating = true }) { Text("新建作品") }
                            OutlinedButton(onClick = pickBackup) { Text("导入备份") }
                        }
                        if (studio != null) TextButton(onClick = model::listStudioBooks) { Text("从服务器导入") }
                    }
                }
            }
        } else if (entries != null) {
            val tools: @Composable () -> Unit = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = pickBackup) { Text("导入备份") }
                        if (studio != null) TextButton(onClick = model::listStudioBooks) { Text("服务器书架") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(cards, { model.setShelfCards(true) }, label = "卡片")
                        FilterChip(!cards, { model.setShelfCards(false) }, label = "横排")
                    }
                }
            }
            val listModifier = Modifier.fillMaxSize().padding(padding)
            val gutters = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp)
            if (cards) LazyVerticalGrid(
                columns = GridCells.Adaptive(142.dp), modifier = listModifier, contentPadding = gutters,
                verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { tools() }
                items(entries, key = ShelfEntry::id) { book -> BookTile(book, true, open) }
            } else {
                LazyColumn(listModifier, contentPadding = gutters, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { tools() }
                    items(entries, key = ShelfEntry::id) { book -> BookTile(book, false, open) }
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
private fun BookTile(book: ShelfEntry, grid: Boolean, open: (String) -> Unit) {
    Card(onClick = { open(book.id) }, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = WriterTheme.colorScheme.surface)) {
        val details: @Composable () -> Unit = {
            Text(book.title, style = WriterTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${book.chapterCount} 章", style = WriterTheme.typography.bodyMedium,
                color = WriterTheme.colorScheme.onSurfaceVariant)
        }
        if (grid) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Cover(book, Modifier.fillMaxWidth().size(height = 144.dp, width = 132.dp))
                details()
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
    val modifier = shape.clip(RoundedCornerShape(9.dp))
    if (bytes != null) {
        AsyncImage(bytes, null, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(WriterTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.qingjian_icon), null, Modifier.size(30.dp))
        }
    }
}

@Composable
internal fun SettingsScreen(model: WriterModel, mode: ThemeMode, back: () -> Unit, providers: () -> Unit) {
    Scaffold(containerColor = WriterTheme.colorScheme.background, topBar = { BackBar("设置", back) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("外观", style = WriterTheme.typography.titleLarge)
            Card(colors = CardDefaults.defaultColors(color = WriterTheme.colorScheme.surface)) {
                CardListGroup {
                    ThemeMode.entries.forEach { option ->
                        item(option.name) {
                            RadioButtonPreference(option.label, mode == option, { model.setTheme(option) },
                                radioButtonLocation = RadioButtonLocation.End)
                        }
                    }
                }
            }
            Card {
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = providers) { Text("模型供应商") }
            }
        }
    }
}
