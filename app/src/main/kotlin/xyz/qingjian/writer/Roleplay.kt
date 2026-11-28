package xyz.qingjian.writer

import io.github.mangi.eta.agent.roleplay.CharacterCard
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.CharacterCardPng
import io.github.mangi.eta.agent.roleplay.CharacterMacros
import io.github.mangi.eta.agent.roleplay.CharacterWorldbook
import java.text.Normalizer
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.array(key: String): JSONArray = optJSONArray(key)
    ?: JSONArray().also { put(key, it) }

internal fun JSONObject.obj(key: String): JSONObject = optJSONObject(key)
    ?: JSONObject().also { put(key, it) }

internal fun roleplay(book: JSONObject) = book.obj("roleplay").apply {
    array("cards")
    array("sessions")
}

internal fun roleplayTranscriptRevision(messages: JSONArray): String = syncHash(sortedJson(messages))

internal fun appendRoleplayResponse(transcript: JSONArray, expectedRevision: String?, rewriteId: String?, text: String) {
    check(expectedRevision != null && roleplayTranscriptRevision(transcript) == expectedRevision) {
        "会话内容已改变，请重新生成回复。"
    }
    if (rewriteId == null) transcript.put(roleplayMessage("assistant", text))
    else {
        val last = transcript.optJSONObject(transcript.length() - 1)
        check(last?.string("id") == rewriteId) { "会话内容已改变，请重新生成回复。" }
        reviseRoleplayReply(checkNotNull(last), text)
    }
}

internal fun branchRoleplaySession(session: JSONObject, messageId: String, defaultTitle: String): JSONObject {
    val messages = session.records("messages")
    val index = messages.indexOfFirst { it.string("id") == messageId }
    require(index >= 0) { "这条消息已删除，无法创建分支。" }
    return JSONObject(session.toString()).put("id", newId())
        .put("title", session.string("title", defaultTitle) + " · 分支")
        .put("messages", JSONArray(messages.take(index + 1).map { JSONObject(it.toString()) }))
}

internal fun readRoleplayCard(bytes: ByteArray): JSONObject {
    val png = CharacterCardPng.isPng(bytes)
    val card = if (png) CharacterCardPng.read(bytes) else CharacterCardCodec.decodeBytes(bytes)
    return writerCard(card).apply {
        if (png) put("png", Base64.getEncoder().encodeToString(bytes))
    }
}

internal fun writerCard(card: CharacterCard) = JSONObject()
    .put("id", newId()).put("name", card.name)
    .put("description", listOf(card.description, card.personality).filter(String::isNotBlank).joinToString("\n"))
    .put("scene", card.scenario).put("greeting", card.firstMessage)
    .put("card", JSONObject(CharacterCardCodec.encodeJson(card)))

internal fun etaCard(card: JSONObject): CharacterCard = card.optJSONObject("card")?.let {
    CharacterCardCodec.decodeJson(it.toString())
} ?: CharacterCardCodec.create(card.string("name")).withEdits(
    description = card.string("description"), scenario = card.string("scene"), firstMessage = card.string("greeting")
)

private const val emptyCardImage =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGNgAAIAAAUAAXpeqz8AAAAASUVORK5CYII="

internal fun roleplayAvatar(bytes: ByteArray): String {
    CharacterCardPng.validate(bytes)
    return Base64.getEncoder().encodeToString(bytes)
}

internal fun exportCard(card: JSONObject, png: Boolean): ByteArray {
    val source = etaCard(card)
    if (!png) return CharacterCardCodec.encodeJson(source).toByteArray(Charsets.UTF_8)
    val image = Base64.getDecoder().decode(card.string("png").ifBlank { emptyCardImage })
    return CharacterCardPng.write(image, source)
}

internal fun newRoleplaySession(card: JSONObject, greeting: String) = JSONObject()
    .put("id", newId()).put("cardId", card.string("id")).put("title", card.string("name"))
    .put("persona", "").put("memory", "").put("userName", "玩家")
    .put("messages", JSONArray().apply {
        if (greeting.isNotBlank()) put(roleplayMessage("assistant", CharacterMacros.expand(greeting, etaCard(card), "玩家")))
    })

internal fun roleplayMessage(role: String, text: String) =
    JSONObject().put("id", newId()).put("role", role).put("text", text)

internal fun findSession(book: JSONObject, sessionId: String): JSONObject =
    roleplay(book).records("sessions").firstOrNull { it.string("id") == sessionId }
        ?: error("会话已删除。")

internal fun findCard(book: JSONObject, cardId: String): JSONObject =
    roleplay(book).records("cards").firstOrNull { it.string("id") == cardId }
        ?: error("角色已删除。")

internal fun roleplayPrompt(
    book: JSONObject, writerCard: JSONObject, session: JSONObject, messages: List<JSONObject>, rewrite: Boolean
): Pair<String, List<ModelMessage>> {
    val card = etaCard(writerCard)
    val persona = session.string("persona")
    val user = session.string("userName", "玩家").ifBlank { "玩家" }
    val recent = messages.takeLast(12)
    fun expand(text: String) = CharacterMacros.expand(text, card, user, persona).take(12_000)
    val matches = CharacterWorldbook.resolve(card, recent.map { it.string("text") }, 2_000,
        CharacterWorldbook::estimateEntryTokens)
    val world = worldBookMatches(book.optJSONObject("creative")?.optJSONObject("worldBook"),
        recent.takeLast(4).joinToString("\n") { it.string("text") }, 4_000)
    val system = buildList {
        add("以${card.name}的身份与${user}进行文本扮演。遵守角色设定和剧情连续性，直接返回角色行动与对白，避免替玩家决定行动。")
        add(matches.beforeCharacter)
        add(card.description.take(8_000))
        add(card.personality.take(3_000))
        add(card.scenario.take(4_000))
        add(card.systemPrompt.take(4_000))
        add(card.exampleMessages.take(4_000))
        add(matches.afterCharacter)
        add(world.joinToString("\n") { "${it.string("title")}：${it.string("content")}" })
        add(persona.take(4_000))
        add(session.string("memory").take(8_000))
        if (rewrite) add("用不同的表达和情节推进，重写上一条角色回复。")
        add(card.postHistoryInstructions.take(4_000))
    }.filter(String::isNotBlank).joinToString("\n\n", transform = ::expand)
    val input = recent.filter { it.string("role") in setOf("user", "assistant") }
        .map { ModelMessage(it.string("role"), it.string("text").takeLast(4_000)) }.toMutableList()
    if (input.firstOrNull()?.role == "assistant") input.add(0, ModelMessage("user", "开始这段扮演。"))
    if (input.lastOrNull()?.role != "user") input.add(ModelMessage("user", "请继续这段扮演。"))
    return system to input
}

// Port of the PC worldMatches keyword and budget rules for the book-level world.
internal fun worldBookMatches(world: JSONObject?, query: String, outerBudget: Int): List<JSONObject> =
    resolveBookWorld(world, query, outerBudget).chosen.map { it.entry }

internal data class BookWorldHit(val entry: JSONObject, val round: Int, val included: Boolean)

internal data class BookWorldResolution(val hits: List<BookWorldHit>, val used: Int, val budget: Int) {
    val chosen get() = hits.filter { it.included }
}

internal fun resolveBookWorld(world: JSONObject?, query: String, outerBudget: Int): BookWorldResolution {
    val entries = world?.records("entries").orEmpty()
        .filter { it.optBoolean("enabled", true) && it.string("content").isNotBlank() }
        .sortedWith(compareByDescending<JSONObject> { it.optBoolean("always") }.thenByDescending { it.optInt("priority", 100) })
    val limit = minOf(world?.optInt("budget", 1800)?.coerceIn(100, 16_000) ?: 1800, (outerBudget * 0.35).toInt())
    val hits = mutableListOf<BookWorldHit>()
    val seen = mutableSetOf<String>()
    var text = Normalizer.normalize(query, Normalizer.Form.NFKC)
    var used = 0
    for (round in 0..(world?.optInt("recursion") ?: 0).coerceIn(0, 3)) {
        val next = mutableListOf<String>()
        for (entry in entries) {
            val id = entry.string("id")
            val obvious = entry.optBoolean("always")
            if (id in seen || !obvious && (round > 0 && entry.optBoolean("excludeRecursion") ||
                    round < entry.optInt("recursionDelay"))) continue
            val ignoreCase = !entry.optBoolean("caseSensitive")
            fun matches(key: String) = text.contains(Normalizer.normalize(key, Normalizer.Form.NFKC), ignoreCase)
            if (!obvious) {
                if (!entry.strings("keys").any(::matches)) continue
                val secondary = entry.strings("secondary").map(::matches)
                if (secondary.isNotEmpty() && !when (entry.string("logic")) {
                    "all" -> secondary.all { it }
                    "not-any" -> secondary.none { it }
                    "not-all" -> secondary.any { !it }
                    else -> secondary.any { it }
                }) continue
            }
            seen += id
            val body = entry.string("content")
            val size = JSONObject().put("标题", entry.string("title")).put("内容", body).toString().length + 1
            val included = size + used <= limit
            hits += BookWorldHit(entry, round, included)
            if (!included) continue
            used += size
            if (!entry.optBoolean("preventRecursion")) next += body
        }
        if (round == 0 && next.isEmpty()) break
        if (next.isNotEmpty()) text += "\n" + Normalizer.normalize(next.joinToString("\n"), Normalizer.Form.NFKC)
    }
    return BookWorldResolution(hits, used, limit)
}

internal fun JSONObject.strings(key: String): List<String> =
    optJSONArray(key)?.let { list -> List(list.length()) { list.opt(it) as? String }.filterNotNull() }.orEmpty()

internal fun importedWorldBook(source: JSONObject): List<JSONObject> {
    val entries = when (val root = source.opt("entries")) {
        is JSONArray -> List(root.length()) { root.optJSONObject(it) }.filterNotNull()
        is JSONObject -> root.keys().asSequence().mapNotNull { root.optJSONObject(it) }.toList()
        else -> error("世界书 JSON 缺少 entries。")
    }
    fun keys(value: Any?): List<String> = when (value) {
        is JSONArray -> List(value.length()) { value.opt(it) as? String }.filterNotNull()
        is String -> value.split(',', '，', '、', '\n')
        else -> emptyList()
    }.map { it.trim().take(80) }.filter(String::isNotBlank).distinct().take(32)
    return entries.take(800).filter { it.string("content").isNotBlank() }.map { item ->
        val primary = keys(item.opt("keys") ?: item.opt("key"))
        val secondary = if (item.has("selective") && !item.optBoolean("selective")) emptyList()
            else keys(item.opt("secondary") ?: item.opt("keysecondary"))
        val logic = item.string("logic", listOf("any", "not-all", "not-any", "all")
            .getOrElse(item.optInt("selectiveLogic")) { "any" })
        JSONObject(item.toString()).put("id", newId())
            .put("title", item.string("title", item.string("comment")).trim().ifBlank { primary.firstOrNull() ?: "世界书条目" }.take(80))
            .put("content", item.string("content").trim().take(9000))
            .put("keys", JSONArray(primary)).put("secondary", JSONArray(secondary))
            .put("logic", logic).put("enabled", item.optBoolean("enabled", !item.optBoolean("disable")))
            .put("always", item.optBoolean("always", item.optBoolean("constant")))
            .put("priority", item.optInt("priority", item.optInt("order", 100)).coerceIn(0, 1000))
            .put("recursionDelay", item.optInt("recursionDelay", item.optInt("delayUntilRecursion")).coerceIn(0, 3))
    }
}
// Eta keeps reply revisions separate from the original transcript. Keep the
// selected text in the PC-compatible field and carry alternatives on the same ID.
internal fun reviseRoleplayReply(message: JSONObject, reply: String) {
    val existing = message.array("replyAlternatives")
    if (existing.length() == 0) existing.put(message.string("text"))
    existing.put(reply)
    message.put("replyAlternatives", existing)
    selectRoleplayReply(message, existing.length() - 1)
}

internal fun selectRoleplayReply(message: JSONObject, index: Int) {
    val alternatives = message.array("replyAlternatives")
    if (index !in 0 until alternatives.length()) return
    message.put("selectedReply", index).put("text", alternatives.getString(index))
}
