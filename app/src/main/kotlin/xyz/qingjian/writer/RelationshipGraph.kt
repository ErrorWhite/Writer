package xyz.qingjian.writer

import io.github.suqi8.coui.kmp.basic.Surface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import io.github.suqi8.coui.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONObject

private data class RelationNode(
    val key: String, val title: String, val kind: String, val detail: String, val chapter: String,
    val links: List<String>
)

@Composable
internal fun LibraryGraph(model: WriterModel, book: BookRow, back: () -> Unit) {
    val chapterRows by remember(book.id) { model.library.directory(book.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val document = remember(book.payload) { JSONObject(book.payload) }
    val nodes = remember(chapterRows, document) {
        buildList {
            val creative = document.optJSONObject("creative")
            creative?.records("cards")?.forEach { row ->
                val chapter = row.string("chapterId")
                val thread = row.string("foreshadowId")
                val kind = when (row.string("kind")) {
                    "character" -> "人物"
                    "scene" -> "场景"
                    "rule" -> "世界规则"
                    else -> "资料卡"
                }
                add(RelationNode("card:${row.string("id")}", row.string("title"), kind, row.string("body"),
                    chapter, listOf("chapter:$chapter", "thread:$thread")))
            }
            creative?.records("assets")?.forEach { row ->
                val chapter = row.string("chapterId")
                val card = row.string("cardId")
                add(RelationNode("asset:${row.string("id")}", row.string("title"), "附件", row.string("summary"),
                    chapter, listOf("chapter:$chapter", "card:$card")))
            }
            document.optJSONObject("research")?.records("foreshadows")?.forEach { row ->
                val chapters = row.records("anchors").mapNotNull { it.optJSONObject("anchor")?.string("chapterId") }
                add(RelationNode("thread:${row.string("id")}", row.string("title"), "伏笔", row.string("description"),
                    chapters.firstOrNull().orEmpty(), chapters.map { "chapter:$it" }))
            }
            chapterRows.forEach { chapter ->
                add(RelationNode("chapter:${chapter.id}", chapter.title, "章节", "", chapter.id, emptyList()))
            }
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf("") }
    val filtered = nodes.filter { (it.title + " " + it.kind).contains(query.trim(), ignoreCase = true) }
    val active = filtered.firstOrNull { it.key == selected } ?: filtered.firstOrNull()
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索关系节点") })
    if (active == null) {
        Text("没有匹配的节点")
        return
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        filtered.forEach { node ->
            FilterChip(node.key == active.key, { selected = node.key }, label = node.title)
        }
    }
    val neighbors = nodes.filter { it.key != active.key && (it.key in active.links || active.key in it.links) }.take(10)
    NetworkGraph(listOf(active) + neighbors, neighbors.map { Triple(active.key, it.key, false) }, central = true) {
        selected = it
        query = ""
    }
    Text(active.title, style = WriterTheme.typography.titleLarge)
    if (active.detail.isNotBlank()) Text(active.detail)
    if (active.chapter.isNotBlank()) TextButton(onClick = {
        model.perform { select(book.id, active.chapter); back() }
    }) { Text("定位章节") }
}

@Composable
internal fun PlotGraph(events: List<JSONObject>, confirmed: List<JSONObject>, chronology: List<Pair<String, String>>) {
    if (events.size < 2) return
    var selected by rememberSaveable { mutableStateOf("") }
    val nodes = events.takeLast(12).map { item ->
        RelationNode(item.string("id"), item.string("title"), "事件", item.string("description"), "", emptyList())
    }
    val links = confirmed.map { Triple(it.string("from"), it.string("to"), false) } +
        chronology.map { Triple(it.first, it.second, true) }
    NetworkGraph(nodes, links, central = false) { selected = it }
    nodes.firstOrNull { it.key == selected }?.let { Text(it.title + " · " + it.detail) }
    Text("实线为已确认因果，虚线为章节顺序。", style = WriterTheme.typography.bodySmall)
}

@Composable
private fun NetworkGraph(
    nodes: List<RelationNode>, edges: List<Triple<String, String, Boolean>>,
    central: Boolean, selected: (String) -> Unit
) {
    val palette = WriterTheme.colorScheme
    val count = if (central) nodes.size - 1 else nodes.size
    val points = nodes.mapIndexed { index, node ->
        val order = if (central) index - 1 else index
        val angle = 2 * PI * order / count.coerceAtLeast(1) - PI / 2
        node.key to if (order < 0) Offset(.5f, .5f) else Offset(.5f + .35f * cos(angle).toFloat(),
            .5f + .35f * sin(angle).toFloat())
    }.toMap()
    Surface(shape = WriterTheme.shapes.large, color = palette.surfaceContainer) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(278.dp)) {
            val width = maxWidth
            val height = maxHeight
            Canvas(Modifier.fillMaxSize()) {
                edges.forEach { (from, to, dashed) ->
                    val a = points[from]
                    val b = points[to]
                    if (a != null && b != null) drawLine(
                        color = palette.primary.copy(alpha = if (dashed) .35f else .65f),
                        start = Offset(a.x * size.width, a.y * size.height),
                        end = Offset(b.x * size.width, b.y * size.height),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())) else null
                    )
                }
            }
            nodes.forEachIndexed { i, node ->
                val point = points.getValue(node.key)
                val diameter = if (i == 0 && central) 74.dp else 49.dp
                Surface(
                    onClick = { selected(node.key) },
                    modifier = Modifier.offset(x = width * point.x - diameter / 2, y = height * point.y - diameter / 2)
                        .size(diameter),
                    shape = CircleShape,
                    color = if (i == 0 && central) palette.primary else palette.surface,
                    contentColor = if (i == 0 && central) palette.onPrimary else palette.onSurface
                ) {
                    Box(Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
                        Text(node.title, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            style = WriterTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
