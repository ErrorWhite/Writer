package xyz.qingjian.writer

import io.github.mangi.eta.agent.model.readProviderSse
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class StudioTest {
    @Test
    fun synchronizedDeletionMetadataSurvivesRestartAndReadsOlderLinks() {
        val original = SyncedBook.read(JSONObject("""
            {"endpoint":"https://sync.example.test/","revision":"r1","digest":"confirmed"}
        """.trimIndent()))
        assertEquals(null, original.deleteDigest)
        val pending = original.copy(deleteDigest = "local-at-deletion")
        assertEquals(pending, SyncedBook.read(JSONObject(pending.json().toString())))
        assertEquals(original, SyncedBook.read(JSONObject(pending.copy(deleteDigest = null).json().toString())))
    }

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun roleplayRetainsUnknownCardFieldsAndUsesCharacterWorldbook() {
        val card = readRoleplayCard("""
            {"spec":"chara_card_v3","spec_version":"3.0","outer":true,"data":{
              "name":"听潮","description":"观察玩家","first_mes":"你好，{{user}}",
              "alternate_greetings":["备选开场"],"extensions":{"extra":17},
              "character_book":{"entries":[{"keys":["灯塔"],"content":"灯塔熄灭","enabled":true,"position":"before_char"}]}
            }}
        """.trimIndent().toByteArray())
        val original = CharacterCardCodec.decodeBytes(exportCard(card, false))
        assertEquals("备选开场", original.alternateGreetings.single())
        assertEquals(17, JSONObject(original.extensions.toString()).getInt("extra"))
        assertTrue(JSONObject(original.raw.toString()).getBoolean("outer"))
        val session = newRoleplaySession(card, card.string("greeting"))
        assertEquals("你好，玩家", session.records("messages").single().string("text"))
        val (system, messages) = roleplayPrompt(Manuscript.create("样稿"), card, session,
            session.records("messages") + roleplayMessage("user", "我走进灯塔。"), false)
        assertTrue(system.contains("灯塔熄灭"))
        assertEquals("user", messages.last().role)
    }

    @Test
    fun aJsonCardWithoutAvatarCanRoundTripThroughTavernPng() {
        val card = readRoleplayCard("""
            {"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"听潮",
              "first_mes":"启航","alternate_greetings":["回到港口"],"extensions":{"chapter":7}}}
        """.trimIndent().toByteArray())
        assertFalse(card.has("png"))
        val exported = exportCard(card, true)
        val imported = readRoleplayCard(exported)
        assertEquals("听潮", imported.string("name"))
        assertEquals("启航", imported.string("greeting"))
        assertEquals(listOf("回到港口"), etaCard(imported).alternateGreetings)
        assertEquals(7, JSONObject(etaCard(imported).extensions.toString()).getInt("chapter"))
        assertEquals(roleplayAvatar(exported), imported.string("png"))
        assertTrue(runCatching { roleplayAvatar("not a png".toByteArray()) }.isFailure)
    }

    @Test
    fun sseStopsAtProtocolTerminalFrameAndJoinsMultilineData() {
        val text = "event: delta\r\ndata: a\r\ndata: b\r\n\r\ndata: [DONE]\n\ndata: ignored\n"
        val frames = mutableListOf<Pair<String, String>>()
        readProviderSse(ByteArrayInputStream(text.toByteArray()), {}) { event, data ->
            frames += event to data
            data != "[DONE]"
        }
        assertEquals(listOf("delta" to "a\nb", "" to "[DONE]"), frames)
    }

    @Test
    fun revisionsMustPreserveSceneBoundariesAndFormatting() {
        assertFalse(safeRevision("旧稿", "**旧稿**"))
        assertFalse(safeRevision("**旧稿**\n\n＊　＊　＊\n续文", "新稿\n续文"))
        assertTrue(safeRevision("**旧稿**\n\n＊　＊　＊\n续文", "**新稿**\n\n＊　＊　＊\n续文"))
    }

    @Test
    fun archiveUndoProtectsManuallyEditedRecords() {
        val chapter = Manuscript.newChapter("天明").put("text", "林深回到灯塔。")
        val book = JSONObject()
        val handoff = JSONObject("""
            {"recap":"林深归来","bridge":"继续调查","events":[{"title":"归来","quote":"林深回到灯塔。","note":"抵达"}]}
        """.trimIndent())
        val delta = archiveHandoff(book, chapter, checkedHandoff(handoff, chapter))
        assertEquals(1, delta.length())
        val event = book.obj("research").records("events").single()
        assertEquals(chapter.string("id"), event.getJSONObject("anchor").string("chapterId"))
        event.put("description", "作者后来编辑")
        undoHandoffArchive(book, delta)
        assertEquals("作者后来编辑", book.obj("research").records("events").single().string("description"))
    }

    @Test
    fun gitHistoryStoresBranchesAndKeepsBookSnapshotsRestorable() {
        runBlocking {
            val git = ManuscriptGit(temp.root)
            val book = Manuscript.create("雾港").put("outline", "第一版")
            val id = book.string("id")
            git.commit(id, "main", null, "初稿", book)
            val initial = git.timeline(id, "main").commits.single()
            git.branch(id, "rewrite", initial.oid)
            git.commit(id, "rewrite", initial.oid, "改大纲", book.put("outline", "第二版"))
            val second = git.timeline(id, "rewrite").commits.first()
            assertEquals(initial.oid, git.timeline(id, "main").commits.single().oid)
            assertEquals("第一版", git.snapshot(id, initial.oid).string("outline"))
            assertEquals("第二版", git.snapshot(id, second.oid).string("outline"))
            val diff = git.diff(id, second.oid).single { it.path == "outline.md" }
            assertEquals("第一版", diff.before)
            assertEquals("第二版", diff.after)
        }
    }

    @Test
    fun comparisonAlignsCommonLinesAcrossInsertionsAndReplacements() {
        val rows = alignDiff("开场\n旧稿\n下章", "开场\n新稿\n新增\n下章")
        assertEquals(listOf(DiffLine("开场", "开场"), DiffLine("旧稿", "新稿"),
            DiffLine(null, "新增"), DiffLine("下章", "下章")), rows)
    }

    @Test
    fun bookSnapshotComparisonUsesChapterIdentityAndShowsReorderingSeparately() {
        val saved = Manuscript.create("雾港").put("outline", "旧大纲")
        val chapters = saved.records("volumes").single().getJSONArray("chapters")
        chapters.getJSONObject(0).put("title", "开场").put("text", "旧稿")
        chapters.put(Manuscript.newChapter("后章").put("text", "保留的正文"))
        val current = JSONObject(saved.toString()).put("outline", "新大纲")
        val currentChapters = current.records("volumes").single().getJSONArray("chapters")
        val first = currentChapters.remove(0) as JSONObject
        currentChapters.put(first.put("text", "新稿"))

        val differences = compareBookSnapshots(current, saved)
        assertEquals(listOf("卷章目录", "全书大纲", "开场"), differences.map { it.label })
        assertEquals(BookSnapshotDifference("开场", "新稿", "旧稿"), differences.last())
        assertTrue(compareBookSnapshots(current, JSONObject(current.toString())).isEmpty())
    }

    @Test
    fun skillScriptMustDeclareARealFileAndOnlyGetsKnownTools() {
        val source = JSONObject("""
            {"name":"计数","metadata":{"qingjian":{"entry":"tools/run.js","tools":["wordCount","shell"]}},
             "files":[{"path":"tools/run.js","text":"function run(input, tools) { return tools.wordCount(input.text); }"}]}
        """.trimIndent())
        val script = requireNotNull(declaredSkillScript(source))
        assertEquals(listOf("wordCount"), script.strings("tools"))
        assertEquals(64, scriptFingerprint(script).length)
        source.getJSONObject("metadata").getJSONObject("qingjian").put("entry", "../tools/run.js")
        assertEquals(null, declaredSkillScript(source))
    }
}
