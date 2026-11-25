package xyz.qingjian.writer

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingWorkspaceTest {
    @Test
    fun chapterPlanningUsesTheExplicitHandoffAndSelectedDirection() {
        val first = Manuscript.newChapter("定稿章").put("handoff", JSONObject().put("bridge", "第一章的伏笔"))
        val other = Manuscript.newChapter("中间章").put("handoff", JSONObject().put("bridge", "别的伏笔"))
        val next = Manuscript.newChapter("新章").put("plan", JSONObject()
            .put("handoffFrom", first.string("id")).put("direction", "沿河追查").put("summary", "先探查渡船"))
        val book = Manuscript.create("测试").put("volumes", JSONArray().put(JSONObject()
            .put("chapters", JSONArray().put(first).put(other).put(next))))
        val context = JSONObject(writingContext(book, next, WritingIntent.Chapter, "", "").second.single().content)
        assertEquals("沿河追查", context.getString("已选走向"))
        assertEquals("第一章的伏笔", context.getJSONObject("上一章交接").getString("bridge"))
        assertEquals("先探查渡船", context.getString("章节摘要"))
    }

    @Test
    fun reasoningSettingsUseTheSelectedProvidersOwnWireFormat() {
        val deepseek = ProviderAccount("id", "DeepSeek", "https://api.deepseek.com/v1", ModelProtocol.Chat,
            "deepseek-reasoner", "synthetic", reasoning = ReasoningEffort.Medium)
        val deepseekBody = JSONObject()
        applyReasoning(deepseekBody, deepseek)
        assertEquals("high", deepseekBody.getString("reasoning_effort"))
        assertEquals("enabled", deepseekBody.getJSONObject("thinking").getString("type"))
        val geminiBody = JSONObject()
        applyReasoning(geminiBody, deepseek.copy(
            protocol = ModelProtocol.Gemini, endpoint = "https://generativelanguage.googleapis.com/v1beta",
            model = "gemini-3"
        ))
        assertEquals("medium", geminiBody.getJSONObject("generation_config").getString("thinking_level"))
        assertFalse(geminiBody.has("reasoning_effort"))
    }

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
