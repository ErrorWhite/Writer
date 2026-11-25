package xyz.qingjian.writer

import java.io.OutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.json.JSONObject

internal enum class ExportFormat(val extension: String, val mime: String, val label: String) {
    Json("json", "application/json", "全书 JSON"),
    Markdown("md", "text/markdown", "全书 Markdown"),
    Text("txt", "text/plain", "全书 TXT"),
    Epub("epub", "application/epub+zip", "全书 EPUB"),
    Chapter("txt", "text/plain", "当前章节 TXT");

    fun write(book: JSONObject, chapterId: String?, output: OutputStream) {
        if (this == Epub) {
            writeEpub(book, output)
            return
        }
        val content = when (this) {
            Json -> book.toString(2)
            Markdown -> Manuscript.plainText(book, markdown = true)
            Text -> Manuscript.plainText(book, markdown = false)
            Chapter -> {
                val chapter = book.records("volumes").flatMap { it.records("chapters") }
                    .first { it.string("id") == chapterId }
                "\uFEFF" + chapter.string("title") + "\n\n" + chapter.string("text")
            }
            Epub -> error("EPUB is written as a ZIP archive")
        }
        output.write(content.toByteArray(Charsets.UTF_8))
    }
}

private fun xml(text: String): String = text.replace("&", "&amp;")
    .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\uFFFE\\uFFFF]"), "")

private fun page(title: String, body: String) = """
    <?xml version="1.0" encoding="UTF-8"?>
    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN">
    <head><title>${xml(title)}</title><link rel="stylesheet" type="text/css" href="book.css"/></head>
    <body>$body</body></html>
""".trimIndent()

private fun writeEpub(book: JSONObject, output: OutputStream) {
    val parser = Parser.builder().extensions(writingExtensions).build()
    val renderer = HtmlRenderer.builder().extensions(writingExtensions).escapeHtml(true).sanitizeUrls(true).build()
    ZipOutputStream(output).use { zip ->
        val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        zip.putNextEntry(ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = mime.size.toLong()
            crc = CRC32().apply { update(mime) }.value
        })
        zip.write(mime)
        zip.closeEntry()
        fun add(path: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(bytes)
            zip.closeEntry()
        }
        fun add(path: String, text: String) = add(path, text.toByteArray(Charsets.UTF_8))
        add("META-INF/container.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles><rootfile full-path="EPUB/book.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent())
        add("EPUB/book.css", "body{max-width:38em;margin:auto;padding:1.5em;font-family:serif;line-height:1.8}img{max-width:100%}pre{white-space:pre-wrap}")
        val manifest = mutableListOf(
            """<item id="css" href="book.css" media-type="text/css"/>""",
            """<item id="toc" href="toc.xhtml" media-type="application/xhtml+xml" properties="nav"/>"""
        )
        val spine = mutableListOf<String>()
        val title = book.string("title")
        fun addPage(id: String, title: String, body: String) {
            add("EPUB/$id.xhtml", page(title, body))
            manifest += """<item id="$id" href="$id.xhtml" media-type="application/xhtml+xml"/>"""
            spine += """<itemref idref="$id"/>"""
        }
        val cover = book.string("cover")
        coverImage(cover)?.let { bytes ->
            val type = cover.substringAfter("data:").substringBefore(';')
            if (type == "image/png" || type == "image/jpeg") {
                val path = "cover." + if (type == "image/png") "png" else "jpg"
                add("EPUB/$path", bytes)
                manifest += """<item id="cover-img" href="$path" media-type="$type" properties="cover-image"/>"""
                addPage("cover", title, """<img src="$path" alt="${xml(title)}"/>""")
            }
        }
        addPage("title", title, "<h1>${xml(title)}</h1>")
        var count = 0
        val navigation = book.records("volumes").mapNotNull { volume ->
            val chapters = volume.records("chapters")
            if (chapters.isEmpty()) return@mapNotNull null
            val links = chapters.joinToString("") { chapter ->
                val id = "chapter-${++count}"
                val name = chapter.string("title")
                val markdown = manuscriptMarkdown(chapter.string("text"))
                val rendered = renderer.render(parser.parse(markdown))
                addPage(id, name, "<h1>${xml(name)}</h1>" + rendered)
                """<li><a href="$id.xhtml">${xml(name)}</a></li>"""
            }
            "<li><span>${xml(volume.string("title"))}</span><ol>$links</ol></li>"
        }.joinToString("")
        add("EPUB/toc.xhtml", page("目录", """<nav epub:type="toc" id="toc"><h1>目录</h1><ol>$navigation</ol></nav>"""))
        val modified = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        add("EPUB/book.opf", """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="id" version="3.0">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:identifier id="id">${xml("urn:qingjian:" + book.string("id"))}</dc:identifier>
            <dc:title>${xml(title)}</dc:title><dc:language>zh-CN</dc:language>
            <meta property="dcterms:modified">$modified</meta></metadata>
            <manifest>${manifest.joinToString("")}</manifest><spine>${spine.joinToString("")}</spine>
            </package>
        """.trimIndent())
    }
}
