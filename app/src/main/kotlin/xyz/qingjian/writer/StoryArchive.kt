package xyz.qingjian.writer

import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private val collections = listOf("foreshadows", "events", "links")

internal fun checkedHandoff(raw: JSONObject, chapter: JSONObject): JSONObject {
    require(raw.string("recap").isNotBlank() && raw.string("bridge").isNotBlank()) { "交接缺少剧情概括或下章承接。" }
    val text = chapter.string("text")
    for (key in listOf("threads", "relations", "events", "links")) {
        val records = raw.records(key)
        require(records.size <= 6) { "交接中的证据条目过多。" }
        for (item in records) {
            val quote = item.string("quote")
            require(quote.isNotBlank() && text.contains(quote)) { "交接证据无法在正文逐字定位。" }
            if (key == "relations") require(item.string("from").isNotBlank() && item.string("to").isNotBlank() &&
                quote.contains(item.string("from")) && quote.contains(item.string("to"))) { "人物关系证据缺少明确人物。" }
        }
    }
    return raw
}

// Port of PC story-handoff.js, using exact evidence and per-record before/after values.
internal fun archiveHandoff(payload: JSONObject, chapter: JSONObject, handoff: JSONObject): JSONArray {
    val research = payload.obj("research")
    val previous = collections.associateWith { category ->
        research.array(category).objects().associate { item -> item.string("id") to item.toString() }
    }
    editChapter(chapter, chapter.string("text"), ensureParagraphs = true)
    fun anchor(quote: String): JSONObject? {
        val line = quote.lineSequence().firstOrNull()?.trim().orEmpty()
        val row = chapter.records("paragraphs").find { line.isNotBlank() && it.string("text").contains(line) } ?: return null
        return JSONObject().put("chapterId", chapter.string("id")).put("paragraphId", row.string("id"))
    }
    fun sameAnchor(left: JSONObject?, right: JSONObject): Boolean =
        left?.string("chapterId") == right.string("chapterId") && left?.string("paragraphId") == right.string("paragraphId")
    fun canonical(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    fun addEvent(title: String, note: String, quote: String, thread: String? = null): String? {
        val evidence = anchor(quote) ?: return null
        val events = research.array("events")
        val existing = events.objects().firstOrNull { canonical(it.string("title")) == canonical(title) &&
            sameAnchor(it.optJSONObject("anchor"), evidence) }
        if (existing != null) return existing.string("id")
        val id = newId()
        events.put(JSONObject().put("id", id).put("title", title).put("description", note)
            .put("anchor", evidence).put("quote", quote).put("source", "handoff").apply {
                if (thread != null) put("threadId", thread)
            })
        return id
    }
    for (thread in handoff.records("threads")) {
        val quote = thread.string("quote")
        val evidence = anchor(quote) ?: continue
        val title = thread.string("title")
        val note = thread.string("note")
        val threads = research.array("foreshadows")
        val row = threads.objects().find { canonical(it.string("title")) == canonical(title) } ?: JSONObject()
            .put("id", newId()).put("title", title).put("description", note).put("status", "open")
            .put("anchors", JSONArray()).put("source", "handoff").also { threads.put(it) }
        if (row.string("status") == "recovered") continue
        if (row.records("anchors").none { sameAnchor(it.optJSONObject("anchor"), evidence) }) {
            row.array("anchors").put(JSONObject().put("id", newId()).put("anchor", evidence)
                .put("role", "线索").put("quote", quote).put("source", "handoff"))
        }
        addEvent(title, note, quote, row.string("id"))
    }
    val eventIds = handoff.records("events").associate { event ->
        event.string("title") to addEvent(event.string("title"), event.string("note"), event.string("quote"))
    }
    for (work in handoff.records("relations")) {
        addEvent("${work.string("from")} · ${work.string("label")} · ${work.string("to")}",
            work.string("label"), work.string("quote"))
    }
    for (link in handoff.records("links").filter { it.optBoolean("confirmed") }) {
        val from = eventIds[link.string("from")] ?: research.records("events")
            .find { it.string("id") == link.string("from") }?.string("id")
        val to = eventIds[link.string("to")]
        val quote = link.string("quote")
        val evidence = anchor(quote)
        val links = research.array("links")
        if (from == null || to == null || evidence == null || from == to ||
            links.objects().any { it.string("from") == from && it.string("to") == to }) continue
        links.put(JSONObject().put("id", newId()).put("from", from).put("to", to)
            .put("text", link.string("label")).put("quote", quote).put("anchor", evidence).put("source", "handoff"))
    }
    return JSONArray(collections.flatMap { category ->
        research.records(category).mapNotNull { item ->
            val id = item.string("id")
            val before = previous.getValue(category)[id]
            val after = item.toString()
            if (before == after) null else JSONObject().put("key", category).put("id", id).put("after", after)
                .apply { if (before != null) put("before", before) }
        }
    })
}

internal fun undoHandoffArchive(payload: JSONObject, delta: JSONArray) {
    val research = payload.obj("research")
    for (entry in delta.objects().asReversed()) {
        val key = entry.string("key")
        if (key !in collections) continue
        val id = entry.string("id")
        val records = research.array(key)
        val index = records.objects().indexOfFirst { it.string("id") == id }
        if (index < 0 || !sameArchiveRecord(records.getJSONObject(index), entry.string("after"))) continue
        val before = entry.string("before")
        if (before.isNotBlank()) records.put(index, JSONObject(before))
        else if (key == "events" && research.records("links").any { it.string("from") == id || it.string("to") == id }) continue
        else if (key == "foreshadows" && research.records("events").any { it.string("threadId") == id }) continue
        else records.remove(index)
    }
}

private fun sameArchiveRecord(value: JSONObject, encoded: String): Boolean {
    fun ordered(input: Any?): Any? = when (input) {
        is JSONObject -> input.keys().asSequence().sorted().associateWith { ordered(input.opt(it)) }
        is JSONArray -> List(input.length()) { ordered(input.opt(it)) }
        else -> input
    }
    return encoded.isNotBlank() && ordered(value) == ordered(JSONObject(encoded))
}
