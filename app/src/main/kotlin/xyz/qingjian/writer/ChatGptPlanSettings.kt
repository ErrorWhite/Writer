package xyz.qingjian.writer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults

private const val usageUrl = "https://chatgpt.com/settings/usage"

@Composable
internal fun ChatGptPlanSettings(model: WriterModel, active: String?) {
    val profiles by model.planProfiles.collectAsStateWithLifecycle()
    val busy by model.planLoginBusy.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    var select by remember { mutableStateOf<PlanProfile?>(null) }
    var disconnect by remember { mutableStateOf<PlanProfile?>(null) }
    var welcome by rememberSaveable { mutableStateOf(false) }

    if (profiles.isEmpty()) PreferenceCategory("ChatGPT 套餐")
    Button(enabled = !busy, onClick = {
        model.signInPlan(browser = uri::openUri) {
            select = it
            if (profiles.isEmpty()) welcome = true
        }
    }, colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White)) {
        Image(painterResource(R.drawable.chatgpt_sign_in), null, Modifier.size(21.dp))
        Text("Continue with ChatGPT", Modifier.padding(start = 9.dp), color = Color.White)
    }
    if (busy) {
        Text("在系统浏览器完成授权后返回青简。")
        TextButton(onClick = model::cancelPlanLogin) { Text("取消登录") }
    }
    if (profiles.isNotEmpty()) PreferenceGroup("ChatGPT 套餐") {
        profiles.forEach { profile ->
            val current = active == "plan:${profile.id}"
            item(profile.id) {
                ArrowPreference(
                    title = profile.name,
                    summary = when {
                        !profile.connected -> "需要授权"
                        else -> profile.model.ifBlank { "已授权 · 请选择模型" }
                    },
                    onClick = {
                        if (profile.connected) select = profile
                        else model.signInPlan(profile.id, uri::openUri) { select = it }
                    },
                    enabled = !busy,
                    startAction = {
                        RadioButton(current, {
                            if (profile.model.isBlank()) select = profile else model.choosePlan(profile, profile.model)
                        }, enabled = profile.connected && !busy)
                    },
                    endActions = {
                        ToolbarTextButton(text = "管理", enabled = !busy, onClick = { disconnect = profile })
                    }
                )
            }
        }
        item("usage") { ArrowPreference("管理 ChatGPT 用量", onClick = { uri.openUri(usageUrl) }) }
    }
    select?.let { profile -> PlanModelPicker(model, profile) { select = null } }
    if (welcome) AlertDialog(
        onDismissRequest = { welcome = false }, title = { Text("ChatGPT 套餐已连接") },
        text = { Text("选择模型后，青简的合资格请求会使用此账号的 ChatGPT 套餐。你可以在 ChatGPT 设置里查看和管理用量。") },
        confirmButton = { TextButton(onClick = { welcome = false }) { Text("知道了") } }
    )
    disconnect?.let { profile ->
        AlertDialog(
            onDismissRequest = { disconnect = null }, title = { Text(profile.name) },
            text = { Text("退出会结束这个连接的可续期会话。保留账号后，下次授权会复用青简在该账号的注册。") },
            confirmButton = {
                TextButton(onClick = { model.disconnectPlan(profile, remove = false); disconnect = null }) { Text("退出账号") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { disconnect = null }) { Text("取消") }
                    TextButton(onClick = { model.disconnectPlan(profile, remove = true); disconnect = null }) { Text("移除账号") }
                }
            }
        )
    }
}

@Composable
internal fun CurrentPlanNotice(model: WriterModel) {
    val active by model.activeAccount.collectAsStateWithLifecycle()
    if (active?.startsWith("plan:") != true) return
    val uri = LocalUriHandler.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("正在使用 ChatGPT 套餐", style = WriterTheme.typography.labelMedium)
        TextButton(onClick = { uri.openUri(usageUrl) }) { Text("管理用量") }
    }
}

@Composable
private fun PlanModelPicker(model: WriterModel, profile: PlanProfile, dismiss: () -> Unit) {
    var entries by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var selection by rememberSaveable { mutableStateOf(profile.model) }
    LaunchedEffect(profile.id) {
        model.planModels(profile.id) { result ->
            result.onSuccess { entries = it }.onFailure { error = it.localizedMessage ?: "读取模型列表失败。" }
        }
    }
    AlertDialog(
        onDismissRequest = dismiss, title = { Text("选择套餐模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WriterSearch(query, { query = it }, "搜索套餐可用模型")
                val options = entries
                when {
                    error != null -> Text(error.orEmpty(), color = WriterTheme.colorScheme.error)
                    options == null -> Text("正在读取模型列表…")
                    options.isEmpty() -> Text("当前套餐未列出可用模型。")
                    else -> LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(options.filter { it.contains(query, ignoreCase = true) }.take(100), key = { it }) { name ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selection == name, { selection = name })
                                Text(name, Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = entries?.contains(selection) == true, onClick = {
                model.choosePlan(profile, selection)
                dismiss()
            }) { Text("使用此模型") }
        }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }
    )
}
