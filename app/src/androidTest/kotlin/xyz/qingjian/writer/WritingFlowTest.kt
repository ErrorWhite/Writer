package xyz.qingjian.writer

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

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
    fun writeInTheReadingPageReturnFromTheDeskAndResumeTheBook() {
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
        runBlocking { library.store(book, null) }
        context.getSharedPreferences("writing_positions", Context.MODE_PRIVATE).edit().clear().commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            requireNotNull(device.wait(Until.findObject(By.text("云端写作验收")), 15_000)).click()
            requireNotNull(device.wait(Until.findObject(By.text(original)), 15_000)).click()
            val editor = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000))
            editor.text = rewritten
            requireNotNull(device.wait(Until.findObject(By.text("完成")), 10_000)).click()
            runBlocking {
                withTimeout(15_000) {
                    while (library.export(bookId).records("volumes").first().records("chapters").first().string("text") != rewritten) {
                        delay(200)
                    }
                }
            }
            screenshot(device, "chapter")
            requireNotNull(device.wait(Until.findObject(By.desc("工作台")), 10_000)).click()
            device.wait(Until.hasObject(By.text("工作台")), 10_000)
            device.pressBack()
            requireNotNull(device.wait(Until.findObject(By.text(rewritten)), 10_000))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            assertEquals(bookId, context.getSharedPreferences("writing_positions", Context.MODE_PRIVATE).getString("book", null))
            requireNotNull(device.wait(Until.findObject(By.text(rewritten)), 15_000))
            val persisted = runBlocking { library.export(bookId) }
            assertEquals(chapterId, persisted.string("activeId"))
        }
    }

    private fun screenshot(device: UiDevice, name: String) {
        device.executeShellCommand("mkdir -p /sdcard/Download/qingjian-qa")
        device.executeShellCommand("screencap -p /sdcard/Download/qingjian-qa/$name.png")
    }
}
