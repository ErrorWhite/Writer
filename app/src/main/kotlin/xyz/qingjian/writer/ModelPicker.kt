package xyz.qingjian.writer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import com.itos.coui.sheet.BottomSheet

private data class ModelChoice(val id: String, val group: String, val name: String, val model: String)

// COUI page follows Eta's AgentModelPicker: searchable provider groups, a
// provisional selection, and a fixed confirmation bar above the system inset.
@Composable
internal fun ModelPicker(model: WriterModel, modifier: Modifier = Modifier) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val plans by model.planProfiles.collectAsStateWithLifecycle()
    val server by model.studioStatus.collectAsStateWithLifecycle()
    val selected by model.activeAccount.collectAsStateWithLifecycle()
    val choices = buildList {
        accounts.orEmpty().filter { it.enabled }.forEach { account ->
            add(ModelChoice(account.id, account.protocol.label, account.name, account.model))
        }
        plans.filter { it.connected }.forEach { plan ->
            add(ModelChoice("plan:" + plan.id, "ChatGPT 套餐", plan.name, plan.model))
        }
        if (server?.optBoolean("codex") == true) {
            add(ModelChoice(STUDIO_ACCOUNT, "青简服务器", "Codex", server?.optJSONObject("cli")?.string("model").orEmpty()))
        }
    }
    val current = choices.firstOrNull { it.id == selected }
    val sheet = rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    TextButton(modifier = modifier, onClick = {
        draft = selected
        query = ""
        sheet.value = true
    }) {
        Text(current?.let { it.model.ifBlank { it.name } } ?: "选择模型", maxLines = 1)
    }
    val filtered = choices.filter { choice ->
        query.isBlank() || listOf(choice.group, choice.name, choice.model).any { it.contains(query.trim(), true) }
    }.groupBy(ModelChoice::group).entries.toList()
    BottomSheet(visible = sheet, dismissDescription = "关闭模型选择", titleContent = { Text("选择模型") },
        showDragHandle = true, scrollableContent = false) {
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(
                WindowInsets.navigationBars.exclude(WindowInsets.ime).only(WindowInsetsSides.Bottom)
            ).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            WriterSearch(query, { query = it }, "搜索模型或供应商")
            LazyColumn(Modifier.heightIn(max = 320.dp), contentPadding = PaddingValues(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (choices.isEmpty()) item {
                    EmptyState(title = "还没有可用模型", subtitle = "在设置中连接供应商或登录 ChatGPT 套餐。")
                } else if (filtered.isEmpty()) item { EmptyState(title = "没有匹配的模型") }
                items(filtered, key = { it.key }) { (group, options) ->
                    PreferenceGroup(group) {
                        for (choice in options) item(choice.id) {
                            val title = if (choice.model.isBlank()) choice.name else "${choice.name} · ${choice.model}"
                            RadioButtonPreference(title, draft == choice.id, { draft = choice.id })
                        }
                    }
                }
            }
            Button(onClick = {
                draft?.let(model::useAccount)
                sheet.value = false
            }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                enabled = choices.any { it.id == draft }) { Text("确认模型") }
        }
    }
}
