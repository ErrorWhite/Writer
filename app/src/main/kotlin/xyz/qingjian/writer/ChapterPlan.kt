package xyz.qingjian.writer

import org.json.JSONArray
import org.json.JSONObject

internal fun chapterPlan(direction: String = "", previous: String = "") = JSONObject()
    .put("stage", "summary").put("summary", "").put("draft", "")
    .put("direction", direction.trim()).put("handoffFrom", previous)

internal fun pendingWritingReview(chapter: JSONObject): JSONObject? {
    val native = chapter.optJSONObject("nativeReview")
    if (native != null && native.string("intent") != WritingIntent.Batch.name) return native
    val batch = chapter.optJSONArray("batchReview")
    if (batch != null && batch.length() > 0) {
        if (native != null && native.optJSONArray("items")?.toString() == batch.toString()) return native
        val text = chapter.string("text")
        val spans = paragraphSpans(text)
        val indices = chapter.records("paragraphs").mapIndexed { index, paragraph -> paragraph.string("id") to index }.toMap()
        val items = batch.objects().map { item ->
            val span = indices[item.string("id")]?.let(spans::getOrNull)?.takeIf { it.text == item.string("before") }
            JSONObject(item.toString()).put("start", span?.start ?: -1)
        }
        return JSONObject().put("id", "shared-batch").put("intent", WritingIntent.Batch.name)
            .put("baseline", text).put("items", JSONArray(items))
            .put("instruction", native?.string("instruction").orEmpty())
    }
    if (batch == null && native != null) return native
    val plan = chapter.optJSONObject("plan") ?: return null
    val draft = plan.string("draft")
    if (plan.string("stage") != "draft" || draft.isBlank()) return null
    val base = plan.string("draftBase", chapter.string("text"))
    return JSONObject().put("id", "chapter-plan").put("intent", WritingIntent.Chapter.name)
        .put("baseline", base).put("before", base).put("after", draft)
}

internal fun setPlanningSummary(chapter: JSONObject, summary: String) {
    require(summary.isNotBlank()) { "请填写本章摘要。" }
    chapter.obj("plan").put("stage", "summary").put("summary", summary.trim())
}

internal fun rememberChapterDraft(chapter: JSONObject, baseline: String, draft: String) {
    require(draft.isNotBlank()) { "模型未返回初稿。" }
    chapter.obj("plan").put("stage", "draft").put("draftBase", baseline).put("draft", draft)
}

internal fun decideChapterDraft(chapter: JSONObject, draft: String, accepted: Boolean) {
    val plan = chapter.obj("plan")
    if (accepted) {
        editChapter(chapter, draft)
        plan.put("stage", "approved").put("draft", draft)
    } else {
        plan.put("stage", "summary")
    }
    plan.remove("draftBase")
}
