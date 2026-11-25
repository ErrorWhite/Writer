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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.github.suqi8.coui.kmp.basic.TopAppBar
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.darkColorScheme as couiDark
import io.github.suqi8.coui.kmp.theme.lightColorScheme as couiLight

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
            QingjianTheme(dark) { WriterApp(model, mode) }
        }
    }
}

private val serif = FontFamily(Font(R.font.source_han_serif_sc, FontWeight.SemiBold))
private val type = Typography().let {
    it.copy(
        headlineLarge = it.headlineLarge.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
        headlineSmall = it.headlineSmall.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
        titleLarge = it.titleLarge.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold)
    )
}
private val lightColors = lightColorScheme(
    primary = Color(0xFF3D62DF), onPrimary = Color.White,
    primaryContainer = Color(0xFFE4EAFF), onPrimaryContainer = Color(0xFF172F79),
    secondaryContainer = Color(0xFFE5EAFB), onSecondaryContainer = Color(0xFF29375A),
    background = Color(0xFFF1F3F8), onBackground = Color(0xFF242D42),
    surface = Color(0xFFFCFCFF), onSurface = Color(0xFF242D42),
    surfaceContainer = Color(0xFFE9EDF7), surfaceContainerHigh = Color(0xFFE3E8F5),
    onSurfaceVariant = Color(0xFF49546D), outline = Color(0xFF737E98), outlineVariant = Color(0xFFD7DEEE)
)
private val darkColors = darkColorScheme(
    primary = Color(0xFFB8C6FF), onPrimary = Color(0xFF122A75),
    primaryContainer = Color(0xFF273B77), onPrimaryContainer = Color(0xFFE1E8FF),
    secondaryContainer = Color(0xFF2E3A58), onSecondaryContainer = Color(0xFFDDE5FF),
    background = Color(0xFF141923), onBackground = Color(0xFFE9EDF9),
    surface = Color(0xFF1B2230), onSurface = Color(0xFFE9EDF9),
    surfaceContainer = Color(0xFF222B3D), surfaceContainerHigh = Color(0xFF293246),
    onSurfaceVariant = Color(0xFFBCC6DD), outline = Color(0xFF8F9BB6), outlineVariant = Color(0xFF3B475E)
)

@Composable
private fun QingjianTheme(dark: Boolean, content: @Composable () -> Unit) {
    val palette = if (dark) darkColors else lightColors
    val base = if (dark) couiDark() else couiLight()
    val colors = remember(dark) {
        base.copy(
            primary = palette.primary, onPrimary = palette.onPrimary, background = palette.background,
            backgroundWithCard = palette.background, surface = palette.surface,
            labelPrimary = palette.onSurface, labelSecondary = palette.onSurfaceVariant,
            divider = palette.outlineVariant
        )
    }
    MaterialTheme(colorScheme = palette, typography = type) { COUITheme(colors = colors, content = content) }
}

@Composable
private fun WriterApp(model: WriterModel, mode: ThemeMode) {
    val nav = rememberNavController()
    val notice by model.notice.collectAsStateWithLifecycle()
    val preview by model.importPreview.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it); model.notice(null) }
    }
    val openBook: (String) -> Unit = { id -> nav.navigate("book/${Uri.encode(id)}") { launchSingleTop = true } }

    Box(Modifier.fillMaxSize()) {
        NavHost(nav, startDestination = "shelf") {
            composable("shelf") {
                ShelfScreen(model, openBook, { nav.navigate("settings") }) {
                    model.perform { standaloneRoleplay(); nav.navigate("roleplay") }
                }
            }
            composable("settings") { SettingsScreen(model, mode) { nav.popBackStack() } }
            composable("roleplay") {
                val workspace by remember { model.library.book(standaloneRoleplayId) }
                    .collectAsStateWithLifecycle(initialValue = null)
                Scaffold(topBar = { BackBar("文本扮演", { nav.popBackStack() }) },
                    containerColor = MaterialTheme.colorScheme.background) { padding ->
                    workspace?.let { RoleplayScreen(model, it, Modifier.padding(padding).consumeWindowInsets(padding).imePadding()) }
                }
            }
            composable("book/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                BookScreen(model, requireNotNull(it.arguments?.getString("id"))) { nav.popBackStack() }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
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
                        Text("覆盖", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackBar(title: String, back: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = title,
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
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(label) })
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { save(value.trim()); dismiss() }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }
    )
}
