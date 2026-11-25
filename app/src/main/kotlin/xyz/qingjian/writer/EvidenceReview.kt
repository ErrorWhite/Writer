package xyz.qingjian.writer

import java.text.Normalizer
import org.json.JSONArray
import org.json.JSONObject

internal val evidenceKinds = setOf("character", "rule", "scene", "foreshadow", "event", "annotation")
internal val evidenceIntents = setOf(WritingIntent.Review, WritingIntent.Extract, WritingIntent.State)

internal fun evidenceFor(chapter: JSONObject): List<JSONObject> = chapter.records("paragraphs").map { row ->
    JSONObject().put("chapterId", chapter.string("id")).put("paragraphId", row.string("id")).put("text", row.string("text"))
}

internal fun stateTargets(book: JSONObject): List<JSONObject> {
    val cards = book.optJSONObject("creative")?.records("cards").orEmpty()
        .filter { it.optBoolean("confirmed") && it.string("kind") in setOf("character", "rule") }
    val threads = book.optJSONObject("research")?.records("foreshadows").orEmpty()
        .filter { it.string("status") != "recovered" }
    return (cards + threads).map { row ->
        JSONObject().put("id", row.string("id")).put("kind", row.string("kind", "foreshadow"))
            .put("title", row.string("title")).put("state", row.string("state"))
    }
}

internal fun checkedEvidence(
    output: JSONArray, type: WritingIntent, chapter: JSONObject, book: JSONObject
): List<JSONObject> {
    require(output.length() <= 12) { "资料候选条目过多。" }
    val id = chapter.string("id")
    val rows = chapter.records("paragraphs").associateBy { it.string("id") }
    val targets = if (type == WritingIntent.State) stateTargets(book).associateBy { it.string("id") } else emptyMap()
    return output.objects().map { item ->
        val quote = item.string("quote")
        val paragraph = rows[item.string("paragraphId")]
        require(quote.isNotBlank() && quote.length <= 260 && paragraph?.string("text")?.contains(quote) == true) {
            "候选引用无法在对应段落逐字定位。"
        }
        when (type) {
            WritingIntent.Review -> require(item.string("role") in setOf("continuity", "transition", "style") &&
                item.string("note").isNotBlank()) { "章节审阅的类别或说明缺失。" }
            WritingIntent.Extract -> require(item.string("kind") in evidenceKinds && item.string("title").isNotBlank() &&
                item.string("body").isNotBlank()) { "资料候选缺少标题、内容或类型。" }
            WritingIntent.State -> require(targets[item.string("targetId")]?.string("kind") == item.string("kind") &&
                item.string("kind").isNotBlank() && item.string("value").isNotBlank() && item.string("note").isNotBlank()) {
                "状态提案目标不存在，或提案缺少说明。"
            }
            else -> error("Unexpected evidence intent")
        }
        item.put("chapterId", id).put("candidateId", item.string("candidateId").ifBlank { newId() })
    }
}

internal fun useEvidence(book: JSONObject, type: WritingIntent, items: List<JSONObject>) {
    val research = book.obj("research")
    val creative = book.obj("creative")
    for (item in items) {
        val kind = item.string("kind")
        val anchor = JSONObject().put("chapterId", item.string("chapterId")).put("paragraphId", item.string("paragraphId"))
        val quote = item.string("quote")
        if (type == WritingIntent.Review) {
            creative.array("reviews").put(JSONObject(item.toString()).put("id", newId()).put("body", item.string("note")))
        } else if (type == WritingIntent.State) {
            val target = item.string("targetId")
            val value = item.string("value")
            if (kind == "foreshadow") {
                val row = research.records("foreshadows").first { it.string("id") == target }
                row.put("status", "recovered").array("anchors").put(JSONObject().put("id", newId())
                    .put("anchor", anchor).put("role", "回收").put("quote", quote))
            } else creative.records("cards").first { it.string("id") == target }.put("state", value)
            creative.array("changes").put(JSONObject(item.toString()).put("id", newId()).put("applied", true))
        } else if (type == WritingIntent.Extract) {
            val title = item.string("title")
            val body = item.string("body")
            val row = JSONObject().put("id", newId()).put("title", title).put("quote", quote)
            when (kind) {
                "annotation" -> research.array("annotations").put(row.put("kind", title).put("text", body).put("anchor", anchor))
                "event" -> if (!duplicate(research, "events", title)) {
                    research.array("events").put(row.put("description", body).put("anchor", anchor))
                }
                "foreshadow" -> if (!duplicate(research, "foreshadows", title)) {
                    research.array("foreshadows").put(row.put("description", body).put("status", "open")
                        .put("anchors", JSONArray().put(JSONObject().put("id", newId()).put("anchor", anchor)
                            .put("role", "线索").put("quote", quote))))
                }
                else -> if (!duplicate(creative, "cards", title, kind)) {
                    creative.array("cards").put(row.put("kind", kind).put("body", body).put("confirmed", true)
                        .put("chapterId", anchor.string("chapterId")).put("paragraphId", anchor.string("paragraphId")))
                }
            }
        }
    }
}

private fun duplicate(parent: JSONObject, key: String, title: String, kind: String? = null): Boolean {
    fun keyOf(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase()
    return parent.records(key).any { (kind == null || kind == it.string("kind")) && keyOf(title) == keyOf(it.string("title")) }
}
