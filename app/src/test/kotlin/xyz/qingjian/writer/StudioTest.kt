package xyz.qingjian.writer

import io.github.mangi.eta.agent.model.readProviderSse
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import java.io.ByteArrayInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioTest {
    @Test
    fun roleplayRetainsUnknownCardFieldsAndUsesCharacterWorldbook() {
        val card = readRoleplayCard("""
            {"spec":"chara_card_v3","spec_version":"3.0","outer":true,"data":{
              "name":"听潮","description":"观察{​{user}}","first_mes":"你好，{{user}}",
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
}
