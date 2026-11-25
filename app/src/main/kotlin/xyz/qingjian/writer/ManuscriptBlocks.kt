package xyz.qingjian.writer

import org.commonmark.node.Node
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

internal data class EditorBlock(val node: Node?, val start: Int, val end: Int)

private val editorMarkdown = Parser.builder().extensions(writingExtensions)
    .includeSourceSpans(IncludeSourceSpans.BLOCKS).build()
private val lineBreaks = Regex("\r\n|\n|\r")

internal fun editorBlocks(source: String): List<EditorBlock> = buildList {
    fun section(start: Int, end: Int) {
        if (start >= end) return
        val text = source.substring(start, end)
        val lineStarts = buildList {
            add(start)
            lineBreaks.findAll(text).forEach { add(start + it.range.last + 1) }
        }
        val document = editorMarkdown.parse(text)
        for (node in generateSequence(document.firstChild) { it.next }) {
            val first = node.sourceSpans.firstOrNull() ?: continue
            val last = node.sourceSpans.last()
            add(EditorBlock(node, lineStarts[first.lineIndex] + first.columnIndex,
                (lineStarts[last.lineIndex] + last.columnIndex + last.length).coerceAtMost(end)))
        }
    }

    var previous = 0
    for (divider in customSceneDividers(source)) {
        section(previous, divider.start)
        add(EditorBlock(ThematicBreak(), divider.start, divider.end))
        previous = divider.end
    }
    section(previous, source.length)
    if (isEmpty()) add(EditorBlock(null, 0, source.length))
}
