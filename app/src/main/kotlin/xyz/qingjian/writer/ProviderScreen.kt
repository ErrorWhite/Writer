package xyz.qingjian.writer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mangi.eta.data.provider.CommunityCatalog
import io.github.mangi.eta.data.provider.CommunityCatalogProvider
import com.itos.coui.layout.Group
import com.itos.coui.layout.Scaffold
import com.itos.coui.sheet.BottomSheet

@Composable
internal fun ProvidersScreen(model: WriterModel, back: () -> Unit) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val selected by model.activeAccount.collectAsStateWithLifecycle()
    val images by model.imagesEnabled.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf<ProviderAccount?>(null) }
    var showUsage by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf<CommunityCatalog?>(null) }
    var loadingCatalog by remember { mutableStateOf(false) }
    val account = edit
    if (account != null) {
        AccountDialog(model, account, accounts.orEmpty().any { it.id == account.id }) { edit = null }
        return
    }
    Scaffold(containerColor = WriterTheme.colorScheme.background, topBar = { BackBar("模型供应商", back) }) { padding ->
      Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
          .verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!accounts.isNullOrEmpty()) {
            PreferenceGroup("模型账号") {
                accounts.orEmpty().forEach { account ->
                    item(account.id) {
                        ArrowPreference(title = account.name, summary = "${account.protocol.label} · ${account.model}",
                            onClick = { edit = account },
                            startAction = {
                                RadioButton(selected == account.id, { model.useAccount(account.id) }, enabled = account.enabled)
                            },
                            endActions = { ToolbarTextButton(text = "编辑", onClick = { edit = account }) }
                        )
                    }
                }
            }
        }
        PreferenceGroup(if (accounts.isNullOrEmpty()) "连接模型" else "管理") {
            item("add") {
                ArrowPreference("添加供应商", summary = "API Key 或兼容端点", onClick = {
                    val preset = providerPresets.first()
                    edit = ProviderAccount(newId(), preset.name, preset.url, preset.protocol, "", "")
                })
            }
            item("catalog") {
                ArrowPreference(if (loadingCatalog) "正在加载模型目录" else "社区模型目录", enabled = !loadingCatalog, onClick = {
                    loadingCatalog = true
                    model.loadModelCatalog { result ->
                        loadingCatalog = false
                        result.onSuccess { catalog = it }.onFailure { model.notice(it.localizedMessage ?: "读取目录失败。") }
                    }
                })
            }
            item("usage") { ArrowPreference("使用统计", onClick = { showUsage = true }) }
        }
        ChatGptPlanSettings(model, selected)
        CodexSettings(model, selected)
        PreferenceGroup("图像") {
            item("images") { SwitchPreference(images, model::enableImages, title = "启用 AI 配图") }
        }
      }
    }
    catalog?.let { source ->
        CatalogDialog(source, dismiss = { catalog = null }) { provider, modelId ->
            edit = ProviderAccount(newId(), provider.name, provider.baseUrl, ModelProtocol.Chat, modelId, "", enabled = false)
            catalog = null
        }
    }
    if (showUsage) ModelUsageDialog(model) { showUsage = false }
}

@Composable
internal fun ModelUsageDialog(model: WriterModel, dismiss: () -> Unit) {
    val usage by model.usage.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = dismiss, title = { Text("AI 使用量") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (usage.isEmpty()) EmptyState(title = "尚无模型用量记录")
                usage.groupBy { it.day }.entries.toList().takeLast(30).asReversed().forEach { (day, rows) ->
                    Text("$day · ${rows.size} 次", style = WriterTheme.typography.titleMedium)
                    rows.groupBy { Triple(it.accountId.ifBlank { it.account }, it.intent, it.model) }.forEach { (key, entries) ->
                        fun sum(value: (UsageEntry) -> Int?): String =
                            entries.mapNotNull(value).takeIf { it.isNotEmpty() }?.sumOf(Int::toLong)?.toString() ?: "未知"
                        val account = entries.last().account
                        val heading = listOf(account, key.third, key.second).filter(String::isNotBlank).joinToString(" · ")
                        Text("$heading\n输入 ${sum(UsageEntry::input)}  输出 ${sum(UsageEntry::output)}  缓存 ${sum(UsageEntry::cached)}")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } }
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
                WriterSearch(query, { query = it }, "搜索目录")
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
    val models = remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("连接") }
    fun save() {
        try {
            providerHeaders(value.customHeaders)
        } catch (error: IllegalArgumentException) {
            status = error.localizedMessage.orEmpty()
            return
        }
        if (value.reasoning !in supportedEfforts(value)) {
            tab = "模型"
            status = "这个模型不支持所选思考档位。"
            return
        }
        model.saveAccount(value.copy(name = value.name.trim(), model = value.model.trim(), key = value.key.trim()))
        dismiss()
    }
    androidx.activity.compose.BackHandler(onBack = dismiss)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            BackBar(if (exists) account.name else "添加供应商", dismiss) {
                TextButton(enabled = value.model.isNotBlank() && value.key.isNotBlank() && value.name.isNotBlank(),
                    onClick = ::save) { Text("保存") }
            }
        }
    ) { insets ->
        Column(Modifier.padding(insets).consumeWindowInsets(insets).imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val tabs = listOf("连接", "模型")
            SegmentButton(tabs, tabs.indexOf(tab), { tab = tabs[it] }, modifier = Modifier.fillMaxWidth())
            if (tab == "连接") Group {
              Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                if (value.protocol == ModelProtocol.Vertex) {
                    Text("填写含项目、地域及 /publishers/google 的 Vertex AI 地址。")
                }
                OutlinedTextField(value.key, { value = value.copy(key = it) }, Modifier.fillMaxWidth(),
                    singleLine = true, password = true,
                    label = { Text(if (value.protocol == ModelProtocol.Vertex) "Google OAuth 访问令牌" else "API Key") })
                SwitchPreference(value.enabled, { value = value.copy(enabled = it) }, title = "启用账号")
                TextButton(enabled = !connecting && value.key.isNotBlank(), onClick = {
                    connecting = true
                    status = "连接中…"
                    model.listModels(value) { result ->
                        connecting = false
                        result.onSuccess {
                            choices = it
                            status = "连接成功 · ${it.size} 个模型"
                            models.value = it.isNotEmpty()
                        }.onFailure { status = it.localizedMessage ?: "连接失败。" }
                    }
                }) { Text("测试连接并拉取模型") }
                TextButton(onClick = { advanced = !advanced }) { Text("自定义请求头") }
                if (advanced) OutlinedTextField(value.customHeaders, { value = value.copy(customHeaders = it) },
                    Modifier.fillMaxWidth(), minLines = 2, label = { Text("每行一个 名称: 值") })
                if (exists) TextButton(onClick = { deleting = true }) {
                    Text("删除账号", color = WriterTheme.colorScheme.error)
                }
              }
            } else Group {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value.model, { value = value.copy(model = it) }, Modifier.fillMaxWidth(),
                        singleLine = true, label = { Text("模型 ID") })
                    val efforts = supportedEfforts(value)
                    if (efforts.size > 1 || value.reasoning != ReasoningEffort.Default) {
                        Text("思考档位", style = WriterTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            efforts.forEach { effort ->
                                FilterChip(value.reasoning == effort, { value = value.copy(reasoning = effort) }, effort.label)
                            }
                        }
                    }
                    TextButton(enabled = !connecting && value.key.isNotBlank(), onClick = {
                        connecting = true
                        model.listModels(value) { result ->
                            connecting = false
                            result.onSuccess {
                                choices = it
                                models.value = it.isNotEmpty()
                                status = if (it.isEmpty()) "未返回模型，可直接填写 ID。" else ""
                            }.onFailure { status = it.localizedMessage ?: "读取模型失败。" }
                        }
                    }) { Text(if (connecting) "正在获取…" else "从供应商获取模型") }
                }
            }
            if (status.isNotBlank()) Text(status, style = WriterTheme.typography.bodySmall)
        }
    }
    BottomSheet(visible = models, dismissDescription = "关闭模型选择", titleContent = { Text("选择模型") },
        showDragHandle = true, scrollableContent = false) {
            var query by remember { mutableStateOf("") }
            Column(Modifier.padding(20.dp)) {
                WriterSearch(query, { query = it }, "搜索模型")
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    choices.asSequence().filter { it.contains(query, ignoreCase = true) }.take(60).forEach { choice ->
                        TextButton(onClick = { value = value.copy(model = choice); models.value = false }) { Text(choice) }
                    }
                }
            }
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false }, title = { Text("删除模型账号？") },
        confirmButton = { TextButton(onClick = { model.removeAccount(account.id); dismiss() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } }
    )
}
