package xyz.qingjian.writer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MarkdownText
import org.commonmark.node.ThematicBreak

@Composable
internal fun ManuscriptBlock(
    node: Node,
    body: androidx.compose.ui.text.TextStyle = WriterTheme.typography.bodyLarge.copy(
        fontFamily = FontFamily.Serif, lineHeight = 29.sp
    )
) {
    val palette = WriterTheme.colorScheme
    fun render(input: Node) = buildAnnotatedString { inlineMarkdown(input, palette.primary) }
    when (node) {
        is ThematicBreak -> HorizontalDivider(Modifier.fillMaxWidth(.28f))
        is Heading -> Text(render(node), style = when (node.level) {
            1 -> WriterTheme.typography.headlineSmall
            2 -> WriterTheme.typography.titleLarge
            else -> body.copy(fontWeight = FontWeight.Bold)
        })
        is TableBlock -> Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            for (group in node.children()) {
                for (row in group.children().filterIsInstance<TableRow>()) {
                    Row(Modifier.background(if (group is TableHead) palette.surfaceContainer else palette.surface),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        row.children().filterIsInstance<TableCell>().forEach { cell ->
                            Text(render(cell), Modifier.widthIn(min = 112.dp, max = 184.dp).padding(8.dp),
                                style = body, fontWeight = if (group is TableHead) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    HorizontalDivider(color = palette.outlineVariant)
                }
            }
        }
        is FencedCodeBlock, is IndentedCodeBlock -> {
            val code = if (node is FencedCodeBlock) node.literal else (node as IndentedCodeBlock).literal
            Text(code.trimEnd(), Modifier.fillMaxWidth().background(palette.surfaceContainer).padding(12.dp),
                fontFamily = FontFamily.Monospace)
        }
        is BlockQuote -> Column(Modifier.fillMaxWidth().background(palette.surfaceContainer).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            node.children().forEach { child -> Text(render(child), style = body, fontStyle = FontStyle.Italic) }
        }
        is BulletList, is OrderedList -> {
            val first = if (node is OrderedList) node.markerStartNumber ?: 1 else null
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                node.children().forEachIndexed { index, item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(first?.let { "${it + index}." } ?: "•")
                        Text(render(item), style = body)
                    }
                }
            }
        }
        else -> {
            val text = remember(node, palette.primary) { render(node) }
            Text(text, style = body)
        }
    }
}

private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }

private fun AnnotatedString.Builder.inlineMarkdown(node: Node, linkColor: Color) {
    fun children() { node.children().forEach { inlineMarkdown(it, linkColor) } }
    when (node) {
        is MarkdownText -> append(node.literal)
        is SoftLineBreak, is HardLineBreak -> append('\n')
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children() }
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children() }
        is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { children() }
        is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(node.literal) }
        is HtmlInline -> append(node.literal)
        is HtmlBlock -> append(node.literal)
        is TaskListItemMarker -> append(if (node.isChecked) "☑ " else "☐ ")
        is Link -> {
            val destination = node.destination
            if (destination.startsWith("https://", true) || destination.startsWith("http://", true)) {
                val style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                withLink(LinkAnnotation.Url(destination, TextLinkStyles(style = style))) { children() }
            } else children()
        }
        else -> children()
    }
}
