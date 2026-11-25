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

internal fun exportCard(card: JSONObject, png: Boolean): ByteArray {
    val source = etaCard(card)
    if (!png) return CharacterCardCodec.encodeJson(source).toByteArray(Charsets.UTF_8)
    return CharacterCardPng.write(Base64.getDecoder().decode(card.getString("png")), source)
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
internal fun worldBookMatches(world: JSONObject?, query: String, outerBudget: Int): List<JSONObject> {
    if (world == null) return emptyList()
    val entries = world.records("entries").filter { it.optBoolean("enabled", true) && it.string("content").isNotBlank() }
        .sortedWith(compareByDescending<JSONObject> { it.optBoolean("always") }.thenByDescending { it.optInt("priority", 100) })
    val limit = minOf(world.optInt("budget", 1800), (outerBudget * 0.35).toInt())
    val chosen = mutableListOf<JSONObject>()
    val seen = mutableSetOf<String>()
    var text = Normalizer.normalize(query, Normalizer.Form.NFKC)
    var used = 0
    for (round in 0..world.optInt("recursion", 0).coerceIn(0, 3)) {
        val next = mutableListOf<String>()
        for (entry in entries) {
            val id = entry.string("id")
            val obvious = entry.optBoolean("always")
            if (id in seen || !obvious && (round > 0 && entry.optBoolean("excludeRecursion") ||
                    round < entry.optInt("recursionDelay"))) continue
            val ignoreCase = !entry.optBoolean("caseSensitive")
            fun hits(key: String) = text.contains(Normalizer.normalize(key, Normalizer.Form.NFKC), ignoreCase)
            if (!obvious) {
                if (!entry.strings("keys").any(::hits)) continue
                val secondary = entry.strings("secondary").map(::hits)
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
            if (size + used > limit) continue
            used += size
            chosen += entry
            if (!entry.optBoolean("preventRecursion")) next += body
        }
        if (next.isEmpty()) break
        text += "\n" + Normalizer.normalize(next.joinToString("\n"), Normalizer.Form.NFKC)
    }
    return chosen
}

internal fun JSONObject.strings(key: String): List<String> =
    optJSONArray(key)?.let { list -> List(list.length()) { list.opt(it) as? String }.filterNotNull() }.orEmpty()
