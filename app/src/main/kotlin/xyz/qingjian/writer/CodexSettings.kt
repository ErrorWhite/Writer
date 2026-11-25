package xyz.qingjian.writer

import io.github.suqi8.coui.kmp.basic.Surface
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.suqi8.coui.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
internal fun CodexSettings(model: WriterModel, current: String?) {
    val endpoint by model.studioEndpoint.collectAsStateWithLifecycle()
    val status by model.studioStatus.collectAsStateWithLifecycle()
    val cli = status?.optJSONObject("cli")
    val accounts = cli?.records("accounts").orEmpty()
    var login by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var accountName by remember { mutableStateOf<JSONObject?>(null) }
    val pending = accounts.any { it.optJSONObject("login")?.string("state") == "pending" }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val browser = LocalUriHandler.current
    LaunchedEffect(pending, lifecycle) {
        if (pending) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(2_500)
                model.refreshStudio()
            }
        }
    }
    Text("青简服务器 Codex", style = WriterTheme.typography.titleLarge)
    if (status == null) {
        OutlinedButton(onClick = { login = true }) { Text("登录青简服务器") }
    } else {
        Text(endpoint, style = WriterTheme.typography.bodySmall)
        if (status?.optBoolean("codex") == true) OutlinedButton(onClick = model::useStudio) {
            Text(if (current == STUDIO_ACCOUNT) "正在使用服务器 Codex" else "使用服务器 Codex")
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起账号" else "管理账号") }
            TextButton(onClick = model::refreshStudio) { Text("刷新") }
            TextButton(onClick = model::signOutStudio) { Text("退出服务器") }
        }
        if (expanded) {
            if (cli?.optBoolean("installed") != true) Text("青简服务器未检测到 Codex CLI。")
            else {
                accounts.forEach { account ->
                    val id = account.string("id")
                    val connected = account.optBoolean("connected")
                    val auth = account.optJSONObject("login")
                    val waiting = auth?.string("state") == "pending"
                    Surface(shape = WriterTheme.shapes.large, color = WriterTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(account.string("name"), style = WriterTheme.typography.titleMedium)
                            Text(when {
                                waiting -> "等待设备授权"
                                connected -> if (cli.string("selected") == id) "已连接 · 当前账号" else "已连接"
                                else -> "待授权"
                            })
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                TextButton(onClick = { model.studioAction("login", if (waiting) "cancel" else "login", id) }) {
                                    Text(if (waiting) "取消" else if (connected) "重新授权" else "设备授权")
                                }
                                if (connected && cli.string("selected") != id) TextButton(onClick = {
                                    model.studioAction("accounts", "select", id)
                                }) { Text("切换到此账号") }
                                if (id != "system") TextButton(onClick = { accountName = account }) { Text("改备注") }
                            }
                            if (waiting) {
                                val code = auth?.string("code").orEmpty()
                                val url = auth?.string("url").orEmpty()
                                if (code.isNotBlank()) Text(code, style = WriterTheme.typography.headlineSmall)
                                val parsed = android.net.Uri.parse(url)
                                if (parsed.scheme == "https" && parsed.host == "auth.openai.com") {
                                    TextButton(onClick = { browser.openUri(url) }) { Text("在浏览器中打开授权页") }
                                }
                            } else auth?.string("message")?.takeIf(String::isNotBlank)?.let { Text(it) }
                        }
                    }
                }
                Row {
                    TextButton(onClick = { accountName = JSONObject() }) { Text("添加账号") }
                    TextButton(onClick = { settings = true }) { Text("模型和思考等级") }
                }
            }
        }
    }
    if (login) StudioSignIn(model, endpoint) { login = false }
    accountName?.let { account ->
        val id = account.string("id")
        NameDialog(if (id.isBlank()) "添加 Codex 账号" else "修改 Codex 备注", "账号备注",
            account.string("name"), dismiss = { accountName = null }) { value ->
            model.studioAction("accounts", if (id.isBlank()) "add" else "rename", id.takeIf(String::isNotBlank), value)
        }
    }
    if (settings && cli != null) {
        var id by rememberSaveable { mutableStateOf(cli.string("model")) }
        var effort by rememberSaveable { mutableStateOf(cli.string("effort")) }
        var enabled by rememberSaveable { mutableStateOf(cli.optBoolean("enabled", true)) }
        AlertDialog(
            onDismissRequest = { settings = false }, title = { Text("服务器 Codex 模型") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(id, { id = it }, singleLine = true, label = { Text("模型 ID，留空使用服务器默认") })
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        listOf("" to "默认", "low" to "低", "medium" to "中", "high" to "高", "xhigh" to "最高")
                            .forEach { (key, label) -> FilterChip(effort == key, { effort = key }, label = label) }
                    }
                    CheckOption("启用 Codex", enabled) { enabled = it }
                }
            },
            confirmButton = { TextButton(onClick = { model.configureStudio(id, effort, enabled); settings = false }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { settings = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun StudioSignIn(model: WriterModel, endpoint: String, dismiss: () -> Unit) {
    var address by rememberSaveable { mutableStateOf(endpoint) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss, title = { Text("登录青简服务器") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(address, { address = it }, singleLine = true, label = { Text("青简服务器 HTTPS 地址") })
                OutlinedTextField(username, { username = it }, singleLine = true, label = { Text("青简用户名") })
                OutlinedTextField(password, { password = it }, singleLine = true, password = true, label = { Text("青简密码") })
            }
        },
        confirmButton = {
            TextButton(enabled = address.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                onClick = { model.signInStudio(address, username, password); dismiss() }) { Text("登录") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }
    )
}
