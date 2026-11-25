package xyz.qingjian.writer

import org.json.JSONArray
import org.json.JSONObject

// Text-only projections of Eta's GeminiInteractionsRequest/Stream and
// VertexGenerateContentRequest/Stream (bbab1b76, GPL-3.0, see COPYING).
internal fun googleRequest(protocol: ModelProtocol, model: String, system: String, messages: List<ModelMessage>): JSONObject {
    if (protocol == ModelProtocol.Gemini) {
        val input = JSONArray()
        val previous = messages.dropLast(1)
        if (previous.isNotEmpty()) {
            val transcript = previous.joinToString("\n\n") { "${it.role}: ${it.content}" }
            input.put(geminiInput("以下是前文对话记录。\n$transcript\n前文结束。"))
        }
        input.put(geminiInput(messages.last().content))
        return JSONObject().put("model", model.removePrefix("models/")).put("input", input)
            .put("system_instruction", system).put("stream", true).put("store", false)
    }
    val contents = JSONArray(messages.map { message ->
        JSONObject().put("role", if (message.role == "assistant") "model" else "user")
            .put("parts", JSONArray().put(JSONObject().put("text", message.content)))
    })
    return JSONObject().put("contents", contents)
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
}

private fun geminiInput(text: String) = JSONObject().put("type", "user_input")
    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))

internal class GoogleStream(
    private val protocol: ModelProtocol,
    private val onText: (String) -> Unit,
    private val onReasoning: (String) -> Unit
) {
    private val steps = mutableMapOf<Int, String>()
    private var blocked = false
    var terminal = false
        private set
    var usage = ModelUsage(null, null, null)
        private set

    fun accept(frame: JSONObject) {
        check(frame.optJSONObject("error") == null) { "模型流式请求失败。" }
        if (protocol == ModelProtocol.Gemini) decodeGemini(frame) else decodeVertex(frame)
    }

    private fun decodeGemini(frame: JSONObject) {
        frame.optJSONObject("metadata")?.optJSONObject("total_usage")?.let { meter(it, gemini = true) }
        when (frame.string("event_type")) {
            "step.start" -> {
                val index = frame.getInt("index")
                val step = frame.getJSONObject("step")
                val type = step.string("type")
                steps[index] = type
                val field = if (type == "thought") "summary" else "content"
                step.records(field).forEach { chunk -> emit(type, chunk.string("text")) }
            }
            "step.delta" -> {
                val delta = frame.getJSONObject("delta")
                val type = steps[frame.getInt("index")] ?: return
                when (delta.string("type")) {
                    "text" -> emit(type, delta.string("text"))
                    "thought_summary" -> emit(type, delta.optJSONObject("content")?.string("text").orEmpty())
                }
            }
            "step.stop" -> frame.optJSONObject("usage")?.let { meter(it, gemini = true) }
            "interaction.completed" -> {
                val result = frame.getJSONObject("interaction")
                result.optJSONObject("usage")?.let { meter(it, gemini = true) }
                check(result.string("status") == "completed") { "Gemini 没有完成生成，已收到的内容仍可查看。" }
                terminal = true
            }
            "error" -> error("Gemini 中止了生成。")
        }
    }

    private fun decodeVertex(frame: JSONObject) {
        frame.optJSONObject("usageMetadata")?.let { meter(it, gemini = false) }
        blocked = blocked || frame.optJSONObject("promptFeedback")?.string("blockReason").orEmpty().isNotEmpty()
        val candidate = frame.optJSONArray("candidates")?.optJSONObject(0) ?: return
        candidate.optJSONObject("content")?.records("parts")?.forEach { part ->
            emit(if (part.optBoolean("thought")) "thought" else "model_output", part.string("text"))
        }
        val reason = candidate.string("finishReason")
        if (reason.isNotEmpty() && reason != "FINISH_REASON_UNSPECIFIED") {
            check(!blocked && reason == "STOP") { "Vertex AI 未完成本次生成（$reason）。" }
            terminal = true
        }
    }

    private fun emit(type: String, value: String) {
        if (value.isEmpty()) return
        when (type) {
            "model_output" -> onText(value)
            "thought" -> onReasoning(value)
        }
    }

    private fun meter(value: JSONObject, gemini: Boolean) {
        fun token(key: String) = (value.opt(key) as? Number)?.toInt()
        usage = ModelUsage(
            token(if (gemini) "total_input_tokens" else "promptTokenCount") ?: usage.input,
            token(if (gemini) "total_output_tokens" else "candidatesTokenCount") ?: usage.output,
            token(if (gemini) "total_cached_tokens" else "cachedContentTokenCount") ?: usage.cached
        )
    }
}
