package xyz.qingjian.writer

import android.content.Context
import com.nimbusds.jose.jwk.JWKSet
import io.github.mangi.eta.agent.auth.LoopbackAuthorizationReceiver
import io.github.mangi.eta.agent.auth.VerifiedIdentity
import io.github.mangi.eta.agent.auth.verifyOpenAiIdentity
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

private const val authority = "https://auth.openai.com"
private const val resource = "https://api.openai.com/v1"
private const val planScope = "chatgpt.tokens.use.direct"
private const val directScopes = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
private const val dynamicClient = "dynamic_agent_client"
private val issuedClientPattern = Regex("oaiapp_[A-Za-z0-9_-]{4,256}")
private val secureRandom = SecureRandom()

internal data class PlanProfile(val id: String, val name: String, val model: String, val connected: Boolean)

private data class PlanSession(
    val id: String, val clientId: String, val identity: VerifiedIdentity, val access: String, val refresh: String,
    val identityToken: String, val scope: String, val expiresAt: Long, val model: String
) {
    fun usesPlan() = access.isNotBlank() && planScope in scope.split(' ') && "resource.invoke" in scope.split(' ')
    fun display() = PlanProfile(id, identity.email.ifBlank { identity.name.ifBlank { "ChatGPT" } }, model, usesPlan())

    fun json() = JSONObject().put("clientId", clientId).put("subject", identity.subject).put("email", identity.email)
        .put("name", identity.name).put("access", access).put("refresh", refresh).put("idToken", identityToken)
        .put("scope", scope).put("expiresAt", expiresAt).put("model", model)
}

internal class ChatGptPlan(context: Context, private val vault: ProviderVault, private val modelClient: ModelClient) {
    private val storage = context.getSharedPreferences("chatgpt-plan", Context.MODE_PRIVATE)
    private val sessions = Mutex()
    private val authorization = Mutex()
    private var discovery: JSONObject? = null
    private var keyCache: Pair<Long, JWKSet>? = null

    suspend fun profiles(): List<PlanProfile> = sessions.withLock {
        withContext(Dispatchers.IO) { readAll().map(PlanSession::display) }
    }

    suspend fun signIn(profileId: String?, browser: (String) -> Unit) = authorization.withLock {
        val previous = sessions.withLock { withContext(Dispatchers.IO) { readAll().firstOrNull { it.id == profileId } } }
        require(profileId == null || previous != null) { "订阅账号已删除。" }
        val host = withContext(Dispatchers.IO) {
            storage.getString("host", null) ?: "urn:uuid:${newId()}".also {
                check(storage.edit().putString("host", it).commit()) { "无法保存设备标识。" }
            }
        }
        val state = randomSecret()
        val nonce = randomSecret()
        val verifier = randomSecret()
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        val receiver = withContext(Dispatchers.IO) { LoopbackAuthorizationReceiver() }
        receiver.use {
            val url = "$authority/api/accounts/authorize".toHttpUrl().newBuilder()
                .addQueryParameter("client_id", previous?.clientId ?: dynamicClient)
                .addQueryParameter("ext_agent_host_id", host)
                .addQueryParameter("response_type", "code")
                .addQueryParameter("redirect_uri", receiver.redirectUri)
                .addQueryParameter("scope", directScopes)
                .addQueryParameter("resource", resource)
                .addQueryParameter("state", state)
                .addQueryParameter("nonce", nonce)
                .addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("code_challenge", challenge)
            if (previous == null) url.addQueryParameter("agent_name_hint", "青简")
            browser(url.build().toString())
            val context = currentCoroutineContext()
            val callback = runInterruptible(Dispatchers.IO) { receiver.awaitCode(state) { context.ensureActive() } }
            val clientId = previous?.clientId ?: callback.clientId.orEmpty()
            require(issuedClientPattern.matches(clientId) && (callback.clientId == null || callback.clientId == clientId)) {
                "ChatGPT 没有返回匹配的应用注册编号。"
            }
            val token = token(mapOf("grant_type" to "authorization_code", "client_id" to clientId,
                "code" to callback.code, "code_verifier" to verifier, "redirect_uri" to receiver.redirectUri, "resource" to resource))
            val idToken = token.string("id_token")
            val identity = verify(idToken, clientId, nonce)
            require(previous == null || previous.identity.subject == identity.subject) { "请选择此配置原来绑定的 ChatGPT 账号。" }
            val id = previous?.id ?: newId()
            val next = session(id, clientId, identity, token, idToken, previous?.model.orEmpty())
            sessions.withLock { withContext(Dispatchers.IO) { writeSession(next) } }
            next.display()
        }
    }

    suspend fun setModel(profileId: String, model: String) = sessions.withLock {
        withContext(Dispatchers.IO) {
            val value = readAll().firstOrNull { it.id == profileId } ?: error("订阅账号已删除。")
            writeSession(value.copy(model = model))
        }
    }

    suspend fun disconnect(profileId: String) = sessions.withLock {
        val stored = withContext(Dispatchers.IO) { readAll().firstOrNull { it.id == profileId } }
        if (stored == null) return@withLock
        if (stored.refresh.isNotBlank()) {
            val endpoint = meta().string("revocation_endpoint")
            val request = form(safeAuthority(endpoint), mapOf("token" to stored.refresh,
                "token_type_hint" to "refresh_token", "client_id" to stored.clientId))
            modelClient.execute(request) { _, _ -> Unit }
        }
        withContext(Dispatchers.IO) {
            writeSession(stored.copy(access = "", refresh = "", identityToken = "", scope = "", expiresAt = 0))
        }
    }

    suspend fun remove(profileId: String) {
        disconnect(profileId)
        sessions.withLock {
            withContext(Dispatchers.IO) {
                val ids = ids().filterNot { it == profileId }
                check(storage.edit().remove("profile:$profileId").putString("profiles", JSONArray(ids).toString()).commit()) {
                    "未能移除本机订阅账号。"
                }
            }
        }
    }

    suspend fun listModels(profileId: String): List<String> = modelClient.models(transientAccount(access(profileId)))

    suspend fun generate(
        profileId: String, system: String, messages: List<ModelMessage>, text: (String) -> Unit,
        reasoning: (String) -> Unit, usage: (ModelUsage) -> Unit
    ) = modelClient.generate(transientAccount(access(profileId)), system, messages, text, reasoning, usage)

    private fun transientAccount(session: PlanSession) =
        ProviderAccount(session.id, session.display().name, resource, ModelProtocol.Responses, session.model, session.access)

    private suspend fun access(profileId: String): PlanSession = sessions.withLock {
        val session = withContext(Dispatchers.IO) { readAll().firstOrNull { it.id == profileId } }
            ?: error("当前 ChatGPT 订阅账号已删除。")
        require(session.usesPlan()) { "ChatGPT 未授权青简使用套餐，请重新授权。" }
        if (session.expiresAt > System.currentTimeMillis() + 60_000) return@withLock session
        require(session.refresh.isNotBlank()) { "ChatGPT 登录已过期，请重新登录。" }
        val token = token(mapOf("grant_type" to "refresh_token", "client_id" to session.clientId,
            "refresh_token" to session.refresh, "resource" to resource))
        val idToken = token.string("id_token")
        val user = if (idToken.isNotBlank()) verify(idToken, session.clientId, null) else session.identity
        require(user.subject == session.identity.subject) { "刷新后账号身份发生变化，请重新登录。" }
        val next = session(session.id, session.clientId, user, token, idToken.ifBlank { session.identityToken }, session.model,
            session.scope)
        withContext(Dispatchers.IO) { writeSession(next) }
        require(next.usesPlan()) { "ChatGPT 套餐授权已失效，请重新授权。" }
        next
    }

    private fun session(
        id: String, client: String, identity: VerifiedIdentity, body: JSONObject, idToken: String, model: String,
        previousScope: String = ""
    ): PlanSession {
        val token = body.string("access_token")
        val refresh = body.string("refresh_token")
        val duration = body.optLong("expires_in", 0)
        val bearer = body.string("token_type")
        require(token.isNotBlank() && duration in 1..86400 && bearer.equals("bearer", true)) { "ChatGPT 返回了无效的令牌。" }
        return PlanSession(id, client, identity, token, refresh, idToken,
            body.string("scope", previousScope), System.currentTimeMillis() + duration * 1000, model)
    }

    private fun ids(): List<String> {
        val array = JSONArray(storage.getString("profiles", "[]"))
        return List(array.length()) { array.getString(it) }
    }

    private fun readAll(): List<PlanSession> = ids().mapNotNull { id ->
        val encrypted = storage.getString("profile:$id", null) ?: return@mapNotNull null
        val profile = JSONObject(vault.open(encrypted, "chatgpt-plan:$id"))
        PlanSession(id, profile.string("clientId"), VerifiedIdentity(profile.string("subject"), profile.string("email"),
            profile.string("name")), profile.string("access"), profile.string("refresh"), profile.string("idToken"),
            profile.string("scope"), profile.optLong("expiresAt"), profile.string("model"))
    }

    private fun writeSession(session: PlanSession) {
        val encrypted = vault.seal(session.json().toString(), "chatgpt-plan:${session.id}")
        val names = (ids() + session.id).distinct()
        check(storage.edit().putString("profile:${session.id}", encrypted).putString("profiles", JSONArray(names).toString()).commit()) {
            "无法保存本机订阅凭据。"
        }
    }

    private suspend fun token(values: Map<String, String>) =
        readJson(form("$authority/api/accounts/oauth/token", values))

    private fun form(url: String, params: Map<String, String>): Request = Request.Builder().url(url)
        .post(FormBody.Builder().apply { params.forEach { (name, value) -> add(name, value) } }.build()).build()

    private suspend fun meta(): JSONObject {
        discovery?.let { return it }
        return readJson(Request.Builder().url("$authority/.well-known/openid-configuration").build())
            .also { require(it.string("issuer") == authority); discovery = it }
    }

    private suspend fun verify(idToken: String, client: String, nonce: String?): VerifiedIdentity {
        val keys = keyCache?.takeIf { it.first > System.currentTimeMillis() }?.second
        val cached = if (keys == null) null else runCatching { verifyOpenAiIdentity(idToken, client, nonce, keys) }.getOrNull()
        if (cached != null) return cached
        val jwks = readJson(Request.Builder().url(safeAuthority(meta().string("jwks_uri"))).build())
        val fresh = JWKSet.parse(jwks.toString())
        keyCache = System.currentTimeMillis() + 3_600_000 to fresh
        return verifyOpenAiIdentity(idToken, client, nonce, fresh)
    }

    private suspend fun readJson(request: Request) = modelClient.execute(request) { response, _ ->
        val bytes = requireNotNull(response.body).byteStream().use { it.readLimited(1024 * 1024, "授权响应过大。") }
        JSONObject(bytes.toString(Charsets.UTF_8))
    }

    private fun safeAuthority(raw: String): String {
        val url = raw.toHttpUrl()
        require(url.isHttps && url.host == "auth.openai.com" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
        return url.toString()
    }
}

private fun randomSecret(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).apply(secureRandom::nextBytes))
