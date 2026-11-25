package xyz.qingjian.writer

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.TreeFormatter
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.storage.file.WindowCacheConfig
import org.eclipse.jgit.treewalk.TreeWalk
import org.json.JSONObject

internal data class ManuscriptCommit(val oid: String, val message: String, val createdAt: Long)
internal data class ManuscriptTimeline(val heads: Map<String, String>, val commits: List<ManuscriptCommit>)
internal data class ManuscriptDiff(val path: String, val before: String, val after: String)

internal class ManuscriptGit(private val root: File) {
    suspend fun timeline(bookId: String, branch: String): ManuscriptTimeline =
        withContext(Dispatchers.IO) {
            val dir = directory(bookId)
            if (!dir.exists()) return@withContext ManuscriptTimeline(emptyMap(), emptyList())
            open(dir).use { repo ->
                val heads = repo.refDatabase.getRefsByPrefix(Constants.R_HEADS)
                    .associate { it.name.removePrefix(Constants.R_HEADS) to it.objectId.name }
                val id = heads[branch] ?: return@use ManuscriptTimeline(heads, emptyList())
                RevWalk(repo).use { walk ->
                    walk.markStart(walk.parseCommit(ObjectId.fromString(id)))
                    ManuscriptTimeline(heads, walk.asSequence().take(60).map {
                        ManuscriptCommit(it.id.name, it.fullMessage.trim(), it.commitTime.toLong() * 1000)
                    }.toList())
                }
            }
        }

    suspend fun commit(bookId: String, branch: String, expected: String?, message: String, book: JSONObject) =
        withContext(Dispatchers.IO) {
            require(message.isNotBlank() && message.length <= 180) { "提交说明需要 1–180 字。" }
            val ref = ref(branch)
            val dir = directory(bookId)
            check(dir.parentFile?.mkdirs() == true || dir.parentFile?.isDirectory == true) { "无法创建版本目录。" }
            open(dir).use { repo ->
                if (!repo.objectDatabase.exists()) {
                    require(branch == "main") { "请先创建主分支提交。" }
                    repo.create(true)
                }
                val parent = repo.resolve(ref)
                check(parent?.name == expected) { "分支已更新，请刷新时间线。" }
                require(parent != null || branch == "main") { "请先创建此分支。" }
                repo.newObjectInserter().use { objects ->
                    val files = TreeFormatter()
                    val chapters = TreeFormatter()
                    val contents = entries(book)
                    contents.filterKeys { it.startsWith("chapters/") }.toSortedMap().forEach { (path, text) ->
                        chapters.append(path.substringAfterLast('/'), FileMode.REGULAR_FILE,
                            objects.insert(Constants.OBJ_BLOB, text.toByteArray()))
                    }
                    val chapterTree = objects.insert(chapters)
                    for ((name, text) in contents.filterKeys { '/' !in it }.toSortedMap()) {
                        files.append(name, FileMode.REGULAR_FILE, objects.insert(Constants.OBJ_BLOB, text.toByteArray()))
                        if (name == "book.json") files.append("chapters", FileMode.TREE, chapterTree)
                    }
                    val tree = objects.insert(files)
                    val unchanged = parent != null && RevWalk(repo).use { it.parseCommit(parent).tree.id == tree }
                    require(!unchanged) { "书稿与分支的最新提交一致。" }
                    val author = PersonIdent("青简", "qingjian@localhost")
                    val commit = CommitBuilder().apply {
                        setTreeId(tree)
                        if (parent != null) setParentId(parent)
                        this.author = author
                        committer = author
                        this.message = message.trim()
                    }
                    val id = objects.insert(commit)
                    objects.flush()
                    update(repo, ref, id, parent)
                }
            }
        }

    suspend fun branch(bookId: String, name: String, oid: String) = withContext(Dispatchers.IO) {
        val branch = ref(name)
        open(directory(bookId)).use { repo ->
            require(repo.refDatabase.getRefsByPrefix(Constants.R_HEADS).none { it.name.equals(branch, true) }) {
                "已有同名分支。"
            }
            val id = ObjectId.fromString(oid)
            RevWalk(repo).use { it.parseCommit(id) }
            update(repo, branch, id, null)
        }
    }

    suspend fun snapshot(bookId: String, oid: String): JSONObject = withContext(Dispatchers.IO) {
        open(directory(bookId)).use { repo ->
            RevWalk(repo).use { walk ->
                val commit = walk.parseCommit(ObjectId.fromString(oid))
                Manuscript.read(blob(repo, commit.tree.id, "book.json"))
            }
        }
    }

    suspend fun diff(bookId: String, oid: String): List<ManuscriptDiff> = withContext(Dispatchers.IO) {
        open(directory(bookId)).use { repo ->
            RevWalk(repo).use { walk ->
                val current = walk.parseCommit(ObjectId.fromString(oid))
                val parent = current.parents.firstOrNull()?.let { walk.parseCommit(it.id) }
                val after = entries(JSONObject(blob(repo, current.tree.id, "book.json")))
                val before = parent?.let { entries(JSONObject(blob(repo, it.tree.id, "book.json"))) }.orEmpty()
                (before.keys + after.keys).filterNot { it == "book.json" }.mapNotNull { path ->
                    val a = before[path].orEmpty()
                    val b = after[path].orEmpty()
                    if (a == b) null else ManuscriptDiff(path, a.take(120_000), b.take(120_000))
                }
            }
        }
    }

    internal fun directory(bookId: String) = File(root, hash(bookId) + ".git")

    private fun open(dir: File): Repository {
        gitCache
        return FileRepositoryBuilder().setGitDir(dir).setBare().build()
    }

    private fun update(repo: Repository, ref: String, value: ObjectId, expected: ObjectId?) {
        val change = repo.updateRef(ref)
        change.setExpectedOldObjectId(expected ?: ObjectId.zeroId())
        change.setNewObjectId(value)
        val result = change.update()
        check(result in setOf(RefUpdate.Result.NEW, RefUpdate.Result.FAST_FORWARD)) { "无法更新 Git 分支，请刷新后重试。" }
    }

    private fun blob(repo: Repository, tree: ObjectId, path: String): String {
        val location = requireNotNull(TreeWalk.forPath(repo, path, tree)) { "提交缺少书稿快照。" }
        return location.use { repo.open(it.getObjectId(0)).getBytes(64 * 1024 * 1024).toString(Charsets.UTF_8) }
    }
}

private val gitCache by lazy { WindowCacheConfig().apply { setExposeStatsViaJmx(false) }.install() }

private val validBranch = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,47}")
private fun ref(name: String): String {
    require(validBranch.matches(name) && !name.equals("HEAD", true)) { "分支名须以字母或数字开头，可使用连字符和下划线。" }
    return Constants.R_HEADS + name
}

private fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    .take(12).joinToString("") { "%02x".format(it) }

private fun entries(book: JSONObject): Map<String, String> = buildMap {
    put("book.json", book.toString(2))
    put("outline.md", book.string("outline"))
    put("notes.md", book.string("notes"))
    val project = JSONObject(book.toString()).apply {
        remove("volumes")
        remove("outline")
        remove("notes")
    }
    put("project.json", project.toString(2))
    for (volume in book.records("volumes")) {
        for (chapter in volume.records("chapters")) {
            val path = "chapters/${hash(volume.string("id"))}-${hash(chapter.string("id"))}.md"
            put(path, "# ${chapter.string("title")}\n\n${chapter.string("text")}")
        }
    }
}
