package xyz.qingjian.writer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManuscriptTest {
    @Test
    fun preservesCrossPlatformExtensionsAndIdsWhenEditing() {
        val book = Manuscript.read("""
            {
              "id":"desktop-book", "title":"青简样稿", "activeId":"c1",
              "creative":{"characters":[{"id":"character-1"}]},
              "research":{"annotations":[{"id":"a1","anchor":{"chapterId":"c1","paragraphId":"p1"}}]},
              "roleplay":{"cards":[{"id":"r1","name":"听潮"}],"sessions":[]},
              "volumes":[{"id":"v1","title":"第一卷","chapters":[{
                "id":"c1","title":"开篇","text":"旧文\r\n\r\n＊　＊　＊\r\n尾段",
                "handoff":{"summary":"交接摘要"},
                "paragraphs":[{"id":"p1","text":"旧文","original":"最初正文","instruction":"保持意象","drafts":[{"state":"pending"}]},
                              {"id":"p2","text":"尾段"}]
              }]}]
            }
        """.trimIndent())
        val chapter = book.records("volumes").single().records("chapters").single()
        editChapter(chapter, "新文\r\n\r\n＊　＊　＊\r\n尾段")
        val roundTrip = Manuscript.read(book.toString())
        val saved = roundTrip.records("volumes").single().records("chapters").single()
        assertEquals(listOf("p1", "p2"), saved.records("paragraphs").map { it.string("id") })
        val first = saved.records("paragraphs").first()
        assertEquals("最初正文", first.string("original"))
        assertEquals("pending", first.records("drafts").single().string("state"))
        assertEquals("交接摘要", saved.getJSONObject("handoff").string("summary"))
        assertEquals("p1", roundTrip.getJSONObject("research").records("annotations").single()
            .getJSONObject("anchor").string("paragraphId"))
        assertEquals("听潮", roundTrip.getJSONObject("roleplay").records("cards").single().string("name"))
        assertEquals("character-1", roundTrip.getJSONObject("creative").records("characters").single().string("id"))
        assertEquals("新文\r\n\r\n＊　＊　＊\r\n尾段", saved.string("text"))
    }

    @Test
    fun acceptsLegacyChaptersAndRemembersOriginalIdentity() {
        val legacy = Manuscript.read("\uFEFF" + """
            {"id":"legacy", "title":"旧书", "volume":"原卷名", "chapters":[{"id":"a","title":"原章名","text":"正文"}]}
        """.trimIndent())
        assertEquals("legacy", legacy.string("id"))
        assertEquals("a", legacy.string("activeId"))
        assertEquals("原卷名", legacy.records("volumes").single().string("title"))
        assertFalse(legacy.has("chapters"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateChapterIds() {
        Manuscript.read("""
            {"title":"重复", "chapters":[{"id":"same","title":"上","text":""},{"id":"same","title":"下","text":""}]}
        """.trimIndent())
    }

    @Test
    fun markdownConvertsSceneBreaksOutsideCodeAndKeepsFencedSource() {
        val fence = "```"
        val chapter = JSONObject().put("text", "")
        val text = "前段\r\n＊　＊　＊\r\n${fence}text\r\n＊　＊　＊\r\n$fence\r\n后段"
        editChapter(chapter, text)
        assertEquals(listOf("前段", "${fence}text", "＊　＊　＊", fence, "后段"),
            chapter.records("paragraphs").map { it.string("text") })
        assertTrue(manuscriptMarkdown(text).contains("\n---\n"))
        assertTrue(manuscriptMarkdown(text).contains("${fence}text\r\n＊　＊　＊\r\n$fence"))
    }
}
