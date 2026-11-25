package xyz.qingjian.writer

import io.github.suqi8.coui.kmp.basic.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.ToolbarTextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.CharacterCardCompatibility
import coil.compose.AsyncImage
import java.util.Base64
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
    var editCard by remember { mutableStateOf<JSONObject?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importRoleplayCard(book.id, uri) { selected = it }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val encoded = card?.string("png").orEmpty()
            val avatar = remember(encoded) {
                if (encoded.isBlank()) null else runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
            }
            if (avatar != null) AsyncImage(avatar, null, Modifier.size(42.dp).clip(WriterTheme.shapes.medium),
                contentScale = ContentScale.Crop)
            Text(session?.string("title")?.ifBlank { card?.string("name") ?: "会话" } ?: "角色与会话",
                Modifier.weight(1f), style = WriterTheme.typography.titleLarge, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            ToolbarTextButton(text = "会话", onClick = { menu = true })
            DropdownMenu(menu, { menu = false }) {
                sessions.asReversed().forEach { item ->
                    val character = cards.find { it.string("id") == item.string("cardId") }
                    DropdownMenuItem(item.string("title").ifBlank { character?.string("name") ?: "会话" },
                        onClick = { selected = item.string("id"); menu = false })
                }
                if (session != null) DropdownMenuItem("当前会话设置", onClick = { menu = false; sessionOptions = true })
                if (card != null) DropdownMenuItem("编辑当前角色", onClick = { menu = false; editCard = card })
                DropdownMenuItem("新建角色", onClick = { menu = false; create = true })
                DropdownMenuItem("导入酒馆角色卡", onClick = {
                    menu = false
                    picker.launch(arrayOf("application/json", "text/plain", "image/png", "application/octet-stream"))
                })
                cards.forEach { item ->
                    DropdownMenuItem("与 ${item.string("name")} 新开会话",
                        onClick = { useCard = item; menu = false })
                }
            }
        }
        if (session == null || card == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(title = "开始一段对话", subtitle = "支持酒馆 JSON 和 PNG 角色卡。",
                        actionText = "新建角色", onActionClick = { create = true })
                    TextButton(onClick = { picker.launch(arrayOf("application/json", "image/png", "application/octet-stream")) }) {
                        Text("导入角色卡")
                    }
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
    editCard?.let { existing ->
        RoleplayCardDialog(existing, dismiss = { editCard = null }) { edited ->
            model.perform {
                mutateBook(book.id) { data ->
                    val list = roleplay(data).array("cards")
                    val index = list.objects().indexOfFirst { it.string("id") == existing.string("id") }
                    check(index >= 0) { "角色已不存在。" }
                    val merged = JSONObject(list.getJSONObject(index).toString())
                    edited.keys().asSequence().filterNot { it == "id" }.forEach { key -> merged.put(key, edited.get(key)) }
                    list.put(index, merged)
                }
                editCard = null
            }
        }
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
    var rewriteTarget by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var input by rememberSaveable(id) { mutableStateOf("") }
    var limit by rememberSaveable(id) { mutableIntStateOf(30) }
    val messages = remember(session) { session.records("messages") }
    val list = rememberLazyListState()
    var branching by remember { mutableStateOf<Int?>(null) }
    var reference by remember { mutableStateOf<JSONObject?>(null) }

    fun request(text: String?, rewrite: Boolean) {
        val target = if (rewrite) messages.lastOrNull()?.takeIf { it.string("role") == "assistant" }?.string("id") else null
        if (rewrite && target == null) return
        rewriteTarget = target
        model.generate(runId, "文本扮演", prepare = {
            val snapshot = export(bookId)
            val thread = findSession(snapshot, id)
            val stored = thread.records("messages")
            if (target != null) check(stored.lastOrNull()?.string("id") == target) { "会话内容已改变。" }
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
                if (target == null) transcript.put(roleplayMessage("assistant", reply))
                else {
                    val last = transcript.optJSONObject(transcript.length() - 1)
                    check(last?.string("id") == target) { "会话内容已改变。" }
                    reviseRoleplayReply(checkNotNull(last), reply)
                }
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
            contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (messages.size > limit) item {
                TextButton(onClick = { limit += 30 }) { Text("查看更早的对话") }
            }
            items(shown, key = { it.string("id") }) { message ->
                val user = message.string("role") == "user"
                var actions by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(
                        modifier = Modifier.widthIn(max = 540.dp).fillMaxWidth(if (user) .84f else .96f),
                        color = if (user) WriterTheme.colorScheme.primaryContainer else WriterTheme.colorScheme.surface,
                        shape = WriterTheme.shapes.large
                    ) {
                        Column(Modifier.padding(start = 16.dp, top = 6.dp, end = 12.dp, bottom = 16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (user) session.string("userName", "玩家") else card.string("name"),
                                    Modifier.weight(1f), style = WriterTheme.typography.labelMedium,
                                    color = WriterTheme.colorScheme.onSurfaceVariant)
                                Box {
                                    IconButton(onClick = { actions = true }, enabled = !running) {
                                        Icon(Icons.Default.MoreVert, "消息操作")
                                    }
                                    DropdownMenu(actions, { actions = false }) {
                                        val count = message.optJSONArray("replyAlternatives")?.length() ?: 0
                                        val index = message.optInt("selectedReply")
                                        if (count > 1) for ((step, label) in listOf(-1 to "上一版", 1 to "下一版")) {
                                            DropdownMenuItem(label, enabled = index + step in 0 until count, onClick = {
                                                actions = false
                                                model.perform {
                                                    mutateBook(bookId) {
                                                        findSession(it, id).records("messages").firstOrNull { entry ->
                                                            entry.string("id") == message.string("id")
                                                        }?.let { entry -> selectRoleplayReply(entry, index + step) }
                                                    }
                                                }
                                            })
                                        }
                                        DropdownMenuItem("从此分支", onClick = {
                                            actions = false
                                            branching = messages.indexOf(message)
                                        })
                                        if (bookId != standaloneRoleplayId && !user) {
                                            DropdownMenuItem("采用为参考", onClick = {
                                                actions = false
                                                reference = message
                                            })
                                        }
                                    }
                                }
                            }
                            Text(message.string("text"), style = WriterTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            if (partial != null) item {
                Surface(color = WriterTheme.colorScheme.surface, shape = WriterTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(if (running) card.string("name") else "未完成的回复",
                            style = WriterTheme.typography.labelLarge)
                        Text(partial.text, style = WriterTheme.typography.bodyLarge)
                        if (!running) TextButton(onClick = {
                            model.perform {
                                mutateBook(bookId) {
                                    val transcript = findSession(it, id).array("messages")
                                    if (rewriteTarget != null) {
                                        val last = transcript.optJSONObject(transcript.length() - 1)
                                        check(last?.string("id") == rewriteTarget) { "会话内容已改变。" }
                                        reviseRoleplayReply(checkNotNull(last), partial.text)
                                    } else transcript.put(roleplayMessage("assistant", partial.text))
                                }
                                model.clearGeneration(runId)
                            }
                        }) { Text("保留回复") }
                    }
                }
            }
        }
        if (run?.error != null) Text(run.error, color = WriterTheme.colorScheme.error)
        CurrentPlanNotice(model)
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    if (partial == null) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text("角色正在回应", Modifier.weight(1f), style = WriterTheme.typography.bodySmall)
                    ToolbarTextButton(text = "停止", onClick = { model.stopGeneration(runId) })
                } else {
                    TextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                        label = "输入消息", maxLines = 4)
                    Button(onClick = { request(input.trim(), false) }, enabled = input.isNotBlank(),
                        minWidth = 52.dp, insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("发送") }
                }
            }
        }
        if (!running && input.isBlank()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModelPicker(model, Modifier.weight(1f))
                if (messages.lastOrNull()?.string("role") == "user") {
                    TextButton(onClick = { request(null, false) }) { Text("继续回复") }
                } else if (messages.any { it.string("role") == "user" }) {
                    TextButton(onClick = { request(null, true) }) { Text("重写回复") }
                }
        }
    }
    reference?.let { reply ->
        AlertDialog(
            onDismissRequest = { reference = null }, title = { Text("采用为参考卡") },
            text = { Text(reply.string("text"), maxLines = 8) },
            confirmButton = {
                TextButton(onClick = {
                    model.perform {
                        mutateBook(bookId) {
                            val items = it.obj("creative").array("references")
                            if (items.objects().none { item -> item.string("roleplayMessage") == reply.string("id") }) {
                                items.put(JSONObject().put("id", newId()).put("title", session.string("title"))
                                    .put("structure", reply.string("text")).put("source", "文本扮演")
                                    .put("roleplayMessage", reply.string("id")).put("confirmed", true))
                            }
                        }
                        model.notice("已收入工作台的参考卡")
                    }
                    reference = null
                }) { Text("采用") }
            },
            dismissButton = { TextButton(onClick = { reference = null }) { Text("取消") } }
        )
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
private fun RoleplayCardDialog(source: JSONObject? = null, dismiss: () -> Unit, saved: (JSONObject) -> Unit) {
    val card = remember(source) { source?.let(::etaCard) }
    var name by rememberSaveable { mutableStateOf(card?.name.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(card?.description.orEmpty()) }
    var personality by rememberSaveable { mutableStateOf(card?.personality.orEmpty()) }
    var scene by rememberSaveable { mutableStateOf(card?.scenario.orEmpty()) }
    var greeting by rememberSaveable { mutableStateOf(card?.firstMessage.orEmpty()) }
    var alternates by rememberSaveable { mutableStateOf(card?.alternateGreetings.orEmpty().joinToString("\n---\n")) }
    var sample by rememberSaveable { mutableStateOf(card?.exampleMessages.orEmpty()) }
    AlertDialog(
        onDismissRequest = dismiss, title = { Text(if (card == null) "新建角色" else "编辑角色") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("名称") })
                OutlinedTextField(description, { description = it }, label = { Text("角色设定") })
                OutlinedTextField(personality, { personality = it }, label = { Text("性格") })
                OutlinedTextField(scene, { scene = it }, label = { Text("场景") })
                OutlinedTextField(greeting, { greeting = it }, label = { Text("开场白") })
                OutlinedTextField(alternates, { alternates = it }, label = { Text("备用开场白，用独立一行 --- 分隔") })
                OutlinedTextField(sample, { sample = it }, label = { Text("示例对话") })
                card?.let { original ->
                    val warnings = remember(original) { CharacterCardCompatibility.warnings(original) }
                    warnings.forEach { Text(it, style = WriterTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                saved(writerCard((card ?: CharacterCardCodec.create(name.trim())).withEdits(
                    name = name.trim(), description = description, personality = personality,
                    scenario = scene, firstMessage = greeting, exampleMessages = sample,
                    alternateGreetings = alternates.split("\n---\n").map(String::trim).filter(String::isNotBlank)
                )))
            }) { Text(if (card == null) "创建" else "保存") }
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
    var erase by rememberSaveable(id) { mutableStateOf(false) }
    val requests by model.generations.collectAsStateWithLifecycle()
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
                TextButton(enabled = requests["roleplay:$bookId:$id"]?.running != true, onClick = { erase = true }) {
                    Text("删除会话", color = WriterTheme.colorScheme.error)
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
    if (erase) AlertDialog(
        onDismissRequest = { erase = false }, title = { Text("删除会话？") },
        text = { Text("该会话的消息和记忆会从本机删除。") },
        confirmButton = {
            TextButton(onClick = {
                model.perform {
                    mutateBook(bookId) { data ->
                        val room = roleplay(data)
                        room.put("sessions", JSONArray(room.records("sessions").filterNot { it.string("id") == id }))
                    }
                    model.clearGeneration("roleplay:$bookId:$id")
                    dismiss()
                }
            }) { Text("删除") }
        }, dismissButton = { TextButton(onClick = { erase = false }) { Text("取消") } }
    )
}
