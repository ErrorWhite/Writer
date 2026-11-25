package xyz.qingjian.writer

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mangi.eta.data.provider.CommunityCatalog
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

private val Application.preferences by preferencesDataStore("qingjian")
private val themeKey = stringPreferencesKey("theme")
private val coverTypes = setOf("image/png", "image/jpeg", "image/webp")

internal enum class ThemeMode(val label: String) {
    System("跟随系统"), Light("浅色"), Dark("深色")
}

internal data class DraftKey(val bookId: String, val chapterId: String)
internal enum class SaveState { Saving, Saved, Failed }
internal data class Draft(val text: String, val state: SaveState = SaveState.Saving)
internal data class ImportPreview(val book: JSONObject, val existing: BookRow?) {
    val title = book.string("title")
    val chapterCount = book.records("volumes").sumOf { it.records("chapters").size }
    val annotationCount = book.optJSONObject("research")?.optJSONArray("annotations")?.length() ?: 0
    val foreshadowCount = book.optJSONObject("research")?.optJSONArray("foreshadows")?.length() ?: 0
}

internal class WriterModel(private val application: Application) : AndroidViewModel(application) {
    val library = Library(application)
    val gitHistory = ManuscriptGit(java.io.File(application.filesDir, "book-history"))
    private val resolver: ContentResolver = application.contentResolver
    private val lock = Mutex()
    private var writer: Job? = null
    private val mutableDrafts = MutableStateFlow<Map<DraftKey, Draft>>(emptyMap())
    val drafts = mutableDrafts.asStateFlow()
    private val mutableImport = MutableStateFlow<ImportPreview?>(null)
    val importPreview = mutableImport.asStateFlow()
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()
    private val vault = ProviderVault(application)
    private val client = ModelClient()
    private val studio = StudioServer(client)
    private val mutableAccounts = MutableStateFlow<List<ProviderAccount>?>(null)
    val accounts = mutableAccounts.asStateFlow()
    private val selectedAccountKey = stringPreferencesKey("active_provider_account")
    private val imagesKey = stringPreferencesKey("images_enabled")
    val imagesEnabled = application.preferences.data.map { it[imagesKey] == "true" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val studioEndpointKey = stringPreferencesKey("studio_endpoint")
    val studioEndpoint = application.preferences.data.map { it[studioEndpointKey].orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val mutableStudioStatus = MutableStateFlow<JSONObject?>(null)
    val studioStatus = mutableStudioStatus.asStateFlow()
    private val mutableStudioBooks = MutableStateFlow<List<StudioBook>?>(null)
    val studioBooks = mutableStudioBooks.asStateFlow()
    val activeAccount = application.preferences.data.map { it[selectedAccountKey] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val mutableGenerations = MutableStateFlow<Map<String, Generation>>(emptyMap())
    val generations = mutableGenerations.asStateFlow()
    private val requests = mutableMapOf<String, Job>()
    private val usageStorage = application.getSharedPreferences("model_usage", 0)
    private val mutableUsage = MutableStateFlow<List<UsageEntry>>(emptyList())
    val usage = mutableUsage.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                mutableAccounts.value = vault.load()
                mutableUsage.value = withContext(Dispatchers.IO) { readUsage() }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notice("无法读取本机模型账号。")
            }
        }
    }

    val theme = application.preferences.data.map { preferences ->
        ThemeMode.entries.firstOrNull { it.name == preferences[themeKey] } ?: ThemeMode.System
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.System)

    fun setTheme(mode: ThemeMode) = perform { application.preferences.edit { it[themeKey] = mode.name } }

    fun saveAccount(account: ProviderAccount) = perform {
        require(account.name.isNotBlank() && account.model.isNotBlank() && account.key.isNotBlank()) {
            "请填写账号名称、模型和 API Key。"
        }
        account.url("models")
        val current = requireNotNull(mutableAccounts.value) { "模型账号尚未加载。" }
        val updated = if (current.none { it.id == account.id }) current + account
            else current.map { if (it.id == account.id) account else it }
        vault.save(updated)
        mutableAccounts.value = updated
        if (activeAccount.value == null && account.enabled) selectAccount(account.id)
        notice("模型账号已保存")
    }

    fun removeAccount(id: String) = perform {
        val updated = requireNotNull(mutableAccounts.value).filter { it.id != id }
        vault.save(updated)
        mutableAccounts.value = updated
        if (activeAccount.value == id) selectAccount(updated.firstOrNull { it.enabled }?.id)
    }

    fun useAccount(id: String) = perform { selectAccount(id) }

    fun enableImages(enabled: Boolean) = perform { application.preferences.edit { it[imagesKey] = enabled.toString() } }

    private val mutableImage = MutableStateFlow<ImageCandidate?>(null)
    val imageCandidate = mutableImage.asStateFlow()
    private var imageJob: Job? = null

    fun generateImage(bookId: String, model: String, prompt: String) {
        if (imageJob?.isActive == true) return
        if (!imagesEnabled.value) return
        val account = mutableAccounts.value.orEmpty().firstOrNull {
            it.id == activeAccount.value && it.enabled && it.protocol == ModelProtocol.Responses
        }
        if (account == null) {
            notice("请先选择支持图像接口的 OpenAI 账号。")
            return
        }
        mutableImage.value = ImageCandidate(bookId, prompt, busy = true)
        imageJob = viewModelScope.launch {
            try {
                val image = client.image(account, model, prompt)
                val compressed = withContext(Dispatchers.Default) { compressImage(image) }
                mutableImage.value = ImageCandidate(bookId, prompt, bytes = compressed)
            } catch (error: CancellationException) {
                mutableImage.value = null
                throw error
            } catch (error: Exception) {
                mutableImage.value = ImageCandidate(bookId, prompt, error = error.localizedMessage ?: "配图失败。")
            }
        }
    }

    fun cancelImage() { imageJob?.cancel() }

    fun applyImage(bookId: String, cover: Boolean) = perform {
        val candidate = mutableImage.value?.takeIf { it.bookId == bookId } ?: return@perform
        val bytes = checkNotNull(candidate.bytes) { "尚未生成配图。" }
        val data = "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(bytes)}"
        if (cover) setCover(bookId, data)
        else mutateBook(bookId) {
            val assets = it.obj("creative").array("assets")
            require(assets.objects().sumOf { row -> row.string("data").toByteArray(Charsets.UTF_8).size } +
                data.length <= 2 * 1024 * 1024) { "资料附件总大小不能超过 2 MB。" }
            assets.put(JSONObject().put("id", newId()).put("title", "生成配图").put("type", "image")
                .put("group", "配图").put("data", data).put("summary", candidate.prompt.take(300))
                .put("source", "ai-image").put("updatedAt", System.currentTimeMillis()))
        }
        mutableImage.value = null
        notice(if (cover) "已采用为封面" else "已保存到资料附件")
    }

    fun useStudio() = perform {
        check(mutableStudioStatus.value?.optBoolean("codex") == true) { "请先在青简服务器启用已登录的 Codex 账号。" }
        selectAccount(STUDIO_ACCOUNT)
    }

    fun signInStudio(endpoint: String, user: String, password: String) = perform {
        val address = endpoint.trim().trimEnd('/')
        studio.signIn(address, user, password)
        if (studioEndpoint.value.isNotBlank() && studioEndpoint.value != address) {
            runCatching { studio.signOut(studioEndpoint.value) }
        }
        application.preferences.edit { it[studioEndpointKey] = address }
        mutableStudioStatus.value = studio.status(address)
        notice("已登录青简服务器")
    }

    fun signOutStudio() = perform {
        try {
            studio.signOut(studioEndpoint.value)
        } finally {
            mutableStudioStatus.value = null
            mutableStudioBooks.value = null
            if (activeAccount.value == STUDIO_ACCOUNT) selectAccount(null)
        }
    }

    fun refreshStudio() {
        if (!studio.connected(studioEndpoint.value)) return
        viewModelScope.launch {
            try {
                mutableStudioStatus.value = studio.status(studioEndpoint.value)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notice(error.localizedMessage ?: "读取服务器状态失败。")
            }
        }
    }

    fun studioAction(resource: String, action: String, id: String? = null, value: String? = null) = perform {
        val request = JSONObject().put("action", action).apply {
            if (id != null) put("id", id)
            if (value != null) put("name", value)
        }
        mutableStudioStatus.value = studio.action(studioEndpoint.value, resource, request)
    }

    fun configureStudio(model: String, effort: String, enabled: Boolean) = perform {
        mutableStudioStatus.value = studio.action(studioEndpoint.value, "settings",
            JSONObject().put("model", model).put("effort", effort).put("enabled", enabled))
    }

    fun listStudioBooks() = perform { mutableStudioBooks.value = studio.books(studioEndpoint.value) }

    fun closeStudioBooks() { mutableStudioBooks.value = null }

    fun previewStudioBook(id: String) = perform {
        val book = studio.book(studioEndpoint.value, id)
        closeStudioBooks()
        mutableImport.value = ImportPreview(book, matchingBook(book))
    }

    fun uploadStudioCopy(bookId: String) = perform {
        studio.uploadCopy(studioEndpoint.value, export(bookId))
        notice("已在青简服务器保存一份副本")
    }

    private suspend fun selectAccount(id: String?) {
        application.preferences.edit { if (id == null) it.remove(selectedAccountKey) else it[selectedAccountKey] = id }
    }

    fun listModels(account: ProviderAccount, complete: (Result<List<String>>) -> Unit) {
        viewModelScope.launch {
            try {
                complete(Result.success(client.models(account)))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                complete(Result.failure(error))
            }
        }
    }

    fun loadModelCatalog(complete: (Result<CommunityCatalog>) -> Unit) {
        viewModelScope.launch {
            try {
                complete(Result.success(client.catalog()))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                complete(Result.failure(error))
            }
        }
    }

    fun generate(
        id: String,
        intent: String,
        prepare: suspend Library.() -> Pair<String, List<ModelMessage>>,
        accept: suspend Library.(String) -> Unit = {}
    ) {
        if (requests[id]?.isActive == true) return
        val studioAddress = studioEndpoint.value.takeIf { activeAccount.value == STUDIO_ACCOUNT }
        val account = mutableAccounts.value.orEmpty().firstOrNull { it.id == activeAccount.value && it.enabled }
        if (studioAddress != null && !studio.connected(studioAddress)) {
            notice("请登录青简服务器，再使用 Codex。")
            return
        }
        if (account == null && studioAddress == null) {
            notice("请先在设置中启用并选中一个模型账号。")
            return
        }
        mutableGenerations.update { it + (id to Generation(running = true)) }
        requests[id] = viewModelScope.launch {
            try {
                val (system, messages) = lock.withLock {
                    flush()
                    check(mutableDrafts.value.values.none { it.state == SaveState.Failed }) { "请先保存正文。" }
                    library.prepare()
                }
                var meter: ModelUsage? = null
                val collectText: (String) -> Unit = { chunk -> updateGeneration(id) { it.copy(text = it.text + chunk) } }
                val collectReasoning: (String) -> Unit = { chunk ->
                    updateGeneration(id) { it.copy(reasoning = (it.reasoning + chunk).takeLast(12_000)) }
                }
                val text = if (studioAddress != null) studio.generate(studioAddress, intent, system, messages,
                    collectText, collectReasoning) { meter = it }
                else client.generate(requireNotNull(account), system, messages, collectText, collectReasoning) { meter = it }
                lock.withLock {
                    flush()
                    library.accept(text)
                    meter?.let {
                        val serverModel = mutableStudioStatus.value?.optJSONObject("cli")?.string("model").orEmpty()
                        val entry = UsageEntry(java.time.LocalDate.now().toString(), intent,
                            account?.name ?: "青简 Codex", account?.model ?: serverModel,
                            it.input, it.output, it.cached)
                        val updated = (mutableUsage.value + entry).takeLast(2000)
                        withContext(Dispatchers.IO) {
                            usageStorage.edit().putString("entries", JSONArray(updated.map(UsageEntry::json)).toString()).commit()
                        }
                        mutableUsage.value = updated
                    }
                }
                updateGeneration(id) { it.copy(running = false, completed = true) }
            } catch (error: CancellationException) {
                updateGeneration(id) { it.copy(running = false, error = "已停止") }
                throw error
            } catch (error: Exception) {
                updateGeneration(id) { it.copy(running = false, error = error.localizedMessage ?: "模型请求失败。") }
            } finally {
                requests.remove(id)
            }
        }
    }

    fun stopGeneration(id: String) { requests[id]?.cancel() }

    fun clearGeneration(id: String) {
        if (requests[id]?.isActive != true) mutableGenerations.update { it - id }
    }

    private fun updateGeneration(id: String, transform: (Generation) -> Generation) {
        mutableGenerations.update { values -> values[id]?.let { values + (id to transform(it)) } ?: values }
    }

    private fun readUsage() = JSONArray(usageStorage.getString("entries", "[]")).objects().map { row ->
        fun number(key: String) = (row.opt(key) as? Number)?.toInt()
        UsageEntry(row.string("day"), row.string("intent"), row.string("account"), row.string("model"),
            number("input"), number("output"), number("cached"))
    }

    fun importRoleplayCard(bookId: String, uri: Uri, opened: (String) -> Unit) = perform {
        val card = withContext(Dispatchers.IO) {
            readRoleplayCard(read(uri, 32, "角色卡不能超过 32 MB。"))
        }
        val session = newRoleplaySession(card, card.string("greeting"))
        mutateBook(bookId) { payload -> roleplay(payload).apply {
            optJSONArray("cards")!!.put(card)
            optJSONArray("sessions")!!.put(session)
        } }
        opened(session.string("id"))
    }

    fun importSkill(bookId: String, uri: Uri) = perform {
        val record = withContext(Dispatchers.IO) {
            readSkillPackage(fileName(uri), read(uri, 3, "技能包不能超过 3 MB。"))
        }
        mutateBook(bookId) { it.obj("skills").array("custom").put(record) }
        notice("已导入写作技能")
    }

    fun importAsset(bookId: String, uri: Uri) = perform {
        val asset = withContext(Dispatchers.IO) {
            val stream = requireNotNull(resolver.openInputStream(uri)) { "无法打开附件。" }
            val bytes = stream.use { it.readLimited(640 * 1024, "资料附件不能超过 640 KB。") }
            val mime = resolver.getType(uri)
            val image = mime in coverTypes || mime == "image/gif"
            val content = if (image) "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}" else bytes.toString(Charsets.UTF_8)
            require(content.toByteArray(Charsets.UTF_8).size <= 640 * 1024) { "资料附件不能超过 640 KB。" }
            JSONObject().put("id", newId()).put("title", fileName(uri)).put("type", if (image) "image" else "markdown")
                .put("group", "").put("summary", "").put("data", content).put("updatedAt", System.currentTimeMillis())
        }
        mutateBook(bookId) {
            val assets = it.obj("creative").array("assets")
            val bytes = assets.objects().sumOf { row -> row.string("data").toByteArray(Charsets.UTF_8).size }
            require(bytes + asset.string("data").toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) {
                "当前作品的资料附件总大小不能超过 2 MB。"
            }
            assets.put(asset)
        }
        notice("已添加附件")
    }

    private fun fileName(uri: Uri): String = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
        null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "附件"

    fun exportRoleplay(uri: Uri, data: () -> ByteArray) = perform {
        withContext(Dispatchers.IO) {
            val stream = requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法打开导出文件。" }
            stream.use { it.write(data()) }
        }
        notice("已导出")
    }

    fun edit(key: DraftKey, text: String) {
        mutableDrafts.value += key to Draft(text)
        savePending()
    }

    fun retry(key: DraftKey) {
        val draft = mutableDrafts.value[key] ?: return
        mutableDrafts.value += key to draft.copy(state = SaveState.Saving)
        savePending()
    }

    fun acknowledge(key: DraftKey, storedText: String) {
        val draft = mutableDrafts.value[key] ?: return
        if (draft.state == SaveState.Saved && draft.text == storedText) mutableDrafts.value -= key
    }

    private fun savePending() {
        if (writer?.isActive == true) return
        writer = viewModelScope.launch { lock.withLock { flush() } }
    }

    private suspend fun flush() {
        while (true) {
            val (key, draft) = mutableDrafts.value.entries.firstOrNull { it.value.state == SaveState.Saving }
                ?: return
            val state = try {
                library.save(key.bookId, key.chapterId, draft.text)
                SaveState.Saved
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice("正文保存失败，请重试。")
                SaveState.Failed
            }
            if (mutableDrafts.value[key] === draft) mutableDrafts.value += key to draft.copy(state = state)
        }
    }

    fun perform(action: suspend Library.() -> Unit) {
        viewModelScope.launch {
            lock.withLock {
                flush()
                if (mutableDrafts.value.values.any { it.state == SaveState.Failed }) {
                    notice("请重试保存当前正文后再继续。")
                    return@withLock
                }
                try {
                    library.action()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    notice(error.localizedMessage ?: "操作失败。")
                }
            }
        }
    }

    fun previewImport(uri: Uri) = perform {
        val book = withContext(Dispatchers.IO) {
            val content = read(uri, 64, "备份不能超过 64 MB。")
            try {
                Manuscript.read(content.toString(Charsets.UTF_8))
            } catch (error: org.json.JSONException) {
                throw IllegalArgumentException("无法读取青简全书 JSON 备份。", error)
            }
        }
        mutableImport.value = ImportPreview(book, matchingBook(book))
    }

    fun dismissImport() { mutableImport.value = null }

    fun acceptImport(overwrite: Boolean, opened: (String) -> Unit) = perform {
        val preview = mutableImport.value ?: return@perform
        val existing = matchingBook(preview.book)
        if (existing?.id != preview.existing?.id) {
            mutableImport.value = ImportPreview(preview.book, existing)
            return@perform
        }
        val replace = existing?.id?.takeIf { overwrite }
        val id = store(preview.book, replace, copy = existing != null && !overwrite)
        if (replace != null) mutableDrafts.value = mutableDrafts.value.filterKeys { it.bookId != replace }
        mutableImport.value = null
        opened(id)
        notice("备份已导入")
    }

    fun writeExport(uri: Uri, bookId: String, format: ExportFormat, chapterId: String?) = perform {
        val book = export(bookId)
        withContext(Dispatchers.IO) {
            val stream = requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法打开导出文件。" }
            stream.buffered().use { format.write(book, chapterId, it) }
        }
        notice("已导出")
    }

    fun importCover(bookId: String, uri: Uri) = perform {
        val cover = withContext(Dispatchers.IO) {
            val bytes = read(uri, 6, "封面不能超过 6 MB。")
            val mime = resolver.getType(uri)
            require(mime in coverTypes) { "请选择 PNG、JPEG 或 WebP 图片。" }
            "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
        }
        setCover(bookId, cover)
    }

    fun restoreChapter(bookId: String, chapterId: String, versionId: Long, restored: () -> Unit) = perform {
        val key = DraftKey(bookId, chapterId)
        val draft = mutableDrafts.value[key]
        restore(bookId, chapterId, versionId)
        if (mutableDrafts.value[key] === draft) mutableDrafts.value -= key
        restored()
        notice("已还原章节")
    }

    fun restoreBookVersion(bookId: String, versionId: Long, restored: () -> Unit) = perform {
        val drafts = mutableDrafts.value
        restoreBook(bookId, versionId)
        mutableDrafts.value = mutableDrafts.value.filter { (key, value) -> key.bookId != bookId || drafts[key] !== value }
        restored()
        notice("已还原全书")
    }

    fun restoreGitVersion(bookId: String, oid: String, restored: () -> Unit) = perform {
        val drafts = mutableDrafts.value
        val snapshot = gitHistory.snapshot(bookId, oid)
        snapshotBook(bookId)
        store(snapshot, bookId)
        mutableDrafts.value = mutableDrafts.value.filter { (key, value) -> key.bookId != bookId || drafts[key] !== value }
        restored()
        notice("已还原 Git 版本")
    }

    fun deleteBook(bookId: String, deleted: () -> Unit) = perform {
        remove(bookId)
        mutableDrafts.value = mutableDrafts.value.filterKeys { it.bookId != bookId }
        deleted()
    }

    private fun read(uri: Uri, limitMb: Int, errorMessage: String): ByteArray {
        val stream = requireNotNull(resolver.openInputStream(uri)) { "无法打开文件。" }
        return stream.use { it.readLimited(limitMb * 1024 * 1024, errorMessage) }
    }

    fun notice(message: String?) { mutableNotice.value = message }
}

internal const val STUDIO_ACCOUNT = "qingjian-codex-server"

internal data class ImageCandidate(val bookId: String, val prompt: String, val busy: Boolean = false,
    val bytes: ByteArray? = null, val error: String? = null)

private fun compressImage(bytes: ByteArray): ByteArray {
    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    require(options.outWidth in 1..8192 && options.outHeight in 1..8192) { "生成的图片尺寸无效。" }
    options.inJustDecodeBounds = false
    options.inSampleSize = (maxOf(options.outWidth, options.outHeight) / 1536).coerceAtLeast(1)
    val decoded = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) {
        "无法读取生成的图片。"
    }
    try {
        for (quality in listOf(88, 76, 64, 48, 32)) {
            val output = ByteArrayOutputStream()
            check(decoded.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, output)) { "图片压缩失败。" }
            if (output.size() <= 470 * 1024) return output.toByteArray()
        }
        error("图片压缩后仍超过附件限制。")
    } finally {
        decoded.recycle()
    }
}

internal data class Generation(val running: Boolean, val text: String = "", val reasoning: String = "",
    val completed: Boolean = false, val error: String? = null)

internal data class UsageEntry(
    val day: String, val intent: String, val account: String, val model: String,
    val input: Int?, val output: Int?, val cached: Int?
) {
    fun json() = JSONObject().put("day", day).put("intent", intent).put("account", account).put("model", model)
        .put("input", input ?: JSONObject.NULL).put("output", output ?: JSONObject.NULL).put("cached", cached ?: JSONObject.NULL)
}

internal fun InputStream.readLimited(limit: Int, message: String): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count == -1) break
        require(result.size() + count <= limit) { message }
        result.write(buffer, 0, count)
    }
    return result.toByteArray()
}

internal fun coverImage(cover: String): ByteArray? {
    val separator = cover.indexOf(";base64,")
    if (separator == -1 || !cover.startsWith("data:") || cover.substring(5, separator) !in coverTypes) return null
    return runCatching { Base64.getDecoder().decode(cover.substring(separator + 8)) }.getOrNull()
}
