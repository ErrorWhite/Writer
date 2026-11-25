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
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardListGroup
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.overlay.OverlayBottomSheet
import io.github.suqi8.coui.kmp.preference.RadioButtonLocation
import io.github.suqi8.coui.kmp.preference.RadioButtonPreference

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
    var open by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    TextButton(modifier = modifier, onClick = {
        draft = selected
        query = ""
        open = true
    }) {
        Text(current?.model?.ifBlank { current.name } ?: "选择模型", maxLines = 1)
    }
    if (!open) return
    val filtered = choices.filter { choice ->
        query.isBlank() || listOf(choice.group, choice.name, choice.model).any { it.contains(query.trim(), true) }
    }.groupBy(ModelChoice::group).entries.toList()
    OverlayBottomSheet(show = true, title = "选择模型", onDismissRequest = { open = false }, showDragHandle = true) {
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(
                WindowInsets.navigationBars.exclude(WindowInsets.ime).only(WindowInsetsSides.Bottom)
            ).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("搜索模型或供应商") })
            LazyColumn(Modifier.heightIn(max = 320.dp), contentPadding = PaddingValues(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (choices.isEmpty()) item { Text("在设置中连接供应商或登录 ChatGPT 套餐。") }
                else if (filtered.isEmpty()) item { Text("没有匹配的模型") }
                items(filtered, key = { it.key }) { (group, options) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(group, style = WriterTheme.typography.labelLarge)
                        Card {
                            CardListGroup {
                                for (choice in options) item(choice.id) {
                                    val title = if (choice.model.isBlank()) choice.name else "${choice.name} · ${choice.model}"
                                    RadioButtonPreference(title, draft == choice.id, { draft = choice.id },
                                        radioButtonLocation = RadioButtonLocation.End)
                                }
                            }
                        }
                    }
                }
            }
            Button(onClick = {
                draft?.let(model::useAccount)
                open = false
            }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                enabled = choices.any { it.id == draft }) { Text("确认模型") }
        }
    }
}
