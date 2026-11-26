package xyz.qingjian.writer

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WritingFlowTest {
    @Test
    fun providersHaveASeparateDetailPageAndModelTab() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        context.getSharedPreferences("writing_positions", Context.MODE_PRIVATE).edit().clear().commit()
        val account = ProviderAccount(
            "qa-model-account", "验收用供应商", "https://model.example.test/v1",
            ModelProtocol.Responses, "fixture-model", "synthetic-key"
        )
        runBlocking { ProviderVault(context).save(listOf(account)) }
        ActivityScenario.launch(MainActivity::class.java).use {
            requireNotNull(device.wait(Until.findObject(By.desc("设置")), 15_000)).click()
            requireNotNull(device.wait(Until.findObject(By.text("模型供应商")), 10_000)).click()
            requireNotNull(device.wait(Until.findObject(By.text("验收用供应商")), 10_000))
            screenshot(device, "providers")
            requireNotNull(device.wait(Until.findObject(By.text("编辑")), 10_000)).click()
            requireNotNull(device.wait(Until.findObject(By.text("HTTPS API 地址")), 10_000))
            screenshot(device, "provider-connection")
            requireNotNull(device.wait(Until.findObject(By.text("模型")), 10_000)).click()
            requireNotNull(device.wait(Until.findObject(By.text("模型 ID")), 10_000))
            requireNotNull(device.wait(Until.findObject(By.text("fixture-model")), 10_000))
            screenshot(device, "provider-models")
            device.pressBack()
            requireNotNull(device.wait(Until.findObject(By.text("验收用供应商")), 10_000))
        }
    }

    @Test
    fun writeOfflineInTheSharedWorkspaceAndResumeTheBook() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val library = Library(context)
        val book = Manuscript.create("云端写作验收")
        val bookId = book.string("id")
        val chapter = book.records("volumes").first().records("chapters").first()
        val chapterId = chapter.string("id")
        val original = "山路转过第三道弯。"
        val rewritten = "穿过薄雾，她看见山路的尽头。"
        chapter.put("title", "写作验收章").put("text", original)
        val volume = book.records("volumes").first()
        val next = Manuscript.newChapter("第二章").put("text", "次日清晨。")
        volume.getJSONArray("chapters").put(next)
        runBlocking { library.store(book, null) }
        context.getSharedPreferences("writing_positions", Context.MODE_PRIVATE).edit().clear().commit()
        device.executeShellCommand("svc wifi disable")
        device.executeShellCommand("svc data disable")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                requireNotNull(device.wait(Until.findObject(By.text("云端写作验收")), 15_000)).click()
                waitForScript(scenario, "$chapterEditor?.value === ${JSONObject.quote(original)}")
                replaceBody(scenario, rewritten)
                waitForScript(scenario, "document.querySelector('.save-state [role=status]')?.textContent === '已保存'")
                runBlocking {
                    assertEquals(rewritten, chapterText(library, bookId))
                }
                screenshot(device, "chapter")
                evaluate(scenario, "document.querySelector('.mobile-writing-nav button:nth-child(2)').click()")
                waitForScript(scenario, "document.querySelector('.navigation')?.classList.contains('mobile-open')")
                evaluate(scenario, "Array.from(document.querySelectorAll('.chapter-button')).find(button => button.title.includes('第二章')).click()")
                waitForScript(scenario, "$chapterEditor?.value === '次日清晨。'")
                waitForScript(scenario, "document.querySelector('.save-state [role=status]')?.textContent === '已保存'")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertEquals(bookId, context.getSharedPreferences("writing_positions", Context.MODE_PRIVATE).getString("book", null))
                waitForScript(scenario, "$chapterEditor?.value === '次日清晨。'")
                val persisted = runBlocking { library.export(bookId) }
                assertEquals(next.string("id"), persisted.string("activeId"))
                assertEquals(chapterId, persisted.records("volumes").first().records("chapters").first().string("id"))
            }
        } finally {
            device.executeShellCommand("svc wifi enable")
            device.executeShellCommand("svc data enable")
        }
    }

    @Test
    fun aConcurrentChangeKeepsTheMainBookAndTheOfflineDraft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val library = Library(context)
        val book = Manuscript.create("并发保存验收")
        val bookId = book.string("id")
        runBlocking { library.store(book, null) }
        context.getSharedPreferences("writing_positions", Context.MODE_PRIVATE).edit().putString("book", bookId).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForScript(scenario, "$chapterEditor?.value === ''")
            runBlocking { library.updateBookText(bookId, "outline", "其他客户端的大纲。") }
            replaceBody(scenario, "手机离线草稿。")
            waitForScript(scenario, "document.querySelector('.android-recovery h1')?.textContent === '已保留本机冲突副本'")
            runBlocking {
                val copy = library.shelf.first().single { it.title == "并发保存验收（同步冲突）" }
                assertEquals("手机离线草稿。", chapterText(library, copy.id))
                assertEquals("其他客户端的大纲。", library.export(bookId).string("outline"))
                assertEquals("", chapterText(library, bookId))
            }
            screenshot(device, "writing-conflict")
        }
    }

    private val chapterEditor = "document.querySelector('textarea[aria-label=\"章节正文\"]')"

    private suspend fun chapterText(library: Library, bookId: String) =
        library.export(bookId).records("volumes").first().records("chapters").first().string("text")

    private fun replaceBody(scenario: ActivityScenario<MainActivity>, text: String) {
        check(evaluate(scenario, """
            (() => {
              const field = $chapterEditor;
              if (!field) return false;
              field.value = ${JSONObject.quote(text)};
              field.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText' }));
              return true;
            })()
        """.trimIndent()) == "true") { "找不到章节正文编辑器" }
    }

    private fun waitForScript(scenario: ActivityScenario<MainActivity>, condition: String) = runBlocking {
        withTimeout(20_000) {
            while (evaluate(scenario, condition) != "true") delay(200)
        }
    }

    private fun evaluate(scenario: ActivityScenario<MainActivity>, script: String): String? {
        val done = CountDownLatch(1)
        var result: String? = null
        scenario.onActivity { activity ->
            val web = findWebView(activity.window.decorView)
            if (web == null) done.countDown()
            else web.evaluateJavascript(script) { value -> result = value; done.countDown() }
        }
        check(done.await(4, TimeUnit.SECONDS)) { "WebView 没有返回执行结果" }
        return result
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun screenshot(device: UiDevice, name: String) {
        device.executeShellCommand("mkdir -p /sdcard/Download/qingjian-qa")
        device.executeShellCommand("screencap -p /sdcard/Download/qingjian-qa/$name.png")
    }
}
