package xyz.qingjian.writer

import java.util.concurrent.ConcurrentHashMap
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal class StudioServer(private val client: ModelClient) {
    private val sessions = ConcurrentHashMap<String, Cookie>()
    private val json = "application/json".toMediaType()

    suspend fun signIn(endpoint: String, username: String, password: String) {
        val base = base(endpoint)
        val body = JSONObject().put("username", username).put("password", password)
        val request = request(endpoint, "api/session", body, authenticated = false)
        client.execute(request) { response, _ ->
            val cookie = Cookie.parseAll(request.url, response.headers)
                .firstOrNull { it.name == "qingjian_novel_session" && it.secure && it.httpOnly }
            require(cookie != null) { "青简服务器没有返回有效的会话 Cookie。" }
            sessions[base.toString()] = cookie
        }
    }

    suspend fun signOut(endpoint: String) {
        val base = base(endpoint)
        if (!connected(endpoint)) return
        try {
            val request = request(endpoint, "api/session").newBuilder().delete().build()
            client.execute(request) { _, _ -> Unit }
        } finally {
            sessions.remove(base.toString())
        }
    }

    fun connected(endpoint: String) = runCatching {
        sessions[base(endpoint).toString()]?.expiresAt?.let { it > System.currentTimeMillis() } == true
    }.getOrDefault(false)

    suspend fun status(endpoint: String): JSONObject = call(endpoint, "api/ai/accounts?check=codex")

    suspend fun action(endpoint: String, resource: String, body: JSONObject) =
        call(endpoint, "api/ai/codex/$resource", body)

    private suspend fun call(endpoint: String, path: String, body: JSONObject? = null) =
        client.execute(request(endpoint, path, body)) { response, _ ->
            val bytes = requireNotNull(response.body).byteStream().use { it.readLimited(2_000_000, "服务器响应过大。") }
            JSONObject(bytes.toString(Charsets.UTF_8))
        }

    suspend fun generate(
        endpoint: String, task: String, system: String, messages: List<ModelMessage>,
        text: (String) -> Unit, reasoning: (String) -> Unit, usage: (ModelUsage) -> Unit
    ): String {
        val intent = when (task) {
            "文本扮演" -> "roleplay"
            "逐段改稿" -> "paragraph"
            "批量改稿" -> "batch"
            "整章改写" -> "chapter-rewrite"
            "章节规划" -> "summary"
            "生成初稿" -> "chapter"
            "全书大纲" -> "outline"
            "创作设定" -> "settings"
            "章节审阅" -> "chapter-review"
            "定稿交接" -> "chapter-close"
            else -> error("此任务尚未接入青简服务器。")
        }
        val single = messages.singleOrNull()?.content
        val original = single?.let { runCatching { JSONObject(it) }.getOrNull() }
        val material = original ?: JSONObject().put("对话", JSONArray(messages.map {
            JSONObject().put("角色", it.role).put("正文", it.content)
        }))
        material.put("原生写作要求", system)
        if (intent == "paragraph") material.put("目标段落", material.string("目标正文"))
        if (intent == "chapter-close" && !material.has("审阅原文")) {
            material.put("审阅原文", JSONArray(paragraphSpans(material.string("目标正文")).map { it.text }))
        }
        val data = JSONObject().put("provider", "codex").put("intent", intent).put("material", material)
        val request = request(endpoint, "api/ai/rewrite", data).newBuilder()
            .header("Accept", "application/x-ndjson").build()
        return client.execute(request) { response, active ->
            val streamed = StringBuilder()
            var result: String? = null
            requireNotNull(response.body).charStream().buffered().useLines { lines ->
                for (line in lines) {
                    active()
                    if (line.isBlank()) continue
                    val frame = JSONObject(line)
                    when (frame.string("type")) {
                        "delta" -> {
                            val fragment = frame.string("text")
                            streamed.append(fragment)
                            text(fragment)
                        }
                        "summary" -> reasoning(frame.string("text"))
                        "error" -> error(frame.string("message", "青简服务器中止了请求。"))
                        "result" -> {
                            val value = frame.getJSONObject("value")
                            val meter = value.optJSONObject("usage")
                            usage(ModelUsage(
                                (meter?.opt("input") as? Number)?.toInt(),
                                (meter?.opt("output") as? Number)?.toInt(),
                                (meter?.opt("cached") as? Number)?.toInt()
                            ))
                            val structured = value.optJSONObject("data")
                            result = when {
                                intent == "batch" && structured != null -> JSONArray(structured.records("items").map {
                                    JSONObject().put("id", it.string("paragraphId")).put("after", it.string("text"))
                                }).toString()
                                structured != null -> structured.toString()
                                else -> value.string("text").ifBlank { streamed.toString() }
                            }
                            break
                        }
                    }
                }
            }
            result?.takeIf(String::isNotBlank) ?: error("服务器流提前结束，尚未收到最终结果。")
        }
    }

    private fun request(endpoint: String, path: String, body: JSONObject? = null, authenticated: Boolean = true): Request {
        val base = base(endpoint)
        val url = requireNotNull(base.resolve(path))
        return Request.Builder().url(url).header("Accept", "application/json").apply {
            header("Origin", "${base.scheme}://${base.encodedAuthority()}")
            if (authenticated) {
                val cookie = sessions[base.toString()]
                require(cookie != null && cookie.matches(url) && cookie.expiresAt > System.currentTimeMillis()) {
                    "请先登录青简服务器。"
                }
                header("Cookie", "${cookie.name}=${cookie.value}")
            }
            if (body != null) post(body.toString().toRequestBody(json))
        }.build()
    }

    private fun base(endpoint: String): HttpUrl = (endpoint.trim().trimEnd('/') + "/").toHttpUrl().also { url ->
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "请输入青简部署的 HTTPS 地址。"
        }
    }
}

private fun HttpUrl.encodedAuthority() = if (port == 443) {
    if (host.contains(':')) "[$host]" else host
} else if (host.contains(':')) "[$host]:$port" else "$host:$port"
