package xyz.qingjian.writer

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
    private val resolver: ContentResolver = application.contentResolver
    private val lock = Mutex()
    private var writer: Job? = null
    private val mutableDrafts = MutableStateFlow<Map<DraftKey, Draft>>(emptyMap())
    val drafts = mutableDrafts.asStateFlow()
    private val mutableImport = MutableStateFlow<ImportPreview?>(null)
    val importPreview = mutableImport.asStateFlow()
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()

    val theme = application.preferences.data.map { preferences ->
        ThemeMode.entries.firstOrNull { it.name == preferences[themeKey] } ?: ThemeMode.System
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.System)

    fun setTheme(mode: ThemeMode) = perform { application.preferences.edit { it[themeKey] = mode.name } }

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
