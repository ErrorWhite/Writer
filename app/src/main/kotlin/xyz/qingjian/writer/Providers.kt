package xyz.qingjian.writer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.mangi.eta.agent.model.readProviderSse
import io.github.mangi.eta.data.provider.CommunityCatalogParser
import io.github.mangi.eta.data.provider.CommunityCatalogSource
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

internal enum class ModelProtocol(val label: String, val route: String) {
    Responses("OpenAI Responses", "responses"),
    Chat("OpenAI Chat 兼容", "chat/completions"),
    Anthropic("Anthropic Messages", "messages");
}

internal data class ProviderPreset(val name: String, val url: String, val protocol: ModelProtocol)
internal val providerPresets = listOf(
    ProviderPreset("OpenAI", "https://api.openai.com/v1", ModelProtocol.Responses),
    ProviderPreset("DeepSeek", "https://api.deepseek.com/v1", ModelProtocol.Chat),
    ProviderPreset("Anthropic", "https://api.anthropic.com/v1", ModelProtocol.Anthropic),
    ProviderPreset("阿里百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1", ModelProtocol.Chat),
    ProviderPreset("OpenRouter", "https://openrouter.ai/api/v1", ModelProtocol.Chat),
    ProviderPreset("Kimi", "https://api.moonshot.cn/v1", ModelProtocol.Chat),
    ProviderPreset("MiMo", "https://api.xiaomimimo.com/v1", ModelProtocol.Chat),
    ProviderPreset("MiniMax", "https://api.minimaxi.com/v1", ModelProtocol.Chat),
    ProviderPreset("阶跃星辰", "https://api.stepfun.com/v1", ModelProtocol.Chat),
    ProviderPreset("xAI", "https://api.x.ai/v1", ModelProtocol.Chat),
    ProviderPreset("硅基流动", "https://api.siliconflow.cn/v1", ModelProtocol.Chat)
)

internal data class ProviderAccount(
    val id: String, val name: String, val endpoint: String, val protocol: ModelProtocol,
    val model: String, val key: String, val enabled: Boolean = true
) {
    fun url(path: String) = endpoint.trim().trimEnd('/').toHttpUrl().let { base ->
        require(base.isHttps && base.username.isEmpty() && base.password.isEmpty() &&
            base.query == null && base.fragment == null) { "请填写无查询参数的 HTTPS 服务地址。" }
        val builder = base.newBuilder()
        path.split('/').forEach { builder.addPathSegment(it) }
        builder.build()
    }
}

internal class ProviderVault(context: Context) {
    private val storage = context.getSharedPreferences("providers", Context.MODE_PRIVATE)
    private val alias = "qingjian-provider-v1"
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    suspend fun load(): List<ProviderAccount> = withContext(Dispatchers.IO) {
        JSONArray(storage.getString("accounts", "[]")).objects().map { row ->
            val id = row.getString("id")
            ProviderAccount(id, row.getString("name"), row.getString("endpoint"),
                ModelProtocol.valueOf(row.getString("protocol")), row.getString("model"),
                open(row.getString("key"), id), row.optBoolean("enabled", true))
        }
    }

    suspend fun save(accounts: List<ProviderAccount>) = withContext(Dispatchers.IO) {
        val rows = accounts.map { account ->
            JSONObject().put("id", account.id).put("name", account.name)
                .put("endpoint", account.endpoint).put("protocol", account.protocol.name)
                .put("model", account.model).put("enabled", account.enabled)
                .put("key", seal(account.key, account.id))
        }
        check(storage.edit().putString("accounts", JSONArray(rows).toString()).commit()) { "账号保存失败。" }
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
    }

    private fun seal(value: String, id: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(id.toByteArray())
        return encoder.encodeToString(cipher.iv) + "." + encoder.encodeToString(cipher.doFinal(value.toByteArray()))
    }

    private fun open(value: String, id: String): String {
        val (iv, encrypted) = value.split('.', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decoder.decode(iv)))
        cipher.updateAAD(id.toByteArray())
        return cipher.doFinal(decoder.decode(encrypted)).toString(Charsets.UTF_8)
    }
}

internal data class ModelMessage(val role: String, val content: String)
internal data class ModelUsage(val input: Int?, val output: Int?, val cached: Int?)

internal class ModelClient {
    private val http = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()
    private val json = "application/json".toMediaType()

    suspend fun catalog() = execute(Request.Builder().url("https://models.dev/api.json").build()) { response, _ ->
        val data = requireNotNull(response.body).byteStream().use {
            it.readLimited(24 * 1024 * 1024, "社区模型目录超过 24 MB。")
        }
        CommunityCatalogParser.parse(data.toString(Charsets.UTF_8), CommunityCatalogSource.ONLINE,
            System.currentTimeMillis())
    }

    suspend fun models(account: ProviderAccount): List<String> = execute(request(account, "models").build()) { response, _ ->
        val body = requireNotNull(response.body).byteStream().use {
            it.readLimited(8 * 1024 * 1024, "模型列表超过 8 MB。")
        }
        JSONObject(body.toString(Charsets.UTF_8)).records("data").map { it.string("id") }
            .filter(String::isNotBlank).distinct().sorted()
    }

    suspend fun generate(
        account: ProviderAccount,
        system: String,
        messages: List<ModelMessage>,
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit = {},
        onUsage: (ModelUsage) -> Unit = {}
    ): String {
        require(account.enabled && account.key.isNotBlank() && account.model.isNotBlank()) { "请选择已启用的模型账号。" }
        val input = JSONArray(messages.map { JSONObject().put("role", it.role).put("content", it.content) })
        val body = JSONObject().put("model", account.model).put("stream", true)
        when (account.protocol) {
            ModelProtocol.Responses -> body.put("instructions", system).put("input", input)
            ModelProtocol.Chat -> body.put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .apply { input.objects().forEach { put(it) } })
                .put("stream_options", JSONObject().put("include_usage", true))
            ModelProtocol.Anthropic -> body.put("system", system).put("messages", input).put("max_tokens", 4096)
        }
        val outgoing = request(account, account.protocol.route)
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(json)).build()
        return execute(outgoing) { response, ensureActive ->
            val result = StringBuilder()
            var terminal = false
            var usage = ModelUsage(null, null, null)
            readProviderSse(requireNotNull(response.body).byteStream(), ensureActive) { _, data ->
                if (data == "[DONE]") {
                    terminal = true
                    return@readProviderSse false
                }
                val frame = JSONObject(data)
                val type = frame.string("type")
                val error = frame.optJSONObject("error")
                check(error == null && type !in setOf("error", "response.failed", "response.incomplete")) {
                    "供应商中止了生成。"
                }
                val chunk = frame.optJSONArray("choices")?.optJSONObject(0)
                val delta = frame.optJSONObject("delta") ?: chunk?.optJSONObject("delta")
                val text = when (account.protocol) {
                    ModelProtocol.Responses -> if (type == "response.output_text.delta") frame.string("delta") else ""
                    ModelProtocol.Chat -> delta?.string("content").orEmpty()
                    ModelProtocol.Anthropic -> if (type == "content_block_delta") delta?.string("text").orEmpty() else ""
                }
                val reasoning = when {
                    type == "response.reasoning_summary_text.delta" -> frame.string("delta")
                    else -> delta?.string("reasoning_content").orEmpty().ifEmpty { delta?.string("thinking").orEmpty() }
                }
                if (text.isNotEmpty()) {
                    check(result.length + text.length <= 1_500_000) { "供应商回复超过长度限制。" }
                    result.append(text)
                    onText(text)
                }
                if (reasoning.isNotEmpty()) onReasoning(reasoning)
                val meter = frame.optJSONObject("usage") ?: frame.optJSONObject("response")?.optJSONObject("usage")
                    ?: frame.optJSONObject("message")?.optJSONObject("usage")
                if (meter != null) {
                    usage = ModelUsage(
                        meter.number("input_tokens") ?: meter.number("prompt_tokens") ?: usage.input,
                        meter.number("output_tokens") ?: meter.number("completion_tokens") ?: usage.output,
                        meter.optJSONObject("input_tokens_details")?.number("cached_tokens")
                            ?: meter.optJSONObject("prompt_tokens_details")?.number("cached_tokens")
                            ?: meter.number("cache_read_input_tokens") ?: usage.cached
                    )
                }
                terminal = type == "response.completed" || type == "message_stop"
                !terminal
            }
            onUsage(usage)
            check(terminal) { "响应中途断开，已接收的内容可保留为草稿。" }
            check(result.isNotBlank()) { "模型未返回正文。" }
            result.toString()
        }
    }

    private fun request(account: ProviderAccount, path: String): Request.Builder =
        Request.Builder().url(account.url(path)).apply {
            if (account.protocol == ModelProtocol.Anthropic) {
                header("x-api-key", account.key)
                header("anthropic-version", "2023-06-01")
            } else header("Authorization", "Bearer ${account.key}")
        }

    internal suspend fun <T> execute(request: Request, read: (Response, () -> Unit) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            check(it.isSuccessful) { modelHttpError(it.code) }
                            read(it) { if (!continuation.isActive) throw java.io.InterruptedIOException("请求已取消") }
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
}

private fun JSONObject.number(key: String): Int? = (opt(key) as? Number)?.toInt()

private fun modelHttpError(code: Int): String = when (code) {
    400 -> "接口不接受此模型或请求参数（HTTP 400）。"
    401, 403 -> "账号密钥无效或权限不足（HTTP $code）。"
    404 -> "找不到模型或接口（HTTP 404）。"
    429 -> "供应商限流或额度不足（HTTP 429）。"
    else -> "供应商请求失败（HTTP $code）。"
}
