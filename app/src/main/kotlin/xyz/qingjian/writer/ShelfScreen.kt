package xyz.qingjian.writer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import io.github.suqi8.coui.kmp.basic.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import io.github.suqi8.coui.kmp.basic.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import io.github.suqi8.coui.kmp.basic.TopAppBar
import io.github.suqi8.coui.kmp.basic.COUIScrollBehavior
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import io.github.suqi8.coui.kmp.basic.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShelfScreen(model: WriterModel, open: (String) -> Unit, settings: () -> Unit) {
    val books by model.library.shelf.collectAsStateWithLifecycle(initialValue = null)
    var creating by rememberSaveable { mutableStateOf(false) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) model.previewImport(it)
    }
    val pickBackup = { importPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
    val scroll = COUIScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = "青简书架",
                color = MaterialTheme.colorScheme.background,
                largeTitleColor = MaterialTheme.colorScheme.onBackground,
                showDivider = false,
                actions = { IconButton(onClick = settings) { Icon(Icons.Default.Settings, "设置") } },
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
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Image(painterResource(R.drawable.qingjian_icon), null, Modifier.size(48.dp))
                        Text("书架上还没有作品", style = MaterialTheme.typography.headlineSmall)
                        Text("可以新建作品，或导入青简全书备份。")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { creating = true }) { Text("新建作品") }
                            OutlinedButton(onClick = pickBackup) { Text("导入备份") }
                        }
                    }
                }
            }
        } else if (entries != null) {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { OutlinedButton(onClick = pickBackup) { Text("导入备份") } }
                items(entries, key = ShelfEntry::id) { book ->
                    Card(
                        onClick = { open(book.id) }, modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(15.dp)
                        ) {
                            Cover(book)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(book.title, style = MaterialTheme.typography.titleLarge,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${book.chapterCount} 章", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    if (creating) NameDialog("新建作品", "书名", dismiss = { creating = false }) { title ->
        model.perform { open(create(title)) }
    }
}

@Composable
private fun Cover(book: ShelfEntry) {
    val bytes by produceState<ByteArray?>(null, book.cover) {
        value = withContext(Dispatchers.Default) { coverImage(book.cover) }
    }
    val modifier = Modifier.size(width = 52.dp, height = 68.dp).clip(RoundedCornerShape(9.dp))
    if (bytes != null) {
        AsyncImage(bytes, null, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.qingjian_icon), null, Modifier.size(30.dp))
        }
    }
}

@Composable
internal fun SettingsScreen(model: WriterModel, mode: ThemeMode, back: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { BackBar("设置", back) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("外观", style = MaterialTheme.typography.titleLarge)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                ThemeMode.entries.forEach { item ->
                    ListItem(
                        headlineContent = { Text(item.label) },
                        modifier = Modifier.clickable(role = Role.RadioButton) { model.setTheme(item) },
                        trailingContent = { RadioButton(selected = mode == item, onClick = null) }
                    )
                }
            }
            ProviderSettings(model)
        }
    }
}
