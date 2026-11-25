package xyz.qingjian.writer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.suqi8.coui.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mangi.eta.data.provider.CommunityCatalog
import io.github.mangi.eta.data.provider.CommunityCatalogProvider
import io.github.suqi8.coui.kmp.basic.RadioButton
import io.github.suqi8.coui.kmp.basic.Switch

@Composable
internal fun ProviderSettings(model: WriterModel) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val selected by model.activeAccount.collectAsStateWithLifecycle()
    val usage by model.usage.collectAsStateWithLifecycle()
    val images by model.imagesEnabled.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf<ProviderAccount?>(null) }
    var showUsage by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf<CommunityCatalog?>(null) }
    var loadingCatalog by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("模型账号", style = WriterTheme.typography.titleLarge)
        accounts.orEmpty().forEach { account ->
            Surface(shape = WriterTheme.shapes.large, color = WriterTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == account.id, { model.useAccount(account.id) }, enabled = account.enabled)
                    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                        Text(account.name, style = WriterTheme.typography.titleMedium)
                        Text(account.model, style = WriterTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { edit = account }) { Text("编辑") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                val preset = providerPresets.first()
                edit = ProviderAccount(newId(), preset.name, preset.url, preset.protocol, "", "")
            }) { Text("添加账号") }
            OutlinedButton(onClick = { showUsage = true }) { Text("使用统计") }
        }
        TextButton(enabled = !loadingCatalog, onClick = {
            loadingCatalog = true
            model.loadModelCatalog { result ->
                loadingCatalog = false
                result.onSuccess { catalog = it }.onFailure { model.notice(it.localizedMessage ?: "读取目录失败。") }
            }
        }) { Text(if (loadingCatalog) "读取模型目录…" else "浏览社区模型目录") }
        ChatGptPlanSettings(model, selected)
        CodexSettings(model, selected)
        CheckOption("启用 AI 配图", images, model::enableImages)
    }
    edit?.let { account ->
        AccountDialog(model, account, accounts.orEmpty().any { it.id == account.id }) { edit = null }
    }
    catalog?.let { source ->
        CatalogDialog(source, dismiss = { catalog = null }) { provider, modelId ->
            edit = ProviderAccount(newId(), provider.name, provider.baseUrl, ModelProtocol.Chat, modelId, "", enabled = false)
            catalog = null
        }
    }
    if (showUsage) AlertDialog(
        onDismissRequest = { showUsage = false }, title = { Text("使用统计") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (usage.isEmpty()) Text("尚无模型用量记录")
                usage.groupBy { it.day }.entries.toList().takeLast(30).asReversed().forEach { (day, rows) ->
                    Text("$day · ${rows.size} 次", style = WriterTheme.typography.titleMedium)
                    rows.groupBy { it.account to it.intent }.forEach { (key, entries) ->
                        fun sum(value: (UsageEntry) -> Int?): String =
                            entries.mapNotNull(value).takeIf { it.isNotEmpty() }?.sum()?.toString() ?: "未知"
                        Text("${key.first} · ${key.second}\n输入 ${sum(UsageEntry::input)}  输出 ${sum(UsageEntry::output)}  缓存 ${sum(UsageEntry::cached)}")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showUsage = false }) { Text("关闭") } }
    )
}

@Composable
private fun CatalogDialog(
    catalog: CommunityCatalog, dismiss: () -> Unit, selected: (CommunityCatalogProvider, String) -> Unit
) {
    var provider by remember { mutableStateOf<CommunityCatalogProvider?>(null) }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss, title = { Text(provider?.name ?: "社区模型目录") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("搜索") })
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val source = provider
                    if (source == null) {
                        catalog.providers.filter { it.name.contains(query, ignoreCase = true) || it.id.contains(query, true) }
                            .take(60).forEach { item ->
                                TextButton(onClick = { provider = item; query = "" }) { Text(item.name) }
                            }
                    } else {
                        source.models.filter { it.displayName.contains(query, true) || it.modelId.contains(query, true) }
                            .take(60).forEach { item ->
                                TextButton(onClick = { selected(source, item.modelId) }) { Text(item.displayName) }
                            }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (provider != null) { provider = null; query = "" } else dismiss() }) {
            Text(if (provider != null) "返回供应商" else "关闭")
        } }
    )
}

@Composable
private fun AccountDialog(model: WriterModel, account: ProviderAccount, exists: Boolean, dismiss: () -> Unit) {
    var value by remember(account.id) { mutableStateOf(account) }
    var presets by remember { mutableStateOf(false) }
    var protocols by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<String>>(emptyList()) }
    var models by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (exists) "编辑模型账号" else "添加模型账号") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!exists) {
                    TextButton(onClick = { presets = true }) { Text("选择供应商预设") }
                    DropdownMenu(presets, { presets = false }) {
                        providerPresets.forEach { preset ->
                            DropdownMenuItem(preset.name, onClick = {
                                value = value.copy(name = preset.name, endpoint = preset.url, protocol = preset.protocol, model = "")
                                presets = false
                            })
                        }
                    }
                }
                OutlinedTextField(value.name, { value = value.copy(name = it) }, Modifier.fillMaxWidth(),
                    singleLine = true, label = { Text("账号备注") })
                TextButton(onClick = { protocols = true }) { Text(value.protocol.label) }
                DropdownMenu(protocols, { protocols = false }) {
                    ModelProtocol.entries.forEach { protocol ->
                        DropdownMenuItem(protocol.label, onClick = {
                            value = value.copy(protocol = protocol)
                            protocols = false
                        })
                    }
                }
                OutlinedTextField(value.endpoint, { value = value.copy(endpoint = it) }, Modifier.fillMaxWidth(),
                    singleLine = true, label = { Text("HTTPS API 地址") })
                OutlinedTextField(value.key, { value = value.copy(key = it) }, Modifier.fillMaxWidth(),
                    singleLine = true, password = true, label = { Text("API Key") })
                OutlinedTextField(value.model, { value = value.copy(model = it) }, Modifier.fillMaxWidth(),
                    singleLine = true, label = { Text("模型 ID") })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("启用账号", Modifier.weight(1f))
                    Switch(checked = value.enabled, onCheckedChange = { value = value.copy(enabled = it) })
                }
                TextButton(enabled = !connecting && value.key.isNotBlank(), onClick = {
                    connecting = true
                    status = "连接中…"
                    model.listModels(value) { result ->
                        connecting = false
                        result.onSuccess {
                            choices = it
                            status = "连接成功 · ${it.size} 个模型"
                            models = it.isNotEmpty()
                        }.onFailure { status = it.localizedMessage ?: "连接失败。" }
                    }
                }) { Text("测试连接并拉取模型") }
                if (status.isNotEmpty()) Text(status)
                if (exists) TextButton(onClick = { deleting = true }) {
                    Text("删除账号", color = WriterTheme.colorScheme.error)
                }
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } },
        confirmButton = {
            TextButton(enabled = value.model.isNotBlank() && value.key.isNotBlank() && value.name.isNotBlank(), onClick = {
                model.saveAccount(value.copy(name = value.name.trim(), model = value.model.trim(), key = value.key.trim()))
                dismiss()
            }) { Text("保存") }
        }
    )
    if (models) AlertDialog(
        onDismissRequest = { models = false }, title = { Text("选择模型") },
        text = {
            var query by remember { mutableStateOf("") }
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("搜索模型") })
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    choices.asSequence().filter { it.contains(query, ignoreCase = true) }.take(60).forEach { choice ->
                        TextButton(onClick = { value = value.copy(model = choice); models = false }) { Text(choice) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { models = false }) { Text("关闭") } }
    )
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false }, title = { Text("删除模型账号？") },
        confirmButton = { TextButton(onClick = { model.removeAccount(account.id); dismiss() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } }
    )
}
