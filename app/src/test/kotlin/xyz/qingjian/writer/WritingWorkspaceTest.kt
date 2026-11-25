package xyz.qingjian.writer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingWorkspaceTest {
    @Test
    fun blocksRetainSourceOffsetsAcrossWindowsLineEndingsAndSceneDividers() {
        val source = "# 标题\r\n\r\n第一段。\r\n✦\r\n第二段。\r\n"
        assertEquals(listOf("# 标题", "第一段。", "✦", "第二段。"),
            editorBlocks(source).map { source.substring(it.start, it.end) })
    }

    @Test
    fun replyAlternativesPreserveTheOriginalAndTheStableMessageId() {
        val message = roleplayMessage("assistant", "第一版。")
        val id = message.string("id")
        reviseRoleplayReply(message, "第二版。")
        assertEquals("第二版。", message.string("text"))
        selectRoleplayReply(message, 0)
        assertEquals("第一版。", message.string("text"))
        assertEquals(id, message.string("id"))
        assertEquals(2, message.getJSONArray("replyAlternatives").length())
    }

    @Test
    fun vertexCompletionCanCarryUsageInAFollowingFrame() {
        val text = mutableListOf<String>()
        val reasoning = mutableListOf<String>()
        val stream = GoogleStream(ModelProtocol.Vertex, { text.add(it) }, { reasoning.add(it) })
        stream.accept(JSONObject("""{"candidates":[{"content":{"parts":[{"text":"推演","thought":true},{"text":"正文"}]}}]}"""))
        assertFalse(stream.terminal)
        stream.accept(JSONObject("""{"candidates":[{"finishReason":"STOP"}]}"""))
        stream.accept(JSONObject("""{"usageMetadata":{"promptTokenCount":20,"candidatesTokenCount":7,"cachedContentTokenCount":5}}"""))
        assertTrue(stream.terminal)
        assertEquals(listOf("正文"), text)
        assertEquals(listOf("推演"), reasoning)
        assertEquals(ModelUsage(20, 7, 5), stream.usage)
    }
}
