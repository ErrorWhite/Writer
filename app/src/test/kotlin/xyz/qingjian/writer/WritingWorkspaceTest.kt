package xyz.qingjian.writer

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingWorkspaceTest {
    @Test
    fun literalSearchCountsNonOverlappingMatchesLikeReplacement() {
        val text = "雾雾雾 Aa aA"
        assertEquals(1, countLiteralMatches(text, "雾雾", matchCase = true))
        assertEquals(2, countLiteralMatches(text, "aa", matchCase = false))
        assertEquals(0, countLiteralMatches(text, "aa", matchCase = true))
        assertEquals(0, countLiteralMatches(text, "", matchCase = false))
    }

    @Test
    fun roleplayBranchesByMessageIdentityAndRejectsRepliesFromAnOlderTranscript() {
        val first = roleplayMessage("user", "打开门。")
        val second = roleplayMessage("assistant", "门后是雾。")
        val transcript = JSONArray(listOf(first, second))
        val session = JSONObject().put("id", "main").put("title", "雾港").put("messages", transcript)
        val revision = roleplayTranscriptRevision(transcript)
        reviseRoleplayReply(second, "门后是渡口。")

        assertTrue(runCatching { appendRoleplayResponse(transcript, revision, null, "失效回复") }.isFailure)
        assertEquals(2, transcript.length())
        val branch = branchRoleplaySession(session, first.string("id"), "雾港")
        assertEquals(listOf(first.string("id")), branch.records("messages").map { it.string("id") })
        branch.records("messages").single().put("text", "只改分支")
        assertEquals("打开门。", first.string("text"))
        appendRoleplayResponse(transcript, roleplayTranscriptRevision(transcript), second.string("id"), "门后是灯塔。")
        assertEquals("门后是灯塔。", second.string("text"))
    }

    @Test
    fun researchNavigationFindsTheSecondOccurrenceByStableParagraphId() {
        val source = "# 雾港\r\n\r\n  同一句。\r\n\r\n  同一句。"
        val chapter = JSONObject().put("text", "")
        editChapter(chapter, source)
        val last = chapter.records("paragraphs").last()
        val position = resolveResearchPosition(chapter, last.string("id"))
        assertEquals(WritingPosition(source.lastIndexOf("  同一句。"), source.length, 2, 0), position)
        assertEquals(null, resolveResearchPosition(chapter, "unknown-id"))
        last.put("text", "脱离正文的旧锚点")
        assertEquals(null, resolveResearchPosition(chapter, last.string("id")))
    }

    @Test
    fun batchComparisonAndAdoptionUseAuthorEditsAndGuardTheOriginalOffsets() {
        val baseline = "初见。\n\n原尾。"
        val first = JSONObject().put("start", 0).put("before", "初见。").put("after", "模型开场。")
            .put("editedAfter", "作者开场。")
        val second = JSONObject().put("start", baseline.indexOf("原尾。")).put("before", "原尾。").put("after", "新尾。")
        fun draft(item: JSONObject) = item.string("editedAfter", item.string("after"))

        assertEquals("作者开场。\n\n新尾。", applyBatchCandidates(baseline, listOf(first, second), ::draft))
        assertEquals("作者开场。\n\n原尾。", applyBatchCandidates(baseline, listOf(first), ::draft))
        assertEquals("模型开场。", first.string("after"))
        assertTrue(runCatching { applyBatchCandidates("已改。\n\n原尾。", listOf(first), ::draft) }.isFailure)
    }

    @Test
    fun interruptedOutputSurvivesAStaleCandidateAndCanBePromotedOrDiscardedById() {
        val baseline = "　　原来的句子。\n\n章末。"
        val result = JSONObject().put("id", "first").put("intent", WritingIntent.Paragraph.name)
            .put("baseline", baseline).put("before", "　　原来的句子。").put("start", 0)
            .put("after", "未写完的句子")
        val second = JSONObject(result.toString()).put("id", "second")
        val chapter = JSONObject().put("text", baseline).put("nativeInterrupted", JSONArray(listOf(result, second)))
        chapter.put("nativeReview", JSONObject().put("id", "existing"))
        assertTrue(runCatching { resolveInterruptedWriting(chapter, "first", keep = true) }.isFailure)
        chapter.remove("nativeReview")
        chapter.put("text", "正文已有新版本。")
        assertTrue(runCatching { resolveInterruptedWriting(chapter, "first", keep = true) }.isFailure)
        assertEquals(2, chapter.records("nativeInterrupted").size)

        chapter.put("text", baseline)
        resolveInterruptedWriting(chapter, "first", keep = true)
        assertEquals(baseline, chapter.string("text"))
        assertEquals("　　未写完的句子", pendingWritingReview(chapter)?.string("after"))
        assertEquals(listOf("second"), chapter.records("nativeInterrupted").map { it.string("id") })
        resolveInterruptedWriting(chapter, "second", keep = false)
        assertFalse(chapter.has("nativeInterrupted"))
        assertEquals("first", pendingWritingReview(chapter)?.string("id"))
    }

    @Test
    fun pcBatchReviewResolvesRepeatedTextByIdAndHonorsRemoteAdoption() {
        val baseline = "重复正文。\r\n\r\n重复正文。"
        val paragraphs = JSONArray(listOf(
            JSONObject().put("id", "first").put("text", "重复正文。"),
            JSONObject().put("id", "last").put("text", "重复正文。")
        ))
        val candidate = JSONObject().put("id", "last").put("before", "重复正文。")
            .put("after", "模型改写。").put("editedAfter", "作者改写。")
        val chapter = JSONObject().put("text", baseline).put("paragraphs", paragraphs)
            .put("batchReview", JSONArray().put(candidate))
        val review = requireNotNull(pendingWritingReview(chapter))
        val item = review.records("items").single()
        assertEquals(baseline.lastIndexOf("重复正文。"), item.getInt("start"))
        assertEquals("模型改写。", item.string("after"))
        assertEquals("重复正文。\r\n\r\n作者改写。", applyBatchCandidates(baseline, listOf(item)) {
            it.string("editedAfter", it.string("after"))
        })

        chapter.put("nativeReview", review).put("batchReview", JSONArray())
        assertEquals(null, pendingWritingReview(chapter))
    }

    @Test
    fun worldTrialDistinguishesBudgetExclusionsAndUsesTheSameMatchesAsGeneration() {
        fun entry(id: String, key: String, content: String) = JSONObject()
            .put("id", id).put("title", id).put("keys", JSONArray(listOf(key))).put("content", content)
        val entries = JSONArray()
            .put(entry("too-long", "A", "预算外关键词 hidden".repeat(40)).put("priority", 200))
            .put(entry("first", "A", "bridge").put("caseSensitive", true))
            .put(entry("second", "bridge", "结束").put("excludeRecursion", false))
            .put(entry("delayed", "A", "下一轮").put("recursionDelay", 2))
            .put(entry("hidden", "hidden", "不会出现"))
        val world = JSONObject().put("entries", entries).put("budget", 180).put("recursion", 2)

        val result = resolveBookWorld(world, "Ａ", 12_000)
        assertEquals(listOf("too-long", "first", "second", "delayed"), result.hits.map { it.entry.string("id") })
        assertEquals(listOf(false, true, true, true), result.hits.map { it.included })
        assertEquals(listOf(0, 1, 2), result.chosen.map { it.round })
        assertEquals(result.chosen.map { it.entry }, worldBookMatches(world, "Ａ", 12_000))
        assertTrue(result.used in 1..result.budget)
        assertFalse(resolveBookWorld(world, "a", 12_000).chosen.any { it.entry.string("id") == "first" })
    }

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
    fun profileSuggestionUsesTheSelectedReferenceSourceWithoutIncludingTheOtherSample() {
        val book = Manuscript.create("文风样本")
        val volume = book.records("volumes").single()
        val chapter = volume.records("chapters").single()
        val reference = Manuscript.newChapter("旧章").put("text", "参考章新文。")
        volume.getJSONArray("chapters").put(reference)
        val profile = book.obj("aiSetup").put("referenceChapterId", reference.string("id"))
            .put("referenceSnapshot", "参考章旧快照。").put("referenceText", "另一个粘贴稿。")

        fun context() = JSONObject(writingContext(book, chapter, WritingIntent.Profile, "慢节奏开篇", "").second.single().content)

        profile.put("referenceType", "chapter")
        val fromChapter = context()
        assertEquals("参考章新文。", fromChapter.string("文风样稿"))
        assertFalse(fromChapter.toString().contains("参考章旧快照。"))
        assertFalse(fromChapter.toString().contains("另一个粘贴稿。"))

        profile.put("referenceType", "paste")
        val fromPaste = context()
        assertEquals("另一个粘贴稿。", fromPaste.string("文风样稿"))
        assertFalse(fromPaste.toString().contains("参考章新文。"))
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
