package xyz.qingjian.writer

import android.content.Context
import androidx.room.ColumnInfo
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
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
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
    val position: Int, val text: String, val complete: Boolean, val payload: String,
    @ColumnInfo(defaultValue = "0") val wordCount: Int
)

@Entity(
    tableName = "writing_journal",
    primaryKeys = ["bookId", "chapterId"],
    foreignKeys = [ForeignKey(
        entity = ChapterRow::class, parentColumns = ["bookId", "id"],
        childColumns = ["bookId", "chapterId"], onDelete = ForeignKey.CASCADE
    )]
)
internal data class JournalEntry(val bookId: String, val chapterId: String, val text: String)

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

@Entity(
    tableName = "book_versions",
    foreignKeys = [ForeignKey(entity = BookRow::class, parentColumns = ["id"], childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")]
)
internal data class BookVersion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val createdAt: Long,
    val snapshot: String
)

internal data class BookVersionEntry(val id: Long, val createdAt: Long)

internal data class ShelfEntry(
    val id: String, val title: String, val chapterCount: Int, val cover: String, val activeChapterTitle: String?
)
internal data class ChapterEntry(
    val id: String, val volumeId: String, val title: String, val complete: Boolean,
    val wordCount: Int, val goal: Int
)
internal data class ChapterStory(val id: String, val title: String, val volumeId: String, val payload: String)
internal const val standaloneRoleplayId = "_qingjian_standalone_roleplay"

@Dao
internal interface LibraryDao {
    @Upsert suspend fun stage(entry: JournalEntry)

    @Query("SELECT * FROM writing_journal")
    suspend fun pendingEdits(): List<JournalEntry>

    @Query("SELECT * FROM writing_journal WHERE bookId = :bookId AND chapterId = :chapterId")
    suspend fun pendingEdit(bookId: String, chapterId: String): JournalEntry?

    @Query("DELETE FROM writing_journal WHERE bookId = :bookId AND chapterId = :chapterId")
    suspend fun clearEdit(bookId: String, chapterId: String)

    @Query("""
        SELECT id, title, cover,
          (SELECT COUNT(*) FROM chapters WHERE bookId = books.id) AS chapterCount,
          (SELECT title FROM chapters WHERE bookId = books.id AND id = books.activeId) AS activeChapterTitle
        FROM books WHERE id <> '_qingjian_standalone_roleplay' ORDER BY updatedAt DESC
    """)
    fun shelf(): Flow<List<ShelfEntry>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun book(id: String): BookRow?

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeBook(id: String): Flow<BookRow?>

    @Query("SELECT * FROM books WHERE id <> '_qingjian_standalone_roleplay' AND (id = :id OR title = :title) ORDER BY (id = :id) DESC, updatedAt DESC LIMIT 1")
    suspend fun matchingBook(id: String, title: String): BookRow?

    @Query("SELECT * FROM volumes WHERE bookId = :id ORDER BY position")
    suspend fun volumes(id: String): List<VolumeRow>

    @Query("SELECT * FROM volumes WHERE bookId = :id ORDER BY position")
    fun observeVolumes(id: String): Flow<List<VolumeRow>>

    @Query("SELECT * FROM chapters WHERE bookId = :id ORDER BY position")
    suspend fun chapters(id: String): List<ChapterRow>

    @Query("""
        SELECT id, volumeId, title, complete, wordCount,
          CAST(COALESCE(json_extract(payload, '$.goal'), 0) AS INTEGER) AS goal
        FROM chapters WHERE bookId = :id ORDER BY position
    """)
    fun observeDirectory(id: String): Flow<List<ChapterEntry>>

    @Query("SELECT id FROM chapters WHERE bookId = :id AND (instr(lower(title), lower(:query)) > 0 OR instr(lower(text), lower(:query)) > 0)")
    fun searchChapters(id: String, query: String): Flow<List<String>>

    @Query("SELECT chapters.id, chapters.title, chapters.volumeId, chapters.payload FROM chapters JOIN volumes ON chapters.bookId = volumes.bookId AND chapters.volumeId = volumes.id WHERE chapters.bookId = :id AND complete = 1 ORDER BY volumes.position, chapters.position")
    fun observeStory(id: String): Flow<List<ChapterStory>>

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

    @Query("SELECT id, createdAt FROM book_versions WHERE bookId = :bookId ORDER BY id DESC")
    fun bookVersions(bookId: String): Flow<List<BookVersionEntry>>

    @Query("SELECT * FROM book_versions WHERE bookId = :bookId AND id = :id")
    suspend fun bookVersion(bookId: String, id: Long): BookVersion?

    @Insert suspend fun addBookVersion(version: BookVersion)

    @Query("DELETE FROM book_versions WHERE bookId = :bookId AND id NOT IN (SELECT id FROM book_versions WHERE bookId = :bookId ORDER BY id DESC LIMIT 12)")
    suspend fun pruneBookVersions(bookId: String)
}

@Database(
    entities = [BookRow::class, VolumeRow::class, ChapterRow::class, ChapterVersion::class, BookVersion::class, JournalEntry::class],
    version = 3
)
internal abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
}

private val writingJournalMigration = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """CREATE TABLE IF NOT EXISTS writing_journal (
                bookId TEXT NOT NULL, chapterId TEXT NOT NULL, text TEXT NOT NULL,
                PRIMARY KEY (bookId, chapterId),
                FOREIGN KEY (bookId, chapterId) REFERENCES chapters (bookId, id) ON DELETE CASCADE
            )"""
        )
    }
}

private val chapterWordCountMigration = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE chapters ADD COLUMN wordCount INTEGER NOT NULL DEFAULT 0")
        connection.prepare("SELECT bookId, id, text FROM chapters").use { rows ->
            connection.prepare("UPDATE chapters SET wordCount = ? WHERE bookId = ? AND id = ?").use { update ->
                while (rows.step()) {
                    update.bindInt(1, manuscriptWordCount(rows.getText(2)))
                    update.bindText(2, rows.getText(0))
                    update.bindText(3, rows.getText(1))
                    update.step()
                    update.reset()
                }
            }
        }
    }
}

internal class Library(context: Context) {
    private val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "qingjian.db")
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(writingJournalMigration, chapterWordCountMigration)
        .build()
    private val dao = database.library()

    val shelf = dao.shelf()
    fun book(id: String) = dao.observeBook(id)
    fun volumes(id: String) = dao.observeVolumes(id)
    fun directory(id: String) = dao.observeDirectory(id)
    fun searchChapters(id: String, query: String) = dao.searchChapters(id, query)
    fun story(id: String) = dao.observeStory(id)
    fun chapter(bookId: String, chapterId: String) = dao.observeChapter(bookId, chapterId)
    fun versions(bookId: String, chapterId: String) = dao.versions(bookId, chapterId)
    fun bookVersions(bookId: String) = dao.bookVersions(bookId)

    suspend fun create(title: String) = store(Manuscript.create(title), null)

    suspend fun standaloneRoleplay() = write {
        if (dao.book(standaloneRoleplayId) == null) {
            val data = JSONObject()
            roleplay(data)
            dao.putBook(BookRow(standaloneRoleplayId, "文本扮演", "", System.currentTimeMillis(), "", data.toString()))
        }
    }

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

    suspend fun export(bookId: String) = database.useReaderConnection { reader ->
        reader.deferredTransaction { exportSnapshot(bookId) }
    }

    private suspend fun exportSnapshot(bookId: String): JSONObject {
        val book = requireNotNull(dao.book(bookId)) { "作品已删除。" }
        val chapters = dao.chapters(bookId).groupBy(ChapterRow::volumeId)
        val volumes = dao.volumes(bookId).map { volume ->
            JSONObject(volume.payload).put("id", volume.id).put("title", volume.title)
                .put("chapters", JSONArray(chapters[volume.id].orEmpty().map(::chapterJson)))
        }
        return JSONObject(book.payload).put("id", book.id).put("title", book.title).put("activeId", book.activeId)
            .put("volumes", JSONArray(volumes)).apply {
                if (!has("cover")) put("cover", book.cover)
            }
    }

    suspend fun select(bookId: String, chapterId: String) = write {
        requireNotNull(dao.chapter(bookId, chapterId)) { "章节已删除。" }
        dao.selectChapter(bookId, chapterId, System.currentTimeMillis())
    }

    suspend fun hasBook(id: String) = dao.book(id) != null

    suspend fun chapterText(bookId: String, chapterId: String) = dao.chapter(bookId, chapterId)?.text.orEmpty()

    suspend fun readChapter(bookId: String, chapterId: String): JSONObject =
        chapterJson(requireNotNull(dao.chapter(bookId, chapterId)) { "锚点所在章节已删除。" })

    suspend fun stageEdit(bookId: String, chapterId: String, text: String) = write {
        dao.stage(JournalEntry(bookId, chapterId, text))
    }

    suspend fun replayEdits() {
        for (entry in dao.pendingEdits()) commitEdit(entry.bookId, entry.chapterId)
    }

    suspend fun commitEdit(bookId: String, chapterId: String) = write {
        val entry = dao.pendingEdit(bookId, chapterId) ?: return@write
        val chapter = requireNotNull(dao.chapter(bookId, chapterId)) { "章节已删除。" }
        if (chapter.text != entry.text) {
            val updated = chapterJson(chapter)
            editChapter(updated, entry.text)
            dao.putChapter(chapterRow(bookId, chapter.volumeId, updated, chapter.position))
            dao.touchBook(bookId, System.currentTimeMillis())
        }
        dao.clearEdit(bookId, chapterId)
    }

    suspend fun addVolume(bookId: String, title: String, direction: String = "") = write {
        val volume = Manuscript.newVolume(title).apply { if (direction.isNotBlank()) put("direction", direction) }
        dao.putVolume(volumeRow(bookId, volume, dao.volumes(bookId).size))
        dao.touchBook(bookId, System.currentTimeMillis())
    }

    suspend fun acceptVolumePlan(
        bookId: String, chapterId: String, review: JSONObject, archived: JSONObject, proposal: JSONObject
    ) = write {
        val title = proposal.string("title").trim()
        require(title.isNotBlank()) { "卷名不能为空。" }
        val chapter = requireNotNull(dao.chapter(bookId, chapterId)) { "章节已删除。" }
        val saved = chapterJson(chapter)
        check(pendingWritingReview(saved)?.toString() == review.toString()) { "候选稿已经变更。" }
        val volume = Manuscript.newVolume(title).put("direction", proposal.string("direction"))
        saved.remove("nativeReview")
        saved.put("nativePreviousReview", archived)
        dao.putVolume(volumeRow(bookId, volume, dao.volumes(bookId).size))
        dao.putChapter(chapterRow(bookId, chapter.volumeId, saved, chapter.position))
        dao.touchBook(bookId, System.currentTimeMillis())
    }

    suspend fun addChapter(
        bookId: String, volumeId: String, title: String, plan: JSONObject? = null, afterChapterId: String? = null
    ) = write {
        require(dao.volumes(bookId).any { it.id == volumeId }) { "目标卷已删除。" }
        val siblings = dao.chapters(bookId).filter { it.volumeId == volumeId }
        val position = if (afterChapterId == null) siblings.size else
            siblings.indexOfFirst { it.id == afterChapterId }.also { require(it >= 0) { "上一章已经移走或删除。" } } + 1
        val source = Manuscript.newChapter(title).apply { if (plan != null) put("plan", plan) }
        val chapter = chapterRow(bookId, volumeId, source, position)
        for (sibling in siblings.drop(position)) dao.putChapter(sibling.copy(position = sibling.position + 1))
        dao.putChapter(chapter)
        dao.selectChapter(bookId, chapter.id, System.currentTimeMillis())
        chapter.id
    }

    suspend fun renameChapter(bookId: String, chapterId: String, title: String) = write {
        val chapter = requireNotNull(dao.chapter(bookId, chapterId)) { "章节已删除。" }
        if (chapter.title == title) return@write
        saveVersion(chapter)
        dao.putChapter(chapter.copy(title = title))
        dao.touchBook(bookId, System.currentTimeMillis())
    }

    suspend fun renameVolume(bookId: String, volumeId: String, title: String) = write {
        val volume = dao.volumes(bookId).first { it.id == volumeId }
        dao.putVolume(volume.copy(title = title))
        dao.touchBook(bookId, System.currentTimeMillis())
    }

    suspend fun moveVolume(bookId: String, volumeId: String, offset: Int) = write {
        val list = dao.volumes(bookId)
        val from = list.indexOfFirst { it.id == volumeId }
        val to = from + offset
        if (from < 0 || to !in list.indices) return@write
        dao.putVolume(list[from].copy(position = list[to].position))
        dao.putVolume(list[to].copy(position = list[from].position))
        dao.touchBook(bookId, System.currentTimeMillis())
    }

    suspend fun moveChapter(bookId: String, chapterId: String, targetVolume: String, offset: Int? = null) = write {
        val chapter = requireNotNull(dao.chapter(bookId, chapterId)) { "章节已删除。" }
        val list = dao.chapters(bookId)
        val source = list.filter { it.volumeId == chapter.volumeId }.toMutableList()
        val index = source.indexOfFirst { it.id == chapterId }
        if (targetVolume == chapter.volumeId) {
            val target = index + (offset ?: 0)
            if (target !in source.indices || target == index) return@write
            source.removeAt(index)
            source.add(target, chapter)
            source.forEachIndexed { position, row -> dao.putChapter(row.copy(position = position)) }
        } else {
            val destination = list.filter { it.volumeId == targetVolume }
            source.removeAt(index)
            dao.putChapter(chapter.copy(volumeId = targetVolume, position = destination.size))
            source.forEachIndexed { position, row -> dao.putChapter(row.copy(position = position)) }
        }
        dao.touchBook(bookId, System.currentTimeMillis())
    }

    suspend fun removeChapter(bookId: String, chapterId: String) = write {
        val volumes = dao.volumes(bookId)
        val byVolume = dao.chapters(bookId).groupBy(ChapterRow::volumeId)
        val ordered = volumes.flatMap { byVolume[it.id].orEmpty() }
        require(ordered.size > 1) { "作品至少需要保留一个章节。" }
        val index = ordered.indexOfFirst { it.id == chapterId }
        require(index >= 0) { "章节已删除。" }
        val chapter = ordered[index]
        dao.deleteChapter(bookId, chapterId)
        byVolume[chapter.volumeId].orEmpty().filter { it.id != chapterId }.forEachIndexed { position, row ->
            dao.putChapter(row.copy(position = position))
        }
        val now = System.currentTimeMillis()
        if (dao.book(bookId)?.activeId == chapterId) {
            val next = ordered.getOrNull(index + 1) ?: ordered[index - 1]
            dao.selectChapter(bookId, next.id, now)
        } else dao.touchBook(bookId, now)
    }

    suspend fun removeEmptyVolume(bookId: String, volumeId: String) = write {
        require(dao.chapters(bookId).none { it.volumeId == volumeId }) { "请先将卷内章节移至其他卷。" }
        dao.deleteVolume(bookId, volumeId)
        dao.volumes(bookId).forEachIndexed { position, volume -> dao.putVolume(volume.copy(position = position)) }
        dao.touchBook(bookId, System.currentTimeMillis())
    }

    suspend fun updateChapter(bookId: String, chapterId: String, title: String, complete: Boolean, goal: Int?) =
        write {
            val chapter = requireNotNull(dao.chapter(bookId, chapterId)) { "章节已删除。" }
            val payload = JSONObject(chapter.payload).apply {
                if (goal == null) remove("goal") else put("goal", goal)
            }
            dao.putChapter(chapter.copy(title = title, complete = complete, payload = payload.toString()))
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

    suspend fun mutateBook(bookId: String, change: (JSONObject) -> Unit) = updateBook(bookId) {
        val payload = JSONObject(it.payload)
        change(payload)
        it.copy(payload = payload.toString())
    }

    suspend fun applyChapterCandidate(bookId: String, chapterId: String, previous: String, next: String) = write {
        val chapter = requireNotNull(dao.chapter(bookId, chapterId)) { "章节已删除。" }
        check(chapter.text == previous) { "正文已修改，请重新比对候选稿。" }
        saveVersion(chapter)
        val updated = chapterJson(chapter)
        editChapter(updated, next)
        dao.putChapter(chapterRow(bookId, chapter.volumeId, updated, chapter.position))
        dao.touchBook(bookId, System.currentTimeMillis())
    }

    suspend fun mutateChapter(
        bookId: String, chapterId: String, bookEdit: ((JSONObject) -> Unit)? = null,
        change: (JSONObject) -> Unit
    ) = write {
        val chapter = requireNotNull(dao.chapter(bookId, chapterId)) { "章节已删除。" }
        val value = chapterJson(chapter)
        change(value)
        if (chapter.text != value.string("text")) saveVersion(chapter)
        dao.putChapter(chapterRow(bookId, chapter.volumeId, value, chapter.position))
        if (bookEdit == null) dao.touchBook(bookId, System.currentTimeMillis())
        else {
            val book = requireNotNull(dao.book(bookId))
            val payload = JSONObject(book.payload)
            bookEdit(payload)
            dao.putBook(book.copy(payload = payload.toString(), updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun closeChapter(
        bookId: String, chapterId: String, baseline: String, review: JSONObject?, proposed: JSONObject,
        archivedReview: JSONObject? = null
    ) = write {
        val chapter = requireNotNull(dao.chapter(bookId, chapterId))
        val book = requireNotNull(dao.book(bookId))
        val data = JSONObject(book.payload)
        val saved = chapterJson(chapter)
        check(chapter.text == baseline &&
            (review == null || saved.optJSONObject("nativeReview")?.toString() == review.toString())) {
            "正文或候选稿已变更，请重新比对。"
        }
        val handoff = checkedHandoff(proposed, saved)
        saved.optJSONObject("handoff")?.optJSONArray("archiveDelta")?.let { undoHandoffArchive(data, it) }
        val changes = archiveHandoff(data, saved, handoff)
        handoff.put("archiveVersion", 1).put("archiveDelta", changes).put("at", System.currentTimeMillis())
        saved.put("handoff", handoff).put("complete", true)
        if (review != null) saved.remove("nativeReview")
        if (archivedReview != null) saved.put("nativePreviousReview", archivedReview)
        saveVersion(chapter)
        dao.putChapter(chapterRow(bookId, chapter.volumeId, saved, chapter.position))
        dao.putBook(book.copy(payload = data.toString(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun undoChapterArchive(bookId: String, chapterId: String) = write {
        val chapter = requireNotNull(dao.chapter(bookId, chapterId))
        val book = requireNotNull(dao.book(bookId))
        val saved = chapterJson(chapter)
        val handoff = requireNotNull(saved.optJSONObject("handoff")) { "本章还没有交接。" }
        val delta = requireNotNull(handoff.optJSONArray("archiveDelta")) { "本章没有可撤销的归档记录。" }
        val data = JSONObject(book.payload)
        undoHandoffArchive(data, delta)
        handoff.remove("archiveDelta")
        handoff.remove("archiveVersion")
        dao.putChapter(chapterRow(bookId, chapter.volumeId, saved, chapter.position))
        dao.putBook(book.copy(payload = data.toString(), updatedAt = System.currentTimeMillis()))
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

    suspend fun snapshotBook(bookId: String) = write { saveBookVersion(bookId) }

    suspend fun replaceFromBackup(bookId: String, backup: JSONObject): String = write {
        saveBookVersion(bookId)
        store(backup, bookId)
    }

    suspend fun snapshotChapter(bookId: String, chapterId: String) = write {
        saveVersion(requireNotNull(dao.chapter(bookId, chapterId)) { "章节已删除。" })
    }

    suspend fun readBookVersion(bookId: String, versionId: Long): JSONObject =
        JSONObject(requireNotNull(dao.bookVersion(bookId, versionId)) { "还原点已不存在。" }.snapshot)

    suspend fun readChapterVersion(bookId: String, chapterId: String, versionId: Long): JSONObject {
        val version = requireNotNull(dao.version(bookId, chapterId, versionId)) { "还原点已不存在。" }
        return JSONObject(version.payload).put("id", chapterId).put("title", version.title)
            .put("text", version.text).put("complete", version.complete)
    }

    suspend fun restoreBook(bookId: String, id: Long) = write {
        val version = requireNotNull(dao.bookVersion(bookId, id)) { "还原点已不存在。" }
        saveBookVersion(bookId)
        store(Manuscript.read(version.snapshot), bookId)
    }

    private suspend fun saveBookVersion(bookId: String) {
        dao.addBookVersion(BookVersion(bookId = bookId, createdAt = System.currentTimeMillis(),
            snapshot = exportSnapshot(bookId).toString()))
        dao.pruneBookVersions(bookId)
    }

    suspend fun restore(bookId: String, chapterId: String, versionId: Long) = write {
        val chapter = requireNotNull(dao.chapter(bookId, chapterId)) { "章节已删除。" }
        val version = requireNotNull(dao.version(bookId, chapterId, versionId)) { "还原点已不存在。" }
        saveVersion(chapter)
        dao.putChapter(chapter.copy(title = version.title, text = version.text,
            complete = version.complete, payload = version.payload, wordCount = manuscriptWordCount(version.text)))
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
        return ChapterRow(bookId, id, volumeId, title, position, text, complete, payload.toString(), manuscriptWordCount(text))
    }
}

internal fun chapterJson(chapter: ChapterRow): JSONObject = JSONObject(chapter.payload)
    .put("id", chapter.id).put("title", chapter.title).put("text", chapter.text).put("complete", chapter.complete)
