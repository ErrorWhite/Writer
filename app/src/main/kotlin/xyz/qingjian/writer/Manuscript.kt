package xyz.qingjian.writer

import java.text.Normalizer
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal fun newId() = UUID.randomUUID().toString()

internal fun JSONArray.objects(): List<JSONObject> =
    List(length()) { index -> getJSONObject(index) }

internal fun JSONObject.string(key: String, fallback: String = ""): String =
    opt(key) as? String ?: fallback

internal fun JSONObject.records(key: String): List<JSONObject> =
    optJSONArray(key)?.objects().orEmpty()

internal object Manuscript {
    fun create(title: String): JSONObject {
        val chapter = newChapter("开篇")
        return JSONObject()
            .put("id", newId())
            .put("title", title)
            .put("cover", "")
            .put("outline", "")
            .put("notes", "")
            .put("activeId", chapter.getString("id"))
            .put("volumes", JSONArray().put(newVolume("第一卷", chapter)))
    }

    fun newVolume(title: String, chapter: JSONObject? = null): JSONObject =
        JSONObject().put("id", newId()).put("title", title).put(
            "chapters", JSONArray().apply { if (chapter != null) put(chapter) }
        )

    fun newChapter(title: String): JSONObject = JSONObject()
        .put("id", newId())
        .put("title", title)
        .put("text", "")
        .put("complete", false)

    fun read(source: String): JSONObject {
        val book = JSONObject(source.removePrefix("\uFEFF"))
        require(book.string("title").isNotBlank()) { "备份缺少书名。" }
        val modern = book.optJSONArray("volumes")
        val legacy = book.optJSONArray("chapters")
        if (modern == null && legacy != null) {
            book.put("volumes", JSONArray().put(JSONObject()
                .put("id", "volume-1")
                .put("title", book.string("volume").ifBlank { "第一卷 · 潮汐之下" })
                .put("chapters", legacy)))
            book.remove("volume")
            book.remove("chapters")
        }
        val volumes = book.records("volumes")
        val chapters = volumes.flatMap { volume ->
            require(volume.opt("title") is String && volume.optJSONArray("chapters") != null) {
                "备份中的分卷格式有误。"
            }
            volume.records("chapters")
        }
        require(chapters.isNotEmpty() &&
            chapters.all { it.opt("title") is String && it.opt("text") is String }) {
            "备份缺少有效章节。"
        }
        for (records in listOf(volumes, chapters)) {
            val ids = records.map { it.string("id") }
            require(ids.none(String::isBlank) && ids.distinct().size == ids.size) {
                "备份包含空白或重复的卷章编号。"
            }
        }
        chapters.forEach { chapter ->
            val ids = chapter.records("paragraphs").map { it.string("id") }
            require(ids.none(String::isBlank) && ids.distinct().size == ids.size) {
                "章节「${chapter.string("title")}」包含空白或重复的段落编号。"
            }
        }
        if (book.string("id").isBlank()) book.put("id", newId())
        if (chapters.none { it.string("id") == book.string("activeId") }) {
            book.put("activeId", chapters.first().string("id"))
        }
        return book
    }

    fun plainText(book: JSONObject, markdown: Boolean): String = buildString {
        if (!markdown) append('\uFEFF')
        if (markdown) append("# ")
        append(book.string("title"))
        var number = 0
        book.records("volumes").forEach { volume ->
            append("\n\n")
            if (markdown) append("## ")
            append(volume.string("title"))
            volume.records("chapters").forEach { chapter ->
                append("\n\n")
                if (markdown) append("### ")
                append(chapterLabel(++number))
                append(" ")
                append(chapter.string("title"))
                append("\n\n")
                val text = chapter.string("text")
                append(if (markdown) manuscriptMarkdown(text) else text)
            }
        }
    }

    private fun chapterLabel(position: Int): String {
        val digits = "零一二三四五六七八九"
        val number = when {
            position < 10 -> digits[position].toString()
            position < 100 -> buildString {
                if (position >= 20) append(digits[position / 10])
                append("十")
                if (position % 10 != 0) append(digits[position % 10])
            }
            else -> position.toString()
        }
        return "第${number}章"
    }
}

private val linePattern = Regex("([^\\r\\n]*)(\\r\\n|[\\r\\n]|$)")
private val fencePattern = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
private val dividerPattern = Regex("""(?:\*{3,}|[-‐‑‒–—―─━]{2,}|_{3,}|={3,}|[·•]{3,}|[✦❖※◇✧]{1,5})""")
private val markdownRule = Regex(""" *(?:-{3,}|_{3,}|\*{3,}) *""")

private data class ManuscriptLine(val text: String, val newline: String, val inCode: Boolean) {
    val divider: Boolean
        get() = !inCode && dividerPattern.matches(
            Normalizer.normalize(text.trim(), Normalizer.Form.NFKC).replace(" ", "").replace("\t", "")
        )
}

private fun manuscriptLines(text: String): Sequence<ManuscriptLine> = sequence {
    var fence: String? = null
    for (match in linePattern.findAll(text)) {
        if (match.value.isEmpty()) break
        val (line, newline) = match.destructured
        val marker = fencePattern.matchEntire(line)
        val delimiter = marker?.groupValues?.get(1)
        val info = marker?.groupValues?.get(2).orEmpty()
        val current = fence
        if (current == null && delimiter != null && (delimiter[0] != '`' || '`' !in info)) {
            fence = delimiter
        }
        yield(ManuscriptLine(line, newline, fence != null))
        if (current != null && delimiter != null && current[0] == delimiter[0] &&
            delimiter.length >= current.length && info.isBlank()) {
            fence = null
        }
    }
}

internal fun manuscriptMarkdown(text: String): String = buildString {
    manuscriptLines(text).forEach { line ->
        val indentedCode = line.text.startsWith("    ") || line.text.startsWith("\t")
        append(if (line.divider && !indentedCode && !markdownRule.matches(line.text)) "\n---\n" else line.text)
        append(line.newline)
    }
}

// Mirrors 青简 revisions.js. Unchanged lines keep their existing IDs and revision metadata.
internal fun editChapter(chapter: JSONObject, text: String) {
    if (chapter.string("text") == text) return
    val current = chapter.records("paragraphs")
    val lines = manuscriptLines(text).filter { it.text.isNotBlank() && !it.divider }
        .map { it.text }.toList().ifEmpty { listOf("") }
    var prefix = 0
    while (prefix < minOf(current.size, lines.size) && current[prefix].string("text") == lines[prefix]) {
        prefix++
    }
    var suffix = 0
    while (suffix < minOf(current.size, lines.size) - prefix &&
        current[current.lastIndex - suffix].string("text") == lines[lines.lastIndex - suffix]) {
        suffix++
    }
    val previous = current.subList(prefix, current.size - suffix)
    val replacement = lines.subList(prefix, lines.size - suffix)
    val byText = previous.groupBy { it.string("text") }.mapValues { ArrayDeque(it.value) }
    val matched = replacement.map { byText[it]?.removeFirstOrNull() }
    val retained = matched.filterNotNull().toSet()
    val unused = ArrayDeque(previous.filter { it !in retained })
    val canReuse = unused.size == matched.count { it == null }
    val middle = replacement.mapIndexed { index, line ->
        matched[index] ?: if (canReuse) {
            unused.removeFirst().put("text", line)
        } else {
            JSONObject().put("id", newId()).put("text", line).put("original", line)
                .put("instruction", "").put("vote", "").put("drafts", JSONArray())
        }
    }
    chapter.put("text", text)
    chapter.put("paragraphs", JSONArray(current.take(prefix) + middle + current.takeLast(suffix)))
}
