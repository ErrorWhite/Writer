package xyz.qingjian.writer

import org.json.JSONObject

// Text-generation subset of Eta's ProviderReasoning (bbab1b76).
internal enum class ReasoningEffort(val wire: String, val label: String) {
    Default("", "由模型决定"), Off("none", "关闭"), Low("low", "低"), Medium("medium", "中"), High("high", "高")
}

internal fun supportedEfforts(account: ProviderAccount): List<ReasoningEffort> {
    val ordinary = listOf(ReasoningEffort.Default)
    val model = account.model.lowercase().removePrefix("models/")
    val host = runCatching { account.url("").host }.getOrNull().orEmpty()
    return when (account.protocol) {
        ModelProtocol.Responses ->
            if (model.startsWith("gpt-5") || model.matches(Regex("o[134](?:[-.].*)?"))) ReasoningEffort.entries else ordinary
        ModelProtocol.Chat -> when {
            host == "api.deepseek.com" -> ReasoningEffort.entries
            host == "openrouter.ai" -> ReasoningEffort.entries
            host == "api.openai.com" && model.startsWith("gpt-5") -> ReasoningEffort.entries
            else -> ordinary
        }
        ModelProtocol.Anthropic -> if ("opus-4-6" in model || "sonnet-4-6" in model) ReasoningEffort.entries else ordinary
        ModelProtocol.Gemini -> if (model.startsWith("gemini-3") || model.startsWith("gemini-4"))
            ReasoningEffort.entries.filter { it != ReasoningEffort.Off } else ordinary
        ModelProtocol.Vertex -> if (model.startsWith("gemini-3") || model.startsWith("gemini-4"))
            ReasoningEffort.entries.filter { it != ReasoningEffort.Off } else ordinary
    }
}

internal fun applyReasoning(body: JSONObject, account: ProviderAccount) {
    val effort = account.reasoning
    require(effort in supportedEfforts(account)) { "这个模型不支持所选思考档位。" }
    if (effort == ReasoningEffort.Default) return
    val off = effort == ReasoningEffort.Off
    when (account.protocol) {
        ModelProtocol.Responses -> body.put("reasoning", JSONObject().put("effort", effort.wire).apply {
            if (!off) put("summary", "auto")
        })
        ModelProtocol.Chat -> when (account.url("").host) {
            "api.deepseek.com" -> {
                body.put("thinking", JSONObject().put("type", if (off) "disabled" else "enabled"))
                if (!off) body.put("reasoning_effort", if (effort == ReasoningEffort.Low) "low" else "high")
            }
            "openrouter.ai" -> body.put("reasoning", JSONObject().put("effort", effort.wire))
            else -> body.put("reasoning_effort", effort.wire)
        }
        ModelProtocol.Anthropic -> {
            body.put("thinking", JSONObject().put("type", if (off) "disabled" else "adaptive").apply {
                if (!off) put("display", "summarized")
            })
            if (!off) body.put("output_config", JSONObject().put("effort", effort.wire))
        }
        ModelProtocol.Gemini -> body.put("generation_config", JSONObject()
            .put("thinking_level", effort.wire).put("thinking_summaries", "auto"))
        ModelProtocol.Vertex -> body.put("generationConfig", JSONObject().put("thinkingConfig", JSONObject()
            .put("thinkingLevel", effort.wire.uppercase()).put("includeThoughts", true)))
    }
}
