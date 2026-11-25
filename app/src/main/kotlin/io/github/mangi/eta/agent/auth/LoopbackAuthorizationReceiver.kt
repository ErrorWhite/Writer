package io.github.mangi.eta.agent.auth

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class AuthorizationCallback(val code: String, val clientId: String?)

/** Adapted from Eta's LoopbackAuthorizationReceiver. Adds the issued client ID for dynamic OAuth registration. */
internal class LoopbackAuthorizationReceiver(port: Int = 0) : AutoCloseable {
    private val server = ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 500 }
    val redirectUri = "http://127.0.0.1:${server.localPort}/auth/callback"

    fun awaitCode(state: String, ensureActive: () -> Unit): AuthorizationCallback {
        val deadline = System.nanoTime() + 600_000_000_000L
        while (System.nanoTime() < deadline) {
            ensureActive()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            socket.use {
                try {
                    val result = validateCallback(readRequest(socket), state) ?: run {
                        respond(socket, "400 Bad Request", "登录回调无效。")
                        return@use
                    }
                    runCatching { respond(socket, "200 OK", "请回到青简完成登录。") }
                    return result
                } catch (denied: AuthorizationDeclined) {
                    runCatching { respond(socket, "400 Bad Request", "授权未完成，请回到青简。") }
                    throw denied
                } catch (_: IOException) {
                    ensureActive()
                }
            }
        }
        error("ChatGPT 登录已超时，请重试。")
    }

    internal fun validateCallback(target: String, state: String): AuthorizationCallback? {
        if (!target.startsWith("/auth/callback?")) return null
        val url = (redirectUri.substringBefore("/auth/callback") + target).toHttpUrlOrNull() ?: return null
        if (url.encodedPath != "/auth/callback" || url.fragment != null) return null
        val keys = listOf("state", "iss", "code", "error", "client_id")
        if (keys.any { url.queryParameterValues(it).size > 1 }) return null
        val actual = url.queryParameter("state") ?: return null
        if (!MessageDigest.isEqual(state.toByteArray(), actual.toByteArray())) return null
        val issuer = url.queryParameter("iss")
        if (issuer != null && issuer != "https://auth.openai.com") return null
        if (url.queryParameter("error") != null) throw AuthorizationDeclined()
        val code = url.queryParameter("code")?.takeIf { it.isNotBlank() && it.length <= 8192 } ?: return null
        return AuthorizationCallback(code, url.queryParameter("client_id"))
    }

    private fun readRequest(socket: Socket): String {
        socket.soTimeout = 500
        val deadline = System.nanoTime() + 3_000_000_000L
        val bytes = ByteArrayOutputStream()
        val input = socket.getInputStream()
        var ending = 0
        while (bytes.size() < 16384 && System.nanoTime() < deadline) {
            val next = input.read()
            if (next < 0) break
            bytes.write(next)
            ending = (ending shl 8) or next
            if (ending == 0x0d0a0d0a) {
                val lines = bytes.toString(Charsets.US_ASCII.name()).split("\r\n")
                val request = lines.first().split(' ')
                val hosts = lines.drop(1).filter { it.startsWith("Host:", ignoreCase = true) }
                if (request.size == 3 && request[0] == "GET" && request[2] in setOf("HTTP/1.1", "HTTP/1.0") &&
                    hosts.size == 1 && hosts.single().substringAfter(':').trim() == "127.0.0.1:${server.localPort}") {
                    return request[1]
                }
                break
            }
        }
        return ""
    }

    private fun respond(socket: Socket, status: String, message: String) {
        val body = "<!doctype html><meta charset=utf-8><title>青简</title><p>$message</p>".toByteArray()
        val headers = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nContent-Security-Policy: default-src 'none'\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }

    override fun close() { server.close() }
}

internal class AuthorizationDeclined : IllegalStateException("ChatGPT 未授予本次登录权限。")
