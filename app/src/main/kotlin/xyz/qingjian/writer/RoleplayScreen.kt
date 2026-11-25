package xyz.qingjian.writer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun RoleplayScreen(model: WriterModel, book: BookRow, modifier: Modifier) {
    val data = remember(book.payload) { roleplay(JSONObject(book.payload)) }
    val cards = remember(data) { data.records("cards") }
    val sessions = remember(data) { data.records("sessions") }
    var selected by rememberSaveable(book.id) { mutableStateOf("") }
    val session = sessions.firstOrNull { it.string("id") == selected } ?: sessions.lastOrNull()
    val card = cards.find { it.string("id") == session?.string("cardId") }
    var menu by remember { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    var useCard by remember { mutableStateOf<JSONObject?>(null) }
    var sessionOptions by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importRoleplayCard(book.id, uri) { selected = it }
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(session?.string("title")?.ifBlank { card?.string("name") ?: "文本扮演" } ?: "文本扮演",
                Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { menu = true }) { Text("会话") }
            DropdownMenu(menu, { menu = false }) {
                sessions.asReversed().forEach { item ->
                    val character = cards.find { it.string("id") == item.string("cardId") }
                    DropdownMenuItem({ Text(item.string("title").ifBlank { character?.string("name") ?: "会话" }) },
                        onClick = { selected = item.string("id"); menu = false })
                }
                if (session != null) DropdownMenuItem({ Text("当前会话设置") }, onClick = { menu = false; sessionOptions = true })
                DropdownMenuItem({ Text("新建角色") }, onClick = { menu = false; create = true })
                DropdownMenuItem({ Text("导入酒馆角色卡") }, onClick = {
                    menu = false
                    picker.launch(arrayOf("application/json", "text/plain", "image/png", "application/octet-stream"))
                })
                cards.forEach { item ->
                    DropdownMenuItem({ Text("与 ${item.string("name")} 新开会话") },
                        onClick = { useCard = item; menu = false })
                }
            }
        }
        if (session == null || card == null) {
            Text("导入酒馆 JSON 或 PNG 角色卡，或新建角色开始对话。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { create = true }) { Text("新建角色") }
                OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "image/png", "application/octet-stream")) }) {
                    Text("导入角色卡")
                }
            }
        } else {
            RoleplayConversation(model, book.id, card, session, Modifier.weight(1f), open = { selected = it })
        }
    }
    if (create) RoleplayCardDialog(dismiss = { create = false }) { newCard ->
        model.perform {
            val thread = newRoleplaySession(newCard, newCard.string("greeting"))
            mutateBook(book.id) {
                roleplay(it).array("cards").put(newCard)
                roleplay(it).array("sessions").put(thread)
            }
            selected = thread.string("id")
            create = false
        }
    }
    useCard?.let { target ->
        val character = remember(target) { etaCard(target) }
        val greetings = (listOf(character.firstMessage) + character.alternateGreetings).distinct()
        AlertDialog(
            onDismissRequest = { useCard = null }, title = { Text("选择开场白") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    greetings.forEach { greeting ->
                        TextButton(onClick = {
                            model.perform {
                                val thread = newRoleplaySession(target, greeting)
                                mutateBook(book.id) { roleplay(it).array("sessions").put(thread) }
                                selected = thread.string("id")
                                useCard = null
                            }
                        }) { Text(greeting.take(160).ifBlank { "空白会话" }) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { useCard = null }) { Text("取消") } }
        )
    }
    if (sessionOptions && session != null && card != null) {
        RoleplaySessionDialog(model, book.id, session, card) { sessionOptions = false }
    }
}

@Composable
private fun RoleplayConversation(
    model: WriterModel, bookId: String, card: JSONObject, session: JSONObject, modifier: Modifier,
    open: (String) -> Unit
) {
    val id = session.string("id")
    val runId = "roleplay:$bookId:$id"
    val runs by model.generations.collectAsStateWithLifecycle()
    val run = runs[runId]
    val running = run?.running == true
    var responseRewrite by rememberSaveable(id) { mutableStateOf(false) }
    var input by rememberSaveable(id) { mutableStateOf("") }
    var limit by rememberSaveable(id) { mutableIntStateOf(30) }
    val messages = remember(session) { session.records("messages") }
    val list = rememberLazyListState()
    var branching by remember { mutableStateOf<Int?>(null) }

    fun request(text: String?, rewrite: Boolean) {
        responseRewrite = rewrite
        model.generate(runId, "文本扮演", prepare = {
            val snapshot = export(bookId)
            val thread = findSession(snapshot, id)
            val stored = thread.records("messages")
            val added = text?.let { roleplayMessage("user", it) }
            val inputMessages = if (rewrite) stored.dropLast(1) else stored + listOfNotNull(added)
            val prompt = roleplayPrompt(snapshot, findCard(snapshot, thread.string("cardId")), thread, inputMessages, rewrite)
            if (added != null) {
                mutateBook(bookId) { findSession(it, id).array("messages").put(added) }
                if (input.trim() == text) input = ""
            }
            prompt
        }, accept = { reply ->
            mutateBook(bookId) { payload ->
                val transcript = findSession(payload, id).array("messages")
                if (rewrite) transcript.getJSONObject(transcript.length() - 1).put("text", reply)
                else transcript.put(roleplayMessage("assistant", reply))
            }
        })
    }

    val partial = run?.takeIf { !it.completed && it.text.isNotBlank() }
    LaunchedEffect(id, messages.size, partial != null) {
        if (list.layoutInfo.totalItemsCount > 0) list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val shown = messages.takeLast(limit)
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (messages.size > limit) item {
                TextButton(onClick = { limit += 30 }) { Text("查看更早的对话") }
            }
            items(shown, key = { it.string("id") }) { message ->
                val user = message.string("role") == "user"
                Surface(color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(if (user) session.string("userName", "玩家") else card.string("name"),
                            style = MaterialTheme.typography.labelLarge)
                        Text(message.string("text"), style = MaterialTheme.typography.bodyLarge)
                        TextButton(enabled = !running, onClick = { branching = messages.indexOf(message) }) { Text("从此分支") }
                    }
                }
            }
            if (partial != null) item {
                Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(if (running) card.string("name") else "未完成的回复",
                            style = MaterialTheme.typography.labelLarge)
                        Text(partial.text, style = MaterialTheme.typography.bodyLarge)
                        if (!running) TextButton(onClick = {
                            model.perform {
                                mutateBook(bookId) {
                                    val transcript = findSession(it, id).array("messages")
                                    if (responseRewrite && transcript.length() > 0) {
                                        transcript.getJSONObject(transcript.length() - 1).put("text", partial.text)
                                    } else transcript.put(roleplayMessage("assistant", partial.text))
                                }
                                model.clearGeneration(runId)
                            }
                        }) { Text("保留回复") }
                    }
                }
            }
        }
        if (run?.error != null) Text(run.error, color = MaterialTheme.colorScheme.error)
        if (running && partial == null) Text("角色正在回应…")
        if (!running) OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(),
            label = { Text("你要说的话") })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (running) OutlinedButton(onClick = { model.stopGeneration(runId) }) { Text("停止") }
            else {
                if (input.isNotBlank()) OutlinedButton(onClick = { request(input.trim(), false) }) { Text("发送") }
                if (messages.lastOrNull()?.string("role") == "user") {
                    TextButton(onClick = { request(null, false) }) { Text("继续回复") }
                } else if (messages.any { it.string("role") == "user" }) {
                    TextButton(onClick = { request(null, true) }) { Text("重写回复") }
                }
            }
        }
    }
    branching?.let { index ->
        AlertDialog(
            onDismissRequest = { branching = null }, title = { Text("从这条消息创建分支？") },
            confirmButton = {
                TextButton(onClick = {
                    model.perform {
                        val next = JSONObject(session.toString())
                            .put("id", newId()).put("title", session.string("title", card.string("name")) + " · 分支")
                            .put("messages", JSONArray(messages.take(index + 1)))
                        mutateBook(bookId) { roleplay(it).array("sessions").put(next) }
                        open(next.string("id"))
                        branching = null
                    }
                }) { Text("创建分支") }
            }, dismissButton = { TextButton(onClick = { branching = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun RoleplayCardDialog(dismiss: () -> Unit, saved: (JSONObject) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var scene by rememberSaveable { mutableStateOf("") }
    var greeting by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss, title = { Text("新建角色") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("名称") })
                OutlinedTextField(description, { description = it }, label = { Text("角色设定") })
                OutlinedTextField(scene, { scene = it }, label = { Text("场景") })
                OutlinedTextField(greeting, { greeting = it }, label = { Text("开场白") })
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                saved(writerCard(CharacterCardCodec.create(name.trim()).withEdits(
                    description = description, scenario = scene, firstMessage = greeting
                )))
            }) { Text("创建") }
        }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }
    )
}

@Composable
private fun RoleplaySessionDialog(model: WriterModel, bookId: String, session: JSONObject, card: JSONObject, dismiss: () -> Unit) {
    val id = session.string("id")
    var title by rememberSaveable(id) { mutableStateOf(session.string("title", card.string("name"))) }
    var user by rememberSaveable(id) { mutableStateOf(session.string("userName", "玩家")) }
    var persona by rememberSaveable(id) { mutableStateOf(session.string("persona")) }
    var memory by rememberSaveable(id) { mutableStateOf(session.string("memory")) }
    val report = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.exportRoleplay(uri) { session.toString(2).toByteArray(Charsets.UTF_8) }
    }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.exportRoleplay(uri) { exportCard(card, false) }
    }
    val png = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) model.exportRoleplay(uri) { exportCard(card, true) }
    }
    AlertDialog(
        onDismissRequest = dismiss, title = { Text("会话设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("会话名称") })
                OutlinedTextField(user, { user = it }, singleLine = true, label = { Text("玩家姓名") })
                OutlinedTextField(persona, { persona = it }, minLines = 2, label = { Text("玩家人设") })
                OutlinedTextField(memory, { memory = it }, minLines = 3, label = { Text("剧情记忆") })
                TextButton(onClick = { report.launch(safeFileName(title) + ".json") }) { Text("导出会话 JSON") }
                TextButton(onClick = { json.launch(safeFileName(card.string("name")) + ".json") }) { Text("导出角色卡 JSON") }
                if (card.has("png")) TextButton(onClick = { png.launch(safeFileName(card.string("name")) + ".png") }) {
                    Text("导出角色卡 PNG")
                }
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } },
        confirmButton = {
            TextButton(onClick = {
                model.perform {
                    mutateBook(bookId) {
                        findSession(it, id).put("title", title.trim()).put("userName", user.trim())
                            .put("persona", persona).put("memory", memory)
                    }
                    dismiss()
                }
            }) { Text("保存") }
        }
    )
}
