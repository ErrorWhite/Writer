package xyz.qingjian.writer

import android.content.Context
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class SyncedBook(val endpoint: String, val revision: String?, val digest: String)

internal class BookSync(
    context: Context,
    private val studio: StudioServer,
    private val library: Library,
    private val documentLock: Mutex,
    private val flush: suspend (String) -> Unit,
    private val notice: (String) -> Unit
) {
    private val preferences = context.getSharedPreferences("book_sync_v1", Context.MODE_PRIVATE)
    private val network = Mutex()
    private val links = MutableStateFlow(preferences.all.mapNotNull { (id, value) ->
        runCatching {
            val item = JSONObject(value as String)
            id to SyncedBook(item.getString("endpoint"), item.string("revision").ifEmpty { null }, item.string("digest"))
        }.getOrNull()
    }.toMap())
    val enabled = links.asStateFlow()
    private val mutableStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val status = mutableStatus.asStateFlow()

    suspend fun enable(endpoint: String, id: String) = network.withLock {
        require(studio.connected(endpoint)) { "请先在设置中登录青简服务器。" }
        val remote = studio.syncBook(endpoint, id)
        val link = SyncedBook(endpoint, null, "")
        when {
            remote.status == 404 && remote.code == "book_missing" -> {
                saveLink(id, link)
                reconcile(id, link)
            }
            remote.book != null -> {
                val applied = adopt(id, link, remote, preserve = true)
                if (!remote.managed) {
                    val initial = applied.copy(digest = "")
                    saveLink(id, initial)
                    reconcile(id, initial)
                }
            }
            remote.status == 410 -> {
                val local = snapshot(id)
                documentLock.withLock { preserveConflict(id, local, "deleted") }
                error("原书已在服务器删除，已保留本地副本。")
            }
            else -> error("此青简服务器尚未启用跨端同步，或登录已过期。")
        }
    }

    suspend fun import(endpoint: String, id: String) = network.withLock {
        require(studio.connected(endpoint)) { "请先登录青简服务器。" }
        val remote = studio.syncBook(endpoint, id)
        check(remote.book != null) { "服务器上没有可同步的书稿。" }
        val applied = adopt(id, SyncedBook(endpoint, null, ""), remote, preserve = true)
        if (!remote.managed) {
            val initial = applied.copy(digest = "")
            saveLink(id, initial)
            reconcile(id, initial)
        }
    }

    suspend fun disable(id: String) = network.withLock {
        saveLink(id, null)
    }

    suspend fun delete(id: String) = network.withLock {
        val link = links.value[id] ?: return@withLock
        require(studio.connected(link.endpoint)) { "请先登录书稿绑定的服务器。" }
        val result = studio.writeSyncBook(link.endpoint, id, link.revision,
            "delete-" + syncHash(id + link.revision), book = null)
        check(result.status in 200..299 || result.status == 410) { "服务器书稿有更新，请同步后再删除。" }
        saveLink(id, null)
    }

    suspend fun syncPending(endpoint: String) = network.withLock {
        for ((id, link) in links.value) {
            try {
                if (endpoint != link.endpoint || !studio.connected(endpoint)) {
                    setStatus(id, "同步暂停，请登录青简服务器")
                    continue
                }
                reconcile(id, link)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                setStatus(id, "同步暂停 · " + (error.localizedMessage ?: "稍后重试"))
            }
        }
    }

    private suspend fun reconcile(id: String, link: SyncedBook) {
        val local = snapshot(id)
        val digest = bookDigest(local)
        if (digest == link.digest) {
            val remote = studio.syncBook(link.endpoint, id, link.revision)
            if (remote.unchanged) setStatus(id, "已同步")
            else adopt(id, link, remote, preserve = false)
            return
        }
        setStatus(id, "正在同步")
        val operation = "android-" + syncHash(id + link.revision + digest)
        val result = studio.writeSyncBook(link.endpoint, id, link.revision, operation, local)
        when (result.status) {
            in 200..299 -> saveLink(id, link.copy(revision = requireNotNull(result.revision), digest = digest))
            412 -> adopt(id, link, studio.syncBook(link.endpoint, id), preserve = true)
            410 -> adopt(id, link, result, preserve = true)
            else -> error("无法同步书稿，请检查青简服务器登录。")
        }
    }

    private suspend fun adopt(id: String, link: SyncedBook, remote: StudioSyncResult, preserve: Boolean): SyncedBook =
        documentLock.withLock {
            flush(id)
            val local = if (library.hasBook(id)) library.export(id) else null
            val localDigest = local?.let { bookDigest(it) }
            val incoming = remote.book?.let { Manuscript.read(it.toString()) }
            val incomingDigest = incoming?.let { bookDigest(it) }
            val changed = preserve || localDigest != link.digest
            if (remote.status == 410) {
                if (local != null && changed) preserveConflict(id, local, "deleted")
                if (local != null) library.remove(id)
                saveLink(id, null)
                setStatus(id, "服务器已删除书稿")
                return@withLock link
            }
            check(incoming != null && !remote.revision.isNullOrBlank()) { "无法读取服务器书稿版本。" }
            if (localDigest != incomingDigest) {
                if (local != null) {
                    if (changed) preserveConflict(id, local, remote.revision)
                    library.snapshotBook(id)
                    incoming.put("activeId", local.string("activeId"))
                    val valid = incoming.records("volumes").flatMap { it.records("chapters") }
                    if (valid.none { it.string("id") == incoming.string("activeId") }) {
                        incoming.put("activeId", valid.firstOrNull()?.string("id").orEmpty())
                    }
                }
                library.store(incoming, if (local == null) null else id)
            }
            val storedDigest = if (localDigest == incomingDigest) checkNotNull(localDigest) else bookDigest(library.export(id))
            link.copy(revision = remote.revision, digest = storedDigest).also { saveLink(id, it) }
        }

    private suspend fun preserveConflict(id: String, book: JSONObject, revision: String) {
        val copyId = "conflict-" + syncHash(id + revision + bookDigest(book)).take(48)
        if (!library.hasBook(copyId)) {
            val copy = JSONObject(book.toString()).put("id", copyId).put("title", book.string("title") + "（同步冲突）")
            library.store(copy, null)
            notice("两台设备都改了这本书，已保留本机冲突副本。")
        }
    }

    private suspend fun snapshot(id: String) = documentLock.withLock {
        flush(id)
        library.export(id)
    }

    private fun setStatus(id: String, value: String) { mutableStatus.update { it + (id to value) } }

    private suspend fun saveLink(id: String, link: SyncedBook?) {
        withContext(Dispatchers.IO) {
            val update = preferences.edit()
            if (link == null) update.remove(id)
            else update.putString(id, JSONObject().put("endpoint", link.endpoint)
                .put("revision", link.revision.orEmpty()).put("digest", link.digest).toString())
            check(update.commit()) { "无法保存同步状态。" }
        }
        links.update { if (link == null) it - id else it + (id to link) }
        setStatus(id, if (link == null) "未开启同步" else "已同步")
    }
}

private suspend fun bookDigest(book: JSONObject) = withContext(Dispatchers.Default) {
    val value = JSONObject(book.toString()).apply { remove("activeId") }
    syncHash(sortedJson(value))
}

internal suspend fun workbenchRevision(book: JSONObject) = withContext(Dispatchers.Default) {
    syncHash(sortedJson(book))
}

private fun sortedJson(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { key ->
        JSONObject.quote(key) + ":" + sortedJson(value.get(key))
    }
    is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { sortedJson(value.get(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}

private fun syncHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
