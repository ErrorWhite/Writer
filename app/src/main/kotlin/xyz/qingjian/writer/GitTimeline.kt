package xyz.qingjian.writer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.suqi8.coui.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException

@Composable
internal fun GitTimeline(model: WriterModel, book: BookRow, dismiss: () -> Unit) {
    var branch by rememberSaveable { mutableStateOf("main") }
    var revision by remember { mutableIntStateOf(0) }
    var timeline by remember { mutableStateOf(ManuscriptTimeline(emptyMap(), emptyList())) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var commit by rememberSaveable { mutableStateOf(false) }
    var fork by rememberSaveable { mutableStateOf(false) }
    var restore by rememberSaveable { mutableStateOf(false) }
    var diffs by remember { mutableStateOf<List<ManuscriptDiff>?>(null) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(book.id, branch, revision) {
        try {
            timeline = model.gitHistory.timeline(book.id, branch)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            model.notice(error.localizedMessage ?: "无法读取 Git 历史。")
        }
    }
    LaunchedEffect(book.id, selected) {
        diffs = null
        try {
            selected?.let { diffs = model.gitHistory.diff(book.id, it) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            model.notice(error.localizedMessage ?: "无法读取差异。")
        }
    }
    AlertDialog(
        onDismissRequest = dismiss, title = { Text(if (selected == null) "Git 版本历史" else "版本差异") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selected == null) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        timeline.heads.keys.ifEmpty { setOf("main") }.forEach { name ->
                            FilterChip(name == branch, { branch = name }, label = name)
                        }
                    }
                    if (timeline.commits.isEmpty()) Text("此书尚无 Git 提交。")
                    timeline.commits.forEach { item ->
                        TextButton(onClick = { selected = item.oid; expanded = null }) {
                            Column {
                                Text(item.message, style = WriterTheme.typography.titleMedium)
                                Text(DateFormat.getDateTimeInstance().format(Date(item.createdAt)) + " · " + item.oid.take(8),
                                    style = WriterTheme.typography.labelSmall)
                            }
                        }
                    }
                } else {
                    val changes = diffs
                    if (changes == null) Text("读取差异…")
                    else if (changes.isEmpty()) Text("此提交未改变写作文件。")
                    else changes.forEach { change ->
                        val label = when (change.path) {
                            "outline.md" -> "全书大纲"
                            "notes.md" -> "人物与世界设定"
                            "project.json" -> "创作设定与资料"
                            else -> (change.after.ifBlank { change.before }).lineSequence().firstOrNull()
                                ?.removePrefix("# ") ?: change.path
                        }
                        TextButton(onClick = { expanded = if (expanded == change.path) null else change.path }) { Text(label) }
                        if (expanded == change.path) {
                            Text("上一版", style = WriterTheme.typography.labelLarge)
                            Text(change.before.ifBlank { "（空白）" },
                                Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()))
                            Text("本版", style = WriterTheme.typography.labelLarge)
                            Text(change.after.ifBlank { "（空白）" },
                                Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()))
                        }
                    }
                    TextButton(onClick = { fork = true }) { Text("从此提交创建分支") }
                }
            }
        },
        confirmButton = {
            if (selected == null) TextButton(onClick = { commit = true }) { Text("提交当前全书") }
            else TextButton(onClick = { restore = true }) { Text("恢复此提交") }
        },
        dismissButton = { TextButton(onClick = { if (selected == null) dismiss() else selected = null }) {
            Text(if (selected == null) "关闭" else "返回历史")
        } }
    )
    if (commit) NameDialog("提交当前作品", "提交说明", dismiss = { commit = false }) { message ->
        model.perform {
            model.gitHistory.commit(book.id, branch, timeline.heads[branch], message, export(book.id))
            revision++
        }
    }
    if (fork) NameDialog("创建 Git 分支", "分支名称", dismiss = { fork = false }) { name ->
        val oid = selected ?: return@NameDialog
        model.perform {
            model.gitHistory.branch(book.id, name, oid)
            branch = name
            selected = null
            revision++
        }
    }
    if (restore) AlertDialog(
        onDismissRequest = { restore = false }, title = { Text("恢复该 Git 提交？") },
        text = { Text("青简会先保留当前全书还原点。") },
        confirmButton = { TextButton(onClick = {
            val oid = selected
            if (oid != null) model.restoreGitVersion(book.id, oid) { restore = false; selected = null }
        }) { Text("恢复") } }, dismissButton = { TextButton(onClick = { restore = false }) { Text("取消") } }
    )
}
