package xyz.qingjian.writer

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "books")
internal data class BookRow(
    @PrimaryKey val id: String,
    val title: String,
    val activeId: String,
    val updatedAt: Long,
    val cover: String,
    val payload: String
)

@Entity(
    tableName = "volumes",
    primaryKeys = ["bookId", "id"],
    foreignKeys = [ForeignKey(
        entity = BookRow::class, parentColumns = ["id"], childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )]
)
internal data class VolumeRow(val bookId: String, val id: String, val title: String, val position: Int, val payload: String)

@Entity(
    tableName = "chapters",
    primaryKeys = ["bookId", "id"],
    foreignKeys = [ForeignKey(
        entity = VolumeRow::class,
        parentColumns = ["bookId", "id"], childColumns = ["bookId", "volumeId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookId", "volumeId")]
)
internal data class ChapterRow(
    val bookId: String, val id: String, val volumeId: String, val title: String,
    val position: Int, val text: String, val complete: Boolean, val payload: String
)

@Entity(
    tableName = "chapter_versions",
    foreignKeys = [ForeignKey(
        entity = ChapterRow::class,
        parentColumns = ["bookId", "id"], childColumns = ["bookId", "chapterId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookId", "chapterId")]
)
internal data class ChapterVersion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val chapterId: String,
    val createdAt: Long,
    val title: String,
    val text: String,
    val complete: Boolean,
    val payload: String
)

internal data class ShelfEntry(val id: String, val title: String, val chapterCount: Int, val cover: String)
internal data class ChapterEntry(val id: String, val volumeId: String, val title: String, val complete: Boolean)

@Dao
internal interface LibraryDao {
    @Query("SELECT id, title, (SELECT COUNT(*) FROM chapters WHERE bookId = books.id) AS chapterCount, cover FROM books ORDER BY updatedAt DESC")
    fun shelf(): Flow<List<ShelfEntry>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun book(id: String): BookRow?

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeBook(id: String): Flow<BookRow?>

    @Query("SELECT * FROM books WHERE id = :id OR title = :title ORDER BY (id = :id) DESC, updatedAt DESC LIMIT 1")
    suspend fun matchingBook(id: String, title: String): BookRow?

    @Query("SELECT * FROM volumes WHERE bookId = :id ORDER BY position")
    suspend fun volumes(id: String): List<VolumeRow>

    @Query("SELECT * FROM volumes WHERE bookId = :id ORDER BY position")
    fun observeVolumes(id: String): Flow<List<VolumeRow>>

    @Query("SELECT * FROM chapters WHERE bookId = :id ORDER BY position")
    suspend fun chapters(id: String): List<ChapterRow>

    @Query("SELECT id, volumeId, title, complete FROM chapters WHERE bookId = :id ORDER BY position")
    fun observeDirectory(id: String): Flow<List<ChapterEntry>>

    @Query("SELECT * FROM chapters WHERE bookId = :id AND id = :chapterId")
    suspend fun chapter(id: String, chapterId: String): ChapterRow?

    @Query("SELECT * FROM chapters WHERE bookId = :id AND id = :chapterId")
    fun observeChapter(id: String, chapterId: String): Flow<ChapterRow?>

    @Upsert suspend fun putBook(book: BookRow)
    @Upsert suspend fun putVolume(volume: VolumeRow)
    @Upsert suspend fun putChapter(chapter: ChapterRow)

    @Query("UPDATE books SET activeId = :chapterId, updatedAt = :now WHERE id = :id")
    suspend fun selectChapter(id: String, chapterId: String, now: Long)

    @Query("UPDATE books SET updatedAt = :now WHERE id = :id")
    suspend fun touchBook(id: String, now: Long)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBook(id: String)

    @Query("DELETE FROM volumes WHERE bookId = :id AND id = :volumeId")
    suspend fun deleteVolume(id: String, volumeId: String)

    @Query("DELETE FROM chapters WHERE bookId = :id AND id = :chapterId")
    suspend fun deleteChapter(id: String, chapterId: String)

    @Query("SELECT * FROM chapter_versions WHERE bookId = :id AND chapterId = :chapterId ORDER BY id DESC")
    fun versions(id: String, chapterId: String): Flow<List<ChapterVersion>>

    @Query("SELECT * FROM chapter_versions WHERE bookId = :bookId AND chapterId = :chapterId AND id = :versionId")
    suspend fun version(bookId: String, chapterId: String, versionId: Long): ChapterVersion?

    @Insert suspend fun addVersion(version: ChapterVersion)

    @Query("DELETE FROM chapter_versions WHERE bookId = :id AND chapterId = :chapterId AND id NOT IN (SELECT id FROM chapter_versions WHERE bookId = :id AND chapterId = :chapterId ORDER BY id DESC LIMIT 12)")
    suspend fun pruneVersions(id: String, chapterId: String)
}

@Database(entities = [BookRow::class, VolumeRow::class, ChapterRow::class, ChapterVersion::class], version = 1)
internal abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
}

internal class Library(context: Context) {
    private val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "qingjian.db")
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    private val dao = database.library()

    val shelf = dao.shelf()
    fun book(id: String) = dao.observeBook(id)
    fun volumes(id: String) = dao.observeVolumes(id)
    fun directory(id: String) = dao.observeDirectory(id)
    fun chapter(bookId: String, chapterId: String) = dao.observeChapter(bookId, chapterId)
    fun versions(bookId: String, chapterId: String) = dao.versions(bookId, chapterId)

    suspend fun create(title: String) = store(Manuscript.create(title), null)

    suspend fun matchingBook(book: JSONObject) =
        dao.matchingBook(book.string("id"), book.string("title"))

    suspend fun store(book: JSONObject, replaceId: String?, copy: Boolean = false): String =
        write {
            val id = replaceId ?: if (copy) newId() else book.string("id")
            val payload = JSONObject(book.toString())
            val volumes = payload.records("volumes")
            payload.remove("volumes")
            val title = payload.remove("title") as String
            val activeId = payload.remove("activeId") as String
            payload.remove("id")
            val oldChapters = dao.chapters(id)
            val rows = volumes.mapIndexed { index, volume ->
                volumeRow(id, volume, index)
            }
            val chapters = volumes.flatMap { volume ->
                volume.records("chapters").mapIndexed { index, chapter ->
                    chapterRow(id, volume.string("id"), chapter, index)
                }
            }
            val cover = payload.string("cover")
            if (payload.opt("cover") is String) payload.remove("cover")
            dao.putBook(BookRow(id, title, activeId, System.currentTimeMillis(), cover, payload.toString()))
            for (row in rows) dao.putVolume(row)
            // Upsert imported chapters before removing obsolete volumes so surviving IDs keep history.
            for (chapter in chapters) dao.putChapter(chapter)
            val chapterIds = chapters.mapTo(HashSet()) { it.id }
            for (chapter in oldChapters) if (chapter.id !in chapterIds) dao.deleteChapter(id, chapter.id)
            val volumeIds = rows.mapTo(HashSet()) { it.id }
            for (volume in dao.volumes(id)) if (volume.id !in volumeIds) dao.deleteVolume(id, volume.id)
            id
        }

    suspend fun export(bookId: String) = database.useReaderConnection { reader -> reader.deferredTransaction {
        val book = requireNotNull(dao.book(bookId)) { "作品已删除。" }
        val chapters = dao.chapters(bookId).groupBy(ChapterRow::volumeId)
        val volumes = dao.volumes(bookId).map { volume ->
            JSONObject(volume.payload).put("id", volume.id).put("title", volume.title)
                .put("chapters", JSONArray(chapters[volume.id].orEmpty().map(::chapterJson)))
        }
        JSONObject(book.payload).put("id", book.id).put("title", book.title).put("activeId", book.activeId)
            .put("volumes", JSONArray(volumes)).apply {
                if (!has("cover")) put("cover", book.cover)
            }
    } }

    suspend fun select(bookId: String, chapterId: String) = write {
        requireNotNull(dao.chapter(bookId, chapterId)) { "章节已删除。" }
        dao.selectChapter(bookId, chapterId, System.currentTimeMillis())
    }

    suspend fun save(bookId: String, chapterId: String, text: String) = write {
        val chapter = requireNotNull(dao.chapter(bookId, chapterId)) { "章节已删除。" }
        if (chapter.text == text) return@write
        val updated = chapterJson(chapter)
        editChapter(updated, text)
        dao.putChapter(chapterRow(bookId, chapter.volumeId, updated, chapter.position))
        dao.touchBook(bookId, System.currentTimeMillis())
    }

    suspend fun addVolume(bookId: String, title: String) = write {
        dao.putVolume(volumeRow(bookId, Manuscript.newVolume(title), dao.volumes(bookId).size))
        dao.touchBook(bookId, System.currentTimeMillis())
    }

    suspend fun addChapter(bookId: String, volumeId: String, title: String) = write {
        val position = dao.chapters(bookId).count { it.volumeId == volumeId }
        val chapter = chapterRow(bookId, volumeId, Manuscript.newChapter(title), position)
        dao.putChapter(chapter)
        dao.selectChapter(bookId, chapter.id, System.currentTimeMillis())
    }

    suspend fun updateChapter(bookId: String, chapterId: String, title: String, complete: Boolean) =
        write {
            val chapter = requireNotNull(dao.chapter(bookId, chapterId)) { "章节已删除。" }
            dao.putChapter(chapter.copy(title = title, complete = complete))
            dao.touchBook(bookId, System.currentTimeMillis())
        }

    suspend fun rename(bookId: String, title: String) = updateBook(bookId) { it.copy(title = title) }

    suspend fun setCover(bookId: String, cover: String) = updateBook(bookId) {
        val payload = JSONObject(it.payload).apply { remove("cover") }
        it.copy(cover = cover, payload = payload.toString())
    }

    suspend fun updateBookText(bookId: String, key: String, text: String) = updateBook(bookId) {
        it.copy(payload = JSONObject(it.payload).put(key, text).toString())
    }

    suspend fun remove(bookId: String) = dao.deleteBook(bookId)

    private suspend fun updateBook(bookId: String, update: (BookRow) -> BookRow) = write {
        val book = requireNotNull(dao.book(bookId)) { "作品已删除。" }
        dao.putBook(update(book).copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun snapshot(bookId: String, chapterId: String) = write {
        val chapter = requireNotNull(dao.chapter(bookId, chapterId)) { "章节已删除。" }
        saveVersion(chapter)
    }

    suspend fun restore(bookId: String, chapterId: String, versionId: Long) = write {
        val chapter = requireNotNull(dao.chapter(bookId, chapterId)) { "章节已删除。" }
        val version = requireNotNull(dao.version(bookId, chapterId, versionId)) { "还原点已不存在。" }
        saveVersion(chapter)
        dao.putChapter(chapter.copy(title = version.title, text = version.text,
            complete = version.complete, payload = version.payload))
        dao.touchBook(bookId, System.currentTimeMillis())
    }

    private suspend fun saveVersion(chapter: ChapterRow) {
        dao.addVersion(ChapterVersion(
            bookId = chapter.bookId, chapterId = chapter.id, createdAt = System.currentTimeMillis(),
            title = chapter.title, text = chapter.text, complete = chapter.complete, payload = chapter.payload
        ))
        dao.pruneVersions(chapter.bookId, chapter.id)
    }

    private suspend fun <T> write(block: suspend () -> T): T =
        database.useWriterConnection { writer -> writer.immediateTransaction { block() } }

    private fun volumeRow(bookId: String, volume: JSONObject, position: Int): VolumeRow {
        val payload = JSONObject(volume.toString())
        payload.remove("chapters")
        return VolumeRow(bookId, payload.remove("id") as String, payload.remove("title") as String,
            position, payload.toString())
    }

    private fun chapterRow(bookId: String, volumeId: String, chapter: JSONObject, position: Int): ChapterRow {
        val payload = JSONObject(chapter.toString())
        val id = payload.remove("id") as String
        val title = payload.remove("title") as String
        val text = payload.remove("text") as String
        val complete = payload.remove("complete") == true
        return ChapterRow(bookId, id, volumeId, title, position, text, complete, payload.toString())
    }
}

internal fun chapterJson(chapter: ChapterRow): JSONObject = JSONObject(chapter.payload)
    .put("id", chapter.id).put("title", chapter.title).put("text", chapter.text).put("complete", chapter.complete)
