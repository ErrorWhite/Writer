package io.github.mangi.eta.agent.model

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/** 只处理 SSE 分帧；各协议自行解释事件并在终态返回 false，不等待服务端关闭连接。 */
internal fun readProviderSse(
    stream: InputStream,
    ensureActive: () -> Unit,
    dispatchPartialFrameAtEof: Boolean = true,
    onEvent: (event: String, data: String) -> Boolean,
) {
    var event = ""
    val dataLines = mutableListOf<String>()

    fun dispatch(): Boolean {
        val name = event
        val payload = dataLines.joinToString("\n")
        event = ""
        dataLines.clear()
        if (payload.isBlank()) return true
        ensureActive()
        return onEvent(name, payload)
    }

    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
        while (true) {
            ensureActive()
            val line = reader.readLine()
            if (line == null) {
                if (dispatchPartialFrameAtEof) dispatch()
                break
            }
            when {
                line.isEmpty() -> if (!dispatch()) break
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> dataLines += line.removePrefix("data:").removePrefix(" ")
            }
        }
    }
}
