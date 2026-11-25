package xyz.qingjian.writer

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

private val builtInSkills = mapOf(
    "style-distillation" to ("文风蒸馏" to "从作者样稿归纳句长、视角、对话节奏、意象密度和标点，再应用于目标段。只借用写法。"),
    "break-patterns" to ("破甲" to "用原文已有的动作、感官和话语重新组织套话和惯性转折，保持叙事视角和因果。"),
    "natural-prose" to ("去 AI 味" to "局部清理重复总结、空泛评价和机械对称句式，保持作者语气及有意的文学表达。")
)
private val frontMatter = Regex("""^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)""")
private val markdownHeading = Regex("""^#\s+(.+)$""", RegexOption.MULTILINE)
private val skillText = Regex("""\.(md|txt|json|yaml|yml|csv|tsv|xml|html|css|js|ts|py|sh|ps1|toml)$""", RegexOption.IGNORE_CASE)
private const val packageLimit = 3 * 1024 * 1024

internal fun skillRecords(book: JSONObject): List<JSONObject> = builtInSkills.map { (id, value) ->
    JSONObject().put("id", id).put("name", value.first).put("instruction", value.second)
} + book.optJSONObject("skills")?.records("custom").orEmpty()

internal fun readSkillPackage(filename: String, bytes: ByteArray): JSONObject {
    require(bytes.size <= packageLimit) { "技能包不能超过 3 MB。" }
    if (!filename.endsWith(".zip", ignoreCase = true)) {
        require(bytes.size <= 128 * 1024) { "单个技能文件不能超过 128 KB。" }
        return parseSkill(filename, bytes.toString(Charsets.UTF_8))
    }
    val entries = linkedMapOf<String, ByteArray>()
    var size = 0
    ZipInputStream(ByteArrayInputStream(bytes)).use { archive ->
        while (true) {
            val entry = archive.nextEntry ?: break
            if (entry.isDirectory) continue
            val path = entry.name
            require('\\' !in path && ':' !in path && '\u0000' !in path && path.split('/').all {
                it.isNotBlank() && it != ".." && it != "."
            }) { "技能包含无效文件路径。" }
            require(path !in entries && entries.size < 150) { "技能包有重复路径或文件超过 150 个。" }
            val content = archive.readLimited(packageLimit - size, "解压后的技能超过 3 MB。")
            entries[path] = content
            size += content.size
        }
    }
    val index = entries.entries.filter { it.key.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) }
        .minByOrNull { it.key.length } ?: error("技能包缺少 SKILL.md。")
    val root = index.key.substringBeforeLast('/', "").let { if (it.isEmpty()) it else "$it/" }
    val files = entries.entries.filter { it.key != index.key && it.key.startsWith(root) }.map { (path, content) ->
        JSONObject().put("path", path.removePrefix(root)).apply {
            if (skillText.containsMatchIn(path)) put("text", content.toString(Charsets.UTF_8))
            else put("base64", Base64.getEncoder().encodeToString(content))
        }
    }
    return parseSkill(filename, index.value.toString(Charsets.UTF_8)).put("files", JSONArray(files))
}

private fun parseSkill(filename: String, content: String): JSONObject {
    var instruction = content.removePrefix("\uFEFF").trim()
    var title = filename.substringBeforeLast('.')
    var description = ""
    var metadata: JSONObject? = null
    if (filename.endsWith(".json", ignoreCase = true)) {
        val data = JSONObject(instruction)
        title = data.string("name")
        description = data.string("description")
        instruction = data.string("instruction", data.string("prompt"))
    } else {
        val front = frontMatter.find(instruction)
        if (front != null) {
            val options = LoadSettings.builder().setAllowDuplicateKeys(false).setMaxAliasesForCollections(0).build()
            val parsed = Load(options).loadFromString(front.groupValues[1])
            if (parsed is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val values = parsed.filterKeys { it is String } as Map<String, Any?>
                metadata = JSONObject(values)
                title = metadata.string("name", title)
                description = metadata.string("description")
            }
            instruction = instruction.substring(front.range.last + 1)
        } else title = markdownHeading.find(instruction)?.groupValues?.get(1) ?: title
    }
    require(title.isNotBlank() && instruction.isNotBlank()) { "技能名称和提示内容不能为空。" }
    return JSONObject().put("id", newId()).put("name", title.trim().take(60))
        .put("description", description.trim()).put("instruction", instruction.trim()).apply {
            if (metadata != null) put("metadata", metadata)
        }
}

internal fun skillPrompt(book: JSONObject, task: String): String {
    val settings = book.optJSONObject("skills") ?: return ""
    val enabled = settings.strings("enabled").toSet()
    return skillRecords(book).filter { it.string("id") in enabled }.joinToString("\n\n") { skill ->
        val instruction = skill.string("instruction")
        val files = skill.records("files").filter { file ->
            val path = file.string("path")
            file.string("text").isNotBlank() && (path in instruction || path.substringAfterLast('/') in instruction ||
                task.isNotBlank() && path.contains(task, ignoreCase = true))
        }.take(5).joinToString("\n") { "${it.string("path")}：${it.string("text").take(1800)}" }
        val sample = if (skill.string("id") == "style-distillation") settings.string("styleSample").take(1600) else ""
        listOf(skill.string("name"), instruction, files, sample).filter(String::isNotBlank).joinToString("\n")
    }
}

internal fun declaredSkillScript(skill: JSONObject): JSONObject? {
    val config = skill.optJSONObject("metadata")?.optJSONObject("qingjian") ?: return null
    val entry = config.string("entry")
    if (!entry.endsWith(".js") || '\\' in entry || ':' in entry || '\u0000' in entry ||
        entry.split('/').any { it.isBlank() || it == "." || it == ".." }) return null
    val files = skill.records("files")
    val source = files.find { it.string("path") == entry }?.string("text").orEmpty()
    if (source.isBlank() || source.length > 32_000) return null
    val tools = config.strings("tools").distinct().filter { it in setOf("wordCount", "find", "readReference") }
    val references = if ("readReference" in tools) files.filter {
        val path = it.string("path")
        path != entry && path.substringAfterLast('.') !in setOf("js", "ts", "sh", "py", "ps1") && it.string("text").isNotBlank()
    }.take(8).map { JSONObject().put("path", it.string("path").take(200)).put("text", it.string("text").take(2400)) }
    else emptyList()
    return JSONObject().put("name", skill.string("name").take(60)).put("entry", entry).put("source", source)
        .put("tools", JSONArray(tools)).put("references", JSONArray(references))
}

internal fun scriptFingerprint(script: JSONObject) = java.security.MessageDigest.getInstance("SHA-256")
    .digest(script.toString().toByteArray()).joinToString("") { "%02x".format(it) }
