package xyz.qingjian.writer

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.itos.coui.layout.Scaffold
import androidx.compose.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream

private const val workbenchOrigin = "https://appassets.androidplatform.net"
private const val workbenchPath = "/assets/workbench/index.html"
private const val workbenchBridge = "QingjianWorkbench"

internal enum class WorkbenchPage {
    Shelf, Settings, Providers, Native, Desk, Roleplay
}

@Composable
private fun WorkbenchLoading(padding: PaddingValues, error: String?, recoveryOnly: Boolean, openNative: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(error ?: if (recoveryOnly) "正在恢复旧草稿…" else "正在打开书稿…",
                color = WriterTheme.colorScheme.onBackground)
            if (error != null) TextButton(onClick = openNative) {
                Text(if (recoveryOnly) "返回写作" else "用原生编辑器打开")
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SharedWorkbench(
    model: WriterModel,
    bookId: String,
    dark: Boolean,
    recoveryOnly: Boolean = false,
    navigate: (WorkbenchPage) -> Unit
) {
    val pageUrl = workbenchOrigin + workbenchPath + if (recoveryOnly) "?recover=1" else ""
    val scope = rememberCoroutineScope()
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val account by model.activeAccount.collectAsStateWithLifecycle()
    val providers by model.accounts.collectAsStateWithLifecycle()
    val profiles by model.planProfiles.collectAsStateWithLifecycle()
    val studio by model.studioStatus.collectAsStateWithLifecycle()
    val background = WriterTheme.colorScheme.background.toArgb()
    var view by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var exportFormat by rememberSaveable { mutableStateOf<String?>(null) }
    var exportChapter by rememberSaveable { mutableStateOf<String?>(null) }
    var fileReply by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileReply?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileReply = null
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val format = exportFormat?.let(ExportFormat::valueOf)
        val uri = result.data?.data
        if (format != null && uri != null) model.writeExport(uri, bookId, format, exportChapter)
        exportFormat = null
        exportChapter = null
    }
    val supported = remember { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }
    LaunchedEffect(view, ready, failed) {
        if (view != null && !ready && failed == null) {
            delay(15_000)
            failed = "写作工作区启动超时。"
        }
    }
    LaunchedEffect(dark, ready) {
        if (ready) view?.evaluateJavascript("window.qingjianTheme?.($dark)", null)
    }
    LaunchedEffect(keyboard, ready) {
        if (ready) view?.evaluateJavascript("window.qingjianKeyboard?.($keyboard)", null)
    }
    LaunchedEffect(ready, account, providers, profiles, studio) {
        val configured = model.workbenchModelReady(account)
        if (ready) view?.evaluateJavascript("window.qingjianModels?.($configured)", null)
    }
    BackHandler(enabled = !keyboard) {
        if (!recoveryOnly && ready && failed == null) {
            view?.evaluateJavascript("window.qingjianBack?.()", null)
        } else navigate(WorkbenchPage.Shelf)
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        if (supported && failed == null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val loader = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                        .build()
                    WebView(context).apply {
                        if (recoveryOnly) {
                            alpha = 0f
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        }
                        val requests = mutableMapOf<String, Job>()
                        setBackgroundColor(background)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        webChromeClient = object : WebChromeClient() {
                            override fun onShowFileChooser(
                                webView: WebView, callback: ValueCallback<Array<Uri>>, parameters: FileChooserParams
                            ): Boolean {
                                if (recoveryOnly) return false
                                fileReply?.onReceiveValue(null)
                                fileReply = callback
                                try {
                                    attachmentPicker.launch(parameters.createIntent())
                                } catch (_: android.content.ActivityNotFoundException) {
                                    callback.onReceiveValue(null)
                                    fileReply = null
                                }
                                return true
                            }
                        }
                        webViewClient = object : WebViewClientCompat() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                !request.isForMainFrame || request.url.toString().substringBefore('#') != pageUrl

                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                                val url = request.url
                                if (url.scheme == "https" && url.host == "appassets.androidplatform.net" &&
                                    url.path.orEmpty().startsWith("/assets/workbench/") && request.method == "GET") {
                                    if (url.path == "/assets/workbench/android-serif.otf") {
                                        return workbenchTypeface()
                                    }
                                    loader.shouldInterceptRequest(url)?.let { return it }
                                }
                                return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(),
                                    ByteArrayInputStream(byteArrayOf()))
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceErrorCompat) {
                                if (request.isForMainFrame) failed = "无法加载写作工作区。"
                            }

                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                failed = "写作工作区已关闭。重新打开作品可恢复草稿。"
                                return true
                            }
                        }
                        WebViewCompat.addWebMessageListener(this, workbenchBridge, setOf(workbenchOrigin)) {
                                _, message, _, mainFrame, reply ->
                            if (!mainFrame) return@addWebMessageListener
                            val input = runCatching { JSONObject(message.data ?: "") }.getOrNull()
                                ?: return@addWebMessageListener
                            val operation = input.string("operation")
                            if (recoveryOnly && operation !in setOf("book", "save", "recovered", "failure", "cancel")) {
                                return@addWebMessageListener
                            }
                            if (operation == "cancel") {
                                requests.remove(input.string("id"))?.cancel()
                                return@addWebMessageListener
                            }
                            val requestId = input.string("id")
                            if (requestId.isBlank() || requests.containsKey(requestId)) return@addWebMessageListener
                            val job = scope.launch(start = CoroutineStart.LAZY) {
                                val response = JSONObject().put("id", requestId)
                                try {
                                    val argument = input.optJSONObject("argument") ?: JSONObject()
                                    val result = when (operation) {
                                        "book" -> model.workbenchBook(bookId).put("dark", dark)
                                            .put("viewportHeight", viewportHeight())
                                        "save" -> model.saveWorkbenchBook(bookId, argument.getString("revision"),
                                            argument.getString("snapshot"))
                                        "position" -> model.workbenchCursor(bookId, argument.getString("chapter"))
                                        "savePosition" -> model.saveWorkbenchPosition(bookId, argument.getString("chapter"),
                                            argument.getJSONObject("position")).let { JSONObject() }
                                        "export" -> {
                                            val format = when (argument.getString("format")) {
                                                "chapter" -> ExportFormat.Chapter
                                                "book-json" -> ExportFormat.Json
                                                "book-md" -> ExportFormat.Markdown
                                                "book-txt" -> ExportFormat.Text
                                                "book-epub" -> ExportFormat.Epub
                                                else -> error("不支持的导出格式。")
                                            }
                                            exportFormat = format.name
                                            exportChapter = argument.string("chapter").takeIf { format == ExportFormat.Chapter }
                                            val title = argument.string("title").map { if (it in "/\\:*?\"<>|") '_' else it }
                                                .joinToString("").take(80).ifBlank { "青简书稿" }
                                            exportLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                                addCategory(Intent.CATEGORY_OPENABLE)
                                                type = format.mime
                                                putExtra(Intent.EXTRA_TITLE, "$title.${format.extension}")
                                            })
                                            JSONObject()
                                        }
                                        "rewrite" -> model.rewriteInWorkbench(argument) { type, text ->
                                            scope.launch {
                                                if (view === this@apply && requests.containsKey(requestId)) {
                                                    reply.postMessage(JSONObject().put("id", requestId)
                                                        .put("event", type).put("text", text).toString())
                                                }
                                            }
                                        }
                                        "versions" -> model.workbenchVersions(bookId, argument.string("chapter"),
                                            argument.string("version").toLongOrNull(), argument.optBoolean("create"))
                                        "usage" -> model.workbenchUsage()
                                        "ready" -> JSONObject().also {
                                            ready = true
                                            updateWorkbenchHeight()
                                        }
                                        "recovered" -> {
                                            check(recoveryOnly)
                                            model.notice(when {
                                                argument.optBoolean("conflict") -> "旧草稿已保存为书架中的冲突副本。"
                                                argument.optBoolean("replayed") -> "已恢复旧工作区草稿。"
                                                else -> "没有待恢复的旧草稿。"
                                            })
                                            reply.postMessage(response.put("result", JSONObject()).toString())
                                            navigate(WorkbenchPage.Native)
                                            return@launch
                                        }
                                        "failure" -> JSONObject().also {
                                            failed = if (recoveryOnly) "无法恢复旧草稿。返回写作后可重试。" else "无法打开写作工作区。"
                                        }
                                        "navigate" -> {
                                            val page = WorkbenchPage.valueOf(argument.getString("page"))
                                            reply.postMessage(response.put("result", JSONObject()).toString())
                                            navigate(page)
                                            return@launch
                                        }
                                        else -> error("写作工作区操作不可用。")
                                    }
                                    response.put("result", result)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    response.put("error", error.localizedMessage ?: "操作失败。")
                                } finally {
                                    requests.remove(requestId)
                                }
                                if (view === this@apply) reply.postMessage(response.toString())
                            }
                            requests[requestId] = job
                            job.start()
                        }
                        addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                            if (ready && bottom - top != oldBottom - oldTop) updateWorkbenchHeight()
                        }
                        view = this
                        loadUrl(pageUrl)
                    }
                },
                onRelease = { released ->
                    fileReply?.onReceiveValue(null)
                    fileReply = null
                    if (view === released) view = null
                    ready = false
                    WebViewCompat.removeWebMessageListener(released, workbenchBridge)
                    released.stopLoading()
                    released.destroy()
                }
            )
        }
        if (!ready || failed != null) {
            Scaffold(
                containerColor = WriterTheme.colorScheme.background,
                topBar = { BackBar(if (recoveryOnly) "恢复旧草稿" else "写作", { navigate(WorkbenchPage.Shelf) }) }
            ) { padding ->
                WorkbenchLoading(padding, failed ?: if (supported) null else "此设备的 WebView 需要更新。", recoveryOnly) {
                    navigate(WorkbenchPage.Native)
                }
            }
        }
    }
}

private fun WebView.viewportHeight(): Int = (height / resources.displayMetrics.density).toInt()

// Read the bundled OTF file directly so Compose and the WebView share the same APK resource.
@SuppressLint("ResourceType")
private fun WebView.workbenchTypeface() =
    WebResourceResponse("font/otf", null, resources.openRawResource(R.font.source_han_serif_sc))

private fun WebView.updateWorkbenchHeight() {
    val availableHeight = viewportHeight()
    if (availableHeight > 0) {
        evaluateJavascript("document.documentElement.style.setProperty('--android-workbench-height','${availableHeight}px')", null)
    }
}
