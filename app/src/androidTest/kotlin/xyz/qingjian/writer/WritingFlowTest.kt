package xyz.qingjian.writer

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.ViewModelProvider
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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class WritingFlowTest {
    @Test
    fun wideWriterShowsTheDirectoryAndTheCurrentChapterTogether() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val library = Library(context)
        val book = Manuscript.create("平板写作验收")
        val bookId = book.string("id")
        val volume = book.records("volumes").single()
        val chapter = volume.records("chapters").single().put("title", "打开中的章节").put("text", "平板正文。")
        volume.getJSONArray("chapters").put(Manuscript.newChapter("目录里的下一章").put("text", "切章后的正文。"))
        runBlocking { library.store(book, null) }
        context.getSharedPreferences("writing_positions", Context.MODE_PRIVATE).edit().putString("book", bookId).commit()
        val previousSize = device.executeShellCommand("wm size").lineSequence()
            .firstOrNull { it.startsWith("Override size:") }?.substringAfter(':')?.trim() ?: "reset"
        val previousDensity = device.executeShellCommand("wm density").lineSequence()
            .firstOrNull { it.startsWith("Override density:") }?.substringAfter(':')?.trim() ?: "reset"

        try {
            device.executeShellCommand("wm size 1200x1920")
            device.executeShellCommand("wm density 200")
            ActivityScenario.launch(MainActivity::class.java).use {
                val otherChapter = requireNotNull(device.wait(Until.findObject(By.text("目录里的下一章")), 15_000))
                val body = requireNotNull(device.wait(Until.findObject(By.text(chapter.string("text"))), 10_000))
                check(otherChapter.visibleBounds.centerX() < body.visibleBounds.centerX()) { "目录未与正文并排显示" }
                otherChapter.click()
                requireNotNull(device.wait(Until.findObject(By.text("切章后的正文。")), 10_000))
                requireNotNull(device.wait(Until.findObject(By.text("打开中的章节")), 10_000))
                screenshot(device, "chapter-tablet")
            }
        } finally {
            device.executeShellCommand("wm size $previousSize")
            device.executeShellCommand("wm density $previousDensity")
        }
    }

    @Test
    fun nativeEditorWritesCommittedChineseInsteadOfImeComposition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val library = Library(context)
        val book = Manuscript.create("输入法验收")
        val bookId = book.string("id")
        val chapterId = book.string("activeId")
        runBlocking { library.store(book, null) }
        context.getSharedPreferences("writing_positions", Context.MODE_PRIVATE).edit().putString("book", bookId).commit()

        withWritingIme(device) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                requireNotNull(device.wait(Until.findObject(By.text("源码")), 15_000)).click()
                requireNotNull(device.wait(Until.findObject(By.text("测试组字")), 10_000)).click()
                requireNotNull(device.wait(Until.findObject(By.text("qing")), 10_000))
                assertEquals("", runBlocking { library.chapterText(bookId, chapterId) })
                requireNotNull(device.wait(Until.findObject(By.text("提交测试汉字")), 10_000)).click()
                assertChapterSaved(device, scenario, library, DraftKey(bookId, chapterId), "青")
                requireNotNull(device.wait(Until.findObject(By.text("完成")), 10_000)).click()
                requireNotNull(device.wait(Until.findObject(By.text("已保存")), 10_000))
            }
        }
    }

    @Test
    fun androidExportsAnEditedPcManuscriptWithItsOriginalAnchorsAndAttachments() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val library = Library(instrumentation.targetContext)
        val source = instrumentation.context.assets.open("manuscript-exchange.json").bufferedReader().use { it.readText() }
        val original = Manuscript.read(source)
        val bookId = library.store(original, null)
        val originalChapter = original.records("volumes").single().records("chapters")[1]
        val revised = originalChapter.string("text").replace("安鹭拆开信封。", "安鹭在雾中拆开信封。")
        val wordsBefore = library.directory(bookId).first().single { it.id == "chapter-two" }.wordCount
        library.stageEdit(bookId, "chapter-two", revised)
        library.commitEdit(bookId, "chapter-two")
        assertEquals(wordsBefore + 3, library.directory(bookId).first().single { it.id == "chapter-two" }.wordCount)
        val current = library.export(bookId)

        fun export(format: ExportFormat) = ByteArrayOutputStream().apply {
            format.write(current, "chapter-two", this)
        }.toByteArray()

        val json = export(ExportFormat.Json).toString(Charsets.UTF_8)
        val exchange = Manuscript.read(json)
        assertEquals(original.string("id"), exchange.string("id"))
        assertEquals(original.string("activeId"), exchange.string("activeId"))
        for (group in listOf("research", "creative", "roleplay")) {
            assertEquals(sortedJson(original.getJSONObject(group)), sortedJson(exchange.getJSONObject(group)))
        }
        val chapters = exchange.records("volumes").single().records("chapters")
        assertEquals("volume-one", exchange.records("volumes").single().string("id"))
        assertEquals(listOf("chapter-one", "chapter-two"), chapters.map { it.string("id") })
        val revisedParagraph = chapters[1].records("paragraphs").last()
        assertEquals("para-two-b", revisedParagraph.string("id"))
        assertEquals("draft-one", revisedParagraph.records("drafts").single().string("id"))
        assertEquals(revised, chapters[1].string("text"))
        check(export(ExportFormat.Markdown).toString(Charsets.UTF_8).contains("\n---\n"))
        check(export(ExportFormat.Text).toString(Charsets.UTF_8).contains("安鹭捡起信封。"))
        assertEquals("\uFEFF第二章\n\n$revised", export(ExportFormat.Chapter).toString(Charsets.UTF_8))
        ZipInputStream(export(ExportFormat.Epub).inputStream()).use { zip ->
            val mime = zip.nextEntry
            assertEquals("mimetype", mime.name)
            assertEquals(ZipEntry.STORED, mime.method)
            assertEquals("application/epub+zip", zip.readBytes().toString(Charsets.US_ASCII))
            val files = buildMap {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                }
            }
            check(files.getValue("EPUB/toc.xhtml").contains("第二章"))
            val chapter = files.getValue("EPUB/chapter-2.xhtml")
            check(chapter.contains("<blockquote>") && chapter.contains("安鹭在雾中拆开信封。"))
        }
        library.replaceFromBackup(bookId, original)
        assertEquals(originalChapter.string("text"), library.chapterText(bookId, "chapter-two"))
        assertEquals(wordsBefore, library.directory(bookId).first().single { it.id == "chapter-two" }.wordCount)
        val restorePoint = library.bookVersions(bookId).first().first()
        val savedBeforeOverwrite = library.readBookVersion(bookId, restorePoint.id)
        assertEquals(revised, savedBeforeOverwrite.records("volumes").single().records("chapters")[1].string("text"))
        instrumentation.targetContext.filesDir.resolve("android-pc-manuscript.json").writeText(json, Charsets.UTF_8)
    }

    @Test
    fun nativeResearchCardOpensTheAnchoredChapterAndBackReturnsToResearch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val library = Library(context)
        val book = Manuscript.create("资料导航验收")
        val bookId = book.string("id")
        val target = Manuscript.newChapter("原文所在章")
        editChapter(target, "这段是资料卡所指的原文。")
        book.records("volumes").single().getJSONArray("chapters").put(target)
        val anchor = JSONObject().put("chapterId", target.string("id"))
            .put("paragraphId", target.records("paragraphs").single().string("id"))
        book.obj("research").array("annotations").put(JSONObject().put("id", "qa-research-anchor")
            .put("title", "跳转批注").put("text", "用于检查原文回链。").put("anchor", anchor))
        runBlocking { library.store(book, null) }
        context.getSharedPreferences("writing_positions", Context.MODE_PRIVATE).edit().putString("book", bookId).commit()

        ActivityScenario.launch(MainActivity::class.java).use {
            val desk = requireNotNull(device.wait(Until.findObject(By.text("工作台")), 15_000))
            check(desk.visibleBounds.centerY() > device.displayHeight / 2) { "手机工作台入口未放在底部" }
            desk.click()
            requireNotNull(device.wait(Until.findObject(By.text("资料")), 10_000)).click()
            requireNotNull(device.wait(Until.findObject(By.text("查看原文")), 10_000)).click()
            requireNotNull(device.wait(Until.findObject(By.text("原文所在章")), 10_000))
            requireNotNull(device.wait(Until.findObject(By.text("这段是资料卡所指的原文。")), 10_000))
            device.pressBack()
            requireNotNull(device.wait(Until.findObject(By.text("跳转批注")), 10_000))
            requireNotNull(device.wait(Until.findObject(By.text("审阅")), 10_000)).click()
            requireNotNull(device.wait(Until.findObject(By.text("AI 整理资料")), 10_000))
            requireNotNull(device.wait(Until.findObject(By.text("审阅与比稿")), 10_000))
            requireNotNull(device.wait(Until.findObject(By.text("章节版本")), 10_000)).click()
            requireNotNull(device.wait(Until.findObject(By.text("章节还原点")), 10_000))
            device.pressBack()
            requireNotNull(device.wait(Until.findObject(By.text("AI 使用量")), 10_000)).click()
            requireNotNull(device.wait(Until.findObject(By.text("尚无模型用量记录")), 10_000))
        }
    }

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
    fun writeOfflineInTheNativeEditorAndResumeTheNextChapter() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val library = Library(context)
        val book = Manuscript.create("原生续写验收")
        val bookId = book.string("id")
        val volume = book.records("volumes").first()
        val first = volume.records("chapters").first().put("title", "原生验收首章").put("text", "第一段。")
        val next = Manuscript.newChapter("原生验收次章").put("text", "次日清晨。")
        volume.getJSONArray("chapters").put(next)
        runBlocking { library.store(book, null) }
        context.getSharedPreferences("writing_positions", Context.MODE_PRIVATE).edit().clear().commit()

        device.executeShellCommand("svc wifi disable")
        device.executeShellCommand("svc data disable")
        try {
            withWritingIme(device) {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    requireNotNull(device.wait(Until.findObject(By.text("原生续写验收")), 15_000)).click()
                    requireNotNull(device.wait(Until.findObject(By.text("源码")), 10_000)).click()
                    requireNotNull(device.wait(Until.findObject(By.text("输入测试长章")), 10_000)).click()
                    assertChapterSaved(device, scenario, library, DraftKey(bookId, first.string("id")), longChapterFixture())
                    requireNotNull(device.wait(Until.findObject(By.text("完成")), 10_000)).click()
                    requireNotNull(device.wait(Until.findObject(By.text("已保存")), 10_000))
                    scenario.onActivity { check(findWebView(it.window.decorView) == null) }
                    screenshot(device, "chapter")
                    requireNotNull(device.wait(Until.findObject(By.text("目录")), 10_000)).click()
                    requireNotNull(device.wait(Until.findObject(By.text("原生验收次章")), 10_000)).click()
                    requireNotNull(device.wait(Until.findObject(By.text("次日清晨。")), 10_000))
                }
                ActivityScenario.launch(MainActivity::class.java).use {
                    requireNotNull(device.wait(Until.findObject(By.text("次日清晨。")), 15_000))
                    val saved = runBlocking { library.export(bookId) }
                    assertEquals(next.string("id"), saved.string("activeId"))
                    assertEquals(first.string("id"), saved.records("volumes").first().records("chapters").first().string("id"))
                }
            }
        } finally {
            device.executeShellCommand("svc wifi enable")
            device.executeShellCommand("svc data enable")
        }
    }

    @Test
    fun openingNativeBookRecoversItsJournalAndKeepsLegacyChapterIdentity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val library = Library(context)
        val book = Manuscript.read("""
            {
              "id": "qa-room-journal", "title": "旧稿恢复验收", "volume": "旧卷",
              "chapters": [{
                "id": "chapter-old", "title": "旧章", "text": "原稿。",
                "paragraphs": [{ "id": "paragraph-old", "text": "原稿。" }]
              }],
              "research": {
                "annotations": [{
                  "id": "note-old", "text": "保留这条批注",
                  "anchor": { "chapterId": "chapter-old", "paragraphId": "paragraph-old" }
                }]
              }
            }
        """.trimIndent())
        val bookId = book.string("id")
        val chapterId = book.string("activeId")
        val text = "恢复草稿。"
        runBlocking {
            library.store(book, null)
            library.stageEdit(bookId, chapterId, text)
            assertEquals("原稿。", library.chapterText(bookId, chapterId))
        }
        context.getSharedPreferences("writing_positions", Context.MODE_PRIVATE).edit().putString("book", bookId).commit()

        ActivityScenario.launch(MainActivity::class.java).use {
            requireNotNull(device.wait(Until.findObject(By.text(text)), 15_000))
            val saved = runBlocking { library.export(bookId) }
            val volume = saved.records("volumes").single()
            val chapter = volume.records("chapters").single()
            assertEquals("旧卷", volume.string("title"))
            assertEquals(chapterId, chapter.string("id"))
            assertEquals("paragraph-old", chapter.records("paragraphs").single().string("id"))
            assertEquals("paragraph-old", saved.getJSONObject("research").records("annotations").single()
                .getJSONObject("anchor").string("paragraphId"))
            assertEquals(text, chapter.string("text"))
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
                openLegacyWorkbench(device)
                waitForScript(scenario, "$chapterEditor?.value === ${JSONObject.quote(original)}")
                checkEditorVisible(scenario)
                evaluate(scenario, """
                    document.fonts.load('650 16px "Source Han Serif"').then(faces => {
                      document.body.dataset.titleFontReady = String(faces.some(face => face.status === 'loaded'));
                    });
                """.trimIndent())
                waitForScript(scenario, "document.body.dataset.titleFontReady === 'true'")
                replaceBody(scenario, rewritten)
                waitForScript(scenario, "document.querySelector('.save-state [role=status]')?.textContent === '已保存'")
                runBlocking {
                    assertEquals(rewritten, chapterText(library, bookId))
                }
                device.waitForIdle()
                screenshot(device, "chapter-legacy")
                evaluate(scenario, "document.querySelector('.mobile-writing-nav button:nth-child(2)').click()")
                waitForScript(scenario, "document.querySelector('.navigation')?.classList.contains('mobile-open')")
                evaluate(scenario, "Array.from(document.querySelectorAll('.chapter-button')).find(button => button.title.includes('第二章')).click()")
                waitForScript(scenario, "$chapterEditor?.value === '次日清晨。'")
                checkEditorVisible(scenario)
                waitForScript(scenario, "document.querySelector('.save-state [role=status]')?.textContent === '已保存'")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertEquals(bookId, context.getSharedPreferences("writing_positions", Context.MODE_PRIVATE).getString("book", null))
                openLegacyWorkbench(device)
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
            openLegacyWorkbench(device)
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

    @Test
    fun legacyJournalRecoveryReturnsToTheNativeEditorAndClearsTheJournal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val library = Library(context)
        val book = Manuscript.create("本机恢复验收")
        val bookId = book.string("id")
        val baseline = runBlocking {
            library.store(book, null)
            library.export(bookId)
        }
        val revision = runBlocking { workbenchRevision(baseline) }
        val text = "这是尚未写入书库的离线草稿。"
        val snapshot = JSONObject(baseline.toString())
        editChapter(snapshot.records("volumes").first().records("chapters").first(), text)
        val entry = JSONObject().put("id", bookId).put("version", "qa-uncommitted-journal")
            .put("revision", revision).put("snapshot", snapshot.toString())
        context.getSharedPreferences("writing_positions", Context.MODE_PRIVATE).edit().putString("book", bookId).commit()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openLegacyWorkbench(device)
            waitForScript(scenario, "$chapterEditor?.value === ''")
            evaluate(scenario, """
                (() => {
                  const request = indexedDB.open('qingjian-autosave', 2);
                  request.onsuccess = () => {
                    const db = request.result;
                    const write = db.transaction('pending', 'readwrite');
                    write.objectStore('pending').put($entry, ${JSONObject.quote(bookId)});
                    write.oncomplete = () => {
                      db.close();
                      document.body.dataset.journalReady = 'true';
                    };
                  };
                })()
            """.trimIndent())
            waitForScript(scenario, "document.body.dataset.journalReady === 'true'")
            assertEquals("", runBlocking { chapterText(library, bookId) })
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            requireNotNull(device.wait(Until.findObject(By.desc("作品管理")), 15_000)).click()
            requireNotNull(device.wait(Until.findObject(By.text("恢复旧工作区草稿")), 10_000)).click()
            requireNotNull(device.wait(Until.findObject(By.text(text)), 15_000))
            assertEquals(text, runBlocking { chapterText(library, bookId) })
            scenario.onActivity { check(findWebView(it.window.decorView) == null) }
            openLegacyWorkbench(device)
            waitForScript(scenario, "$chapterEditor?.value === ${JSONObject.quote(text)}")
            evaluate(scenario, """
                (() => {
                  const request = indexedDB.open('qingjian-autosave', 2);
                  request.onsuccess = () => {
                    const db = request.result;
                    const entry = db.transaction('pending').objectStore('pending').get(${JSONObject.quote(bookId)});
                    entry.onsuccess = () => {
                      document.body.dataset.journalCleared = String(entry.result === undefined);
                      db.close();
                    };
                  };
                })()
            """.trimIndent())
            waitForScript(scenario, "document.body.dataset.journalCleared === 'true'")
        }
    }

    private val chapterEditor = "document.querySelector('textarea[aria-label=\"章节正文\"]')"

    private fun openLegacyWorkbench(device: UiDevice) {
        requireNotNull(device.wait(Until.findObject(By.desc("作品管理")), 15_000)).click()
        requireNotNull(device.wait(Until.findObject(By.text("旧工作区回归")), 10_000)).click()
    }

    private suspend fun chapterText(library: Library, bookId: String) =
        library.export(bookId).records("volumes").first().records("chapters").first().string("text")

    private fun assertChapterSaved(
        device: UiDevice, scenario: ActivityScenario<MainActivity>, library: Library, key: DraftKey, expected: String
    ) {
        val saved = runBlocking {
            withTimeoutOrNull(10_000) {
                while (library.chapterText(key.bookId, key.chapterId) != expected) delay(100)
                true
            }
        }
        if (saved == true) return
        var pending: Draft? = null
        var notice: String? = null
        scenario.onActivity { activity ->
            val model = ViewModelProvider(activity)[WriterModel::class.java]
            pending = model.drafts.value[key]
            notice = model.notice.value
        }
        val stored = runBlocking { library.chapterText(key.bookId, key.chapterId) }
        val visible = device.findObject(By.desc("章节正文"))?.text
        screenshot(device, if (expected.length == 1) "ime-save-timeout" else "long-chapter-save-timeout")
        check(false) {
            "正文保存超时。预期 ${expected.length} 字符，数据库 ${stored.length} 字符，编辑器 ${visible?.length} 字符，" +
                "保存状态 ${pending?.state}，待保存 ${pending?.text?.length} 字符，提示 $notice。"
        }
    }

    private fun checkEditorVisible(scenario: ActivityScenario<MainActivity>) {
        val hitTest = """
            (() => {
              const field = $chapterEditor;
              if (!field) return false;
              const rect = field.getBoundingClientRect();
              if (rect.width < 160 || rect.height < 22 || rect.top < 0 ||
                  rect.top + 20 >= innerHeight || rect.left < 0 || rect.right > innerWidth) return false;
              return document.elementFromPoint(rect.left + 12, rect.top + 12) === field;
            })()
        """.trimIndent()
        val visible = runBlocking {
            withTimeoutOrNull(5_000) {
                while (evaluate(scenario, hitTest) != "true") delay(100)
                true
            } ?: false
        }
        check(visible) {
            val geometry = evaluate(scenario, """
                (() => {
                  const field = $chapterEditor;
                  const rect = field?.getBoundingClientRect();
                  const hit = rect && document.elementFromPoint(rect.left + 12, rect.top + 12);
                  return JSON.stringify({ rect, hit: hit?.outerHTML.slice(0, 180), viewport: [innerWidth, innerHeight] });
                })()
            """.trimIndent())
            "正文编辑区不在可操作的屏幕范围内：$geometry"
        }
    }

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

    private fun withWritingIme(device: UiDevice, action: () -> Unit) {
        val previous = device.executeShellCommand("settings get secure default_input_method").trim()
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        val ime = "$testPackage/${WritingTestIme::class.java.name}"
        device.executeShellCommand("ime enable $ime")
        try {
            device.executeShellCommand("ime set $ime")
            assertEquals(ime, device.executeShellCommand("settings get secure default_input_method").trim())
            action()
        } finally {
            if (previous.isNotEmpty() && previous != "null") device.executeShellCommand("ime set $previous")
            if (previous != ime) device.executeShellCommand("ime disable $ime")
        }
    }

    private fun screenshot(device: UiDevice, name: String) {
        device.executeShellCommand("mkdir -p /sdcard/Download/qingjian-qa")
        device.executeShellCommand("screencap -p /sdcard/Download/qingjian-qa/$name.png")
    }
}
