package xyz.qingjian.writer

import androidx.compose.material3.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun ResourcesScreen(model: WriterModel, book: BookRow) {
    val payload = remember(book.payload) { JSONObject(book.payload) }
    val assets = payload.optJSONObject("creative")?.records("assets").orEmpty()
    val skills = skillRecords(payload)
    val enabled = payload.optJSONObject("skills")?.strings("enabled").orEmpty().toSet()
    var asset by remember { mutableStateOf<JSONObject?>(null) }
    var skill by remember { mutableStateOf<JSONObject?>(null) }
    var sample by rememberSaveable { mutableStateOf(false) }
    var imageOpen by rememberSaveable { mutableStateOf(false) }
    val images by model.imagesEnabled.collectAsStateWithLifecycle()
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val active by model.activeAccount.collectAsStateWithLifecycle()
    val assetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importAsset(book.id, uri)
    }
    val skillPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importSkill(book.id, uri)
    }
    Text("资料附件", style = WriterTheme.typography.titleLarge)
    OutlinedButton(onClick = {
        assetPicker.launch(arrayOf("text/plain", "text/markdown", "image/png", "image/jpeg", "image/webp", "image/gif"))
    }) { Text("添加图片或文档") }
    if (images && accounts.orEmpty().any { it.id == active && it.enabled && it.protocol == ModelProtocol.Responses }) {
        OutlinedButton(onClick = { imageOpen = true }) { Text("生成配图") }
    }
    assets.sortedByDescending { it.optLong("updatedAt") }.forEach { item ->
        Surface(onClick = { asset = item }, shape = WriterTheme.shapes.large, color = WriterTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(item.string("title"), style = WriterTheme.typography.titleMedium)
                Text(item.string("group").ifBlank { if (item.string("type") == "image") "图片" else "文档" })
            }
        }
    }
    Text("写作技能", style = WriterTheme.typography.titleLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            skillPicker.launch(arrayOf("application/zip", "application/json", "text/plain", "text/markdown", "application/octet-stream"))
        }) { Text("导入技能包") }
        TextButton(onClick = { sample = true }) { Text("文风样稿") }
    }
    skills.forEach { item ->
        val id = item.string("id")
        Row {
            CheckOption(item.string("name"), id in enabled) { checked ->
                model.perform {
                    mutateBook(book.id) { data ->
                        val settings = data.obj("skills")
                        val existing = settings.strings("enabled").toMutableSet()
                        if (checked) existing.add(id) else existing.remove(id)
                        settings.put("enabled", JSONArray(existing))
                    }
                }
            }
            TextButton(onClick = { skill = item }) { Text("详情") }
        }
    }
    if (sample) {
        var text by rememberSaveable { mutableStateOf(payload.optJSONObject("skills")?.string("styleSample").orEmpty()) }
        AlertDialog(onDismissRequest = { sample = false }, title = { Text("文风样稿") },
            text = { OutlinedTextField(text, { text = it }, minLines = 5, label = { Text("粘贴自己的写作样稿") }) },
            confirmButton = { TextButton(onClick = {
                model.perform { mutateBook(book.id) { it.obj("skills").put("styleSample", text) }; sample = false }
            }) { Text("保存") } }, dismissButton = { TextButton(onClick = { sample = false }) { Text("取消") } })
    }
    asset?.let { item -> AssetDialog(model, book, item) { asset = null } }
    if (imageOpen) ImageDialog(model, book.id) { imageOpen = false }
    skill?.let { item ->
        val script = remember(item) { declaredSkillScript(item) }
        var approved by remember(item) { mutableStateOf(model.isSkillApproved(book.id, item)) }
        var grant by rememberSaveable { mutableStateOf(false) }
        var testText by rememberSaveable { mutableStateOf("") }
        var result by remember { mutableStateOf("") }
        val server by model.studioStatus.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { skill = null }, title = { Text(item.string("name")) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.string("instruction"))
                item.records("files").forEach { Text(it.string("path"), style = WriterTheme.typography.labelLarge) }
                if (script != null) {
                    Text("脚本入口 · ${script.string("entry")}")
                    if (server != null) {
                        TextButton(onClick = {
                            if (approved) model.approveSkillScript(book.id, item, false) { approved = false }
                            else grant = true
                        }) { Text(if (approved) "撤销脚本授权" else "授权此版本脚本") }
                        if (approved) {
                            OutlinedTextField(testText, { testText = it }, label = { Text("试跑样本文字") })
                            TextButton(onClick = { model.testSkill(book.id, item, testText) { result = it } }) { Text("在服务器试跑") }
                            if (result.isNotBlank()) Text(result)
                        }
                    } else Text("登录青简服务器后可在沙箱中试跑脚本。")
                }
            } }, confirmButton = { TextButton(onClick = { skill = null }) { Text("关闭") } }
        )
        if (grant && script != null) AlertDialog(
            onDismissRequest = { grant = false }, title = { Text("授权运行此版本技能脚本？") },
            text = { Text("脚本会发送到已登录的青简服务器，在 QuickJS 沙箱中运行。生成时仅附加文本输入，以及技能声明的文本工具。") },
            confirmButton = { TextButton(onClick = {
                model.approveSkillScript(book.id, item, true) { approved = true; grant = false }
            }) { Text("授权") } },
            dismissButton = { TextButton(onClick = { grant = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun ImageDialog(model: WriterModel, bookId: String, dismiss: () -> Unit) {
    val generated by model.imageCandidate.collectAsStateWithLifecycle()
    val candidate = generated?.takeIf { it.bookId == bookId }
    var prompt by rememberSaveable { mutableStateOf(candidate?.prompt.orEmpty()) }
    var modelId by rememberSaveable { mutableStateOf("gpt-image-1") }
    AlertDialog(
        onDismissRequest = dismiss, title = { Text("生成配图") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(modelId, { modelId = it }, singleLine = true, label = { Text("图像模型") })
                OutlinedTextField(prompt, { prompt = it }, minLines = 3, label = { Text("画面描述") })
                if (candidate?.busy == true) Text("正在生成图片…")
                candidate?.bytes?.let { AsyncImage(it, "配图候选", Modifier.fillMaxWidth().heightIn(max = 240.dp)) }
                candidate?.error?.let { Text(it, color = WriterTheme.colorScheme.error) }
                if (candidate?.bytes != null) Row {
                    TextButton(onClick = { model.applyImage(bookId, true) }) { Text("采用为封面") }
                    TextButton(onClick = { model.applyImage(bookId, false) }) { Text("保存到附件") }
                }
            }
        },
        confirmButton = {
            if (candidate?.busy == true) TextButton(onClick = model::cancelImage) { Text("停止") }
            else TextButton(enabled = prompt.isNotBlank() && prompt.length <= 2500 && modelId.isNotBlank(),
                onClick = { model.generateImage(bookId, modelId.trim(), prompt.trim()) }) { Text("生成") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("关闭") } }
    )
}

@Composable
private fun AssetDialog(model: WriterModel, book: BookRow, item: JSONObject, dismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(item.string("title")) }
    var group by rememberSaveable { mutableStateOf(item.string("group")) }
    var summary by rememberSaveable { mutableStateOf(item.string("summary")) }
    var context by rememberSaveable { mutableStateOf(item.optBoolean("context")) }
    var remove by rememberSaveable { mutableStateOf(false) }
    val image = item.string("type") == "image"
    val data = item.string("data")
    val bytes = remember(data) { if (image) runCatching { Base64.getDecoder().decode(data.substringAfter(',')) }.getOrNull() else null }
    val mime = if (image) data.substringAfter("data:").substringBefore(';') else "text/markdown"
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null) model.exportRoleplay(uri) { bytes ?: data.toByteArray(Charsets.UTF_8) }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("资料附件") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (image) AsyncImage(bytes, item.string("title"), Modifier.fillMaxWidth().heightIn(max = 200.dp))
                else Text(data.take(1800), style = WriterTheme.typography.bodySmall)
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("名称") })
                OutlinedTextField(group, { group = it }, singleLine = true, label = { Text("分组") })
                OutlinedTextField(summary, { summary = it }, label = { Text("摘要") })
                CheckOption("在写作上下文中使用摘要", context) { context = it }
                Row {
                    TextButton(onClick = { export.launch(safeFileName(title)) }) { Text("导出") }
                    TextButton(onClick = { remove = true }) { Text("删除", color = WriterTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = {
            model.perform {
                mutateBook(book.id) {
                    it.obj("creative").records("assets").first { row -> row.string("id") == item.string("id") }
                        .put("title", title.trim()).put("group", group.trim()).put("summary", summary.trim())
                        .put("context", context).put("updatedAt", System.currentTimeMillis())
                }
                dismiss()
            }
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("关闭") } })
    if (remove) AlertDialog(
        onDismissRequest = { remove = false }, title = { Text("删除该附件？") },
        confirmButton = { TextButton(onClick = {
            model.perform {
                mutateBook(book.id) {
                    val creative = it.obj("creative")
                    creative.put("assets", JSONArray(creative.records("assets").filterNot { row ->
                        row.string("id") == item.string("id")
                    }))
                }
                dismiss()
            }
        }) { Text("删除") } }, dismissButton = { TextButton(onClick = { remove = false }) { Text("取消") } }
    )
}
