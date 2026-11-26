package xyz.qingjian.writer

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.SnackbarHost
import io.github.suqi8.coui.kmp.basic.SnackbarHostState
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.overlay.OverlayInputDialog
import io.github.suqi8.coui.kmp.basic.SmallTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: WriterModel = viewModel()
            val mode by model.theme.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            val view = LocalView.current
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            QingjianTheme(dark) { WriterApp(model, mode, dark) }
        }
    }
}

@Composable
private fun WriterApp(model: WriterModel, mode: ThemeMode, dark: Boolean) {
    val nav = rememberNavController()
    val notice by model.notice.collectAsStateWithLifecycle()
    val preview by model.importPreview.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it); model.notice(null) }
    }
    val openBook: (String) -> Unit = { id ->
        model.openedBook(id)
        nav.navigate("book/${Uri.encode(id)}") { launchSingleTop = true }
    }
    var resumed by rememberSaveable { mutableStateOf(false) }

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
      Box(Modifier.fillMaxSize()) {
        NavHost(nav, startDestination = "shelf") {
            composable("shelf") {
                ShelfScreen(model, openBook, { nav.navigate("settings") }) {
                    model.perform { standaloneRoleplay(); nav.navigate("roleplay") }
                }
            }
            composable("settings") {
                SettingsScreen(model, mode, back = { nav.popBackStack() }, providers = { nav.navigate("providers") })
            }
            composable("providers") { ProvidersScreen(model) { nav.popBackStack() } }
            composable("roleplay") {
                val workspace by remember { model.library.book(standaloneRoleplayId) }
                    .collectAsStateWithLifecycle(initialValue = null)
                Scaffold(topBar = { BackBar("文本扮演", { nav.popBackStack() }) },
                    containerColor = WriterTheme.colorScheme.background) { padding ->
                    workspace?.let { RoleplayScreen(model, it, Modifier.padding(padding).consumeWindowInsets(padding).imePadding()) }
                }
            }
            composable("book/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                val id = requireNotNull(it.arguments?.getString("id"))
                SharedWorkbench(model, id, dark) { page ->
                    when (page) {
                        WorkbenchPage.Shelf -> nav.popBackStack("shelf", false)
                        WorkbenchPage.Settings -> nav.navigate("settings")
                        WorkbenchPage.Providers -> nav.navigate("providers")
                        WorkbenchPage.Native, WorkbenchPage.Desk, WorkbenchPage.Roleplay -> {
                            val initial = when (page) {
                                WorkbenchPage.Desk -> BookPage.Desk
                                WorkbenchPage.Roleplay -> BookPage.Roleplay
                                else -> BookPage.Edit
                            }
                            nav.navigate("native-book/${Uri.encode(id)}/${initial.name}")
                        }
                    }
                }
            }
            composable("native-book/{id}/{page}", arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("page") { type = NavType.StringType }
            )) {
                val id = requireNotNull(it.arguments?.getString("id"))
                val page = BookPage.valueOf(requireNotNull(it.arguments?.getString("page")))
                BookScreen(model, id, page) { nav.popBackStack() }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
      }
      LaunchedEffect(Unit) {
          if (!resumed) {
              val last = model.resumeBook()
              if (last != null && nav.currentDestination?.route == "shelf") openBook(last)
              resumed = true
          }
      }
    }
    preview?.let { pending ->
        AlertDialog(
            onDismissRequest = model::dismissImport,
            title = { Text(pending.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${pending.chapterCount} 章 · ${pending.annotationCount} 条批注 · ${pending.foreshadowCount} 条伏笔")
                    pending.existing?.let { Text("书架上已有《${it.title}》。") }
                }
            },
            confirmButton = {
                TextButton(onClick = { model.acceptImport(false, openBook) }) {
                    Text(if (pending.existing == null) "导入并打开" else "保留副本")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = model::dismissImport) { Text("取消") }
                    if (pending.existing != null) TextButton(onClick = { model.acceptImport(true, openBook) }) {
                        Text("覆盖", color = WriterTheme.colorScheme.error)
                    }
                }
            }
        )
    }
}

@Composable
internal fun BackBar(title: String, back: () -> Unit, actions: @Composable () -> Unit = {}) {
    SmallTopAppBar(
        title = title,
        showDivider = false,
        navigationIcon = {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        },
        actions = { actions() }
    )
}

@Composable
internal fun NameDialog(
    title: String, label: String, initial: String = "",
    dismiss: () -> Unit, save: (String) -> Unit
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    OverlayInputDialog(
        show = true, value = value, onValueChange = { value = it },
        title = title, label = label,
        onDismissRequest = dismiss,
        negative = DialogButtonBarAction(text = "取消", onClick = dismiss),
        positive = DialogButtonBarAction(text = "确定", enabled = value.isNotBlank(), recommended = true,
            onClick = { save(value.trim()); dismiss() })
    )
}
