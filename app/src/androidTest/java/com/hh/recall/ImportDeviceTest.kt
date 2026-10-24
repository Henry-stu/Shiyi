package com.hh.recall

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hh.recall.core.Article
import com.hh.recall.core.Folder
import com.hh.recall.core.Mask
import com.hh.recall.data.Library
import com.hh.recall.data.Repository
import com.hh.recall.importing.ImportType
import com.hh.recall.ui.RecallApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.Charset
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ImportDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val databaseName = "import-${UUID.randomUUID()}.db"
    private val directory = File(app.cacheDir, "test-import-${UUID.randomUUID()}").apply { mkdirs() }
    private lateinit var vm: AppModel
    private val existing = Article(title = "原文章", body = "保留原正文及挖空。", masks = listOf(Mask(0, 2)), readingOffset = 3)

    private fun mount() {
        compose.runOnUiThread { vm = AppModel(app, databaseName) }
        compose.waitUntil(5000) { vm.loaded }
        runBlocking { vm.repository.importCollection(Library(listOf(existing), listOf(Folder("target", "导入目录")))) }
        compose.runOnUiThread { vm.pendingRecovery = null }
        compose.setContent { RecallApp(vm) }
    }
    private fun asset(name: String): Uri {
        val file = File(directory, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open("import/$name").use { input -> file.outputStream().use(input::copyTo) }
        return Uri.fromFile(file)
    }
    private fun text(name: String, bytes: ByteArray): Uri = Uri.fromFile(File(directory, name).apply { writeBytes(bytes) })
    private fun start(uri: Uri, type: ImportType, folder: String? = null) {
        compose.runOnUiThread { vm.importer.start(uri, type, folder) }
        compose.waitUntil(90000) { !vm.importer.running }
        compose.waitForIdle()
    }
    @After fun cleanup() {
        if (::vm.isInitialized) { compose.runOnUiThread { vm.importer.close() }; runBlocking { vm.repository.close() } }
        app.deleteDatabase(databaseName)
        directory.deleteRecursively()
    }

    @Test fun markdownReviewEditsAndExactlyOnceSavePreserveExistingData() {
        mount(); start(text("notes.md", "# 课程\n\n中英混排 Active recall.\n".toByteArray()), ImportType.MARKDOWN, "target")
        compose.onNodeWithTag("importTextTab").assertDoesNotExist()
        compose.onNodeWithTag("importPagesTab").assertDoesNotExist()
        assertEquals(listOf(existing), vm.repository.library.value.articles)
        compose.onNodeWithTag("confirmImport").performClick()
        compose.onNodeWithText("文章标题还未命名").assertExists()
        compose.onNodeWithText("去命名").performClick()
        compose.onNodeWithTag("importTitle").assertIsFocused().performTextInput("独立标题")
        compose.runOnUiThread { vm.importer.body("已校对正文\nActive recall."); vm.confirmImport(); vm.confirmImport() }
        compose.waitUntil(10000) { vm.mode == ScreenMode.MASK && !vm.busy }
        compose.onNodeWithTag("importScreen").assertDoesNotExist()
        runBlocking {
            Repository(app, databaseName).use { fresh ->
                fresh.load(); assertEquals(2, fresh.library.value.articles.size)
                assertEquals(existing, fresh.library.value.articles.single { it.id == existing.id })
                val saved = fresh.library.value.articles.single { it.id != existing.id }
                assertEquals("独立标题", saved.title); assertEquals("已校对正文\nActive recall.", saved.body)
                assertEquals("target", saved.folderId); assertTrue(saved.masks.isEmpty())
            }
        }
    }

    @Test fun pngRequiresReviewAndProvidesSourcePagesBeforeSaving() {
        mount(); start(asset("scan.png"), ImportType.PNG)
        assertNotNull(vm.importer.preview); assertTrue(vm.importer.preview!!.needsReview)
        assertNotNull(vm.importer.preview!!.pages.single().bitmap)
        compose.runOnUiThread { vm.importer.title("PNG 校对材料") }
        compose.onNodeWithTag("confirmImport").assertIsNotEnabled()
        compose.onNodeWithTag("importTextTab").assertDoesNotExist()
        compose.onNodeWithTag("importPagesTab").assertDoesNotExist()
        compose.onNodeWithTag("importContent").performScrollToNode(hasTestTag("importSourceImage"))
        compose.onNodeWithTag("importPage-1").assertIsDisplayed()
        val image = compose.onNodeWithTag("importSourceImage").getUnclippedBoundsInRoot()
        assertTrue("原图不能再使用小缩略图", (image.right - image.left).value >= 260f)
        val bitmap = vm.importer.preview!!.pages.single().bitmap!!
        assertTrue("保留原图细节", maxOf(bitmap.width, bitmap.height) > 700)
        compose.onNodeWithTag("importSourceImage").performClick()
        compose.onNodeWithTag("importImageZoom").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("importContent").performScrollToNode(hasTestTag("importBody"))
        compose.onNodeWithTag("importBody").performTextReplacement("校对后：主动回忆\nActive recall.")
        compose.activityRule.scenario.onActivity { activity ->
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.onNodeWithTag("importReviewed").performClick()
        compose.onNodeWithTag("confirmImport").performClick()
        compose.waitUntil(10000) { vm.mode == ScreenMode.MASK }
        assertEquals(2, vm.repository.library.value.articles.size)
        assertEquals("PNG 校对材料", vm.selected!!.title)
        assertTrue(vm.selected!!.body.contains("主动回忆"))
        assertEquals("校对后：主动回忆\nActive recall.", vm.selected!!.body)
    }

    @Test fun nativeAndScannedPdfRemainPreviewOnlyUntilConfirmed() {
        mount(); start(asset("notes.pdf"), ImportType.PDF)
        assertNotNull(vm.importer.preview); assertFalse(vm.importer.preview!!.pages.any { it.scanned })
        compose.onNodeWithTag("importTextTab").assertDoesNotExist()
        compose.onNodeWithTag("importPagesTab").assertDoesNotExist()
        compose.onNodeWithTag("importSourceImage").assertDoesNotExist()
        compose.onNodeWithTag("importContent").performScrollToNode(hasTestTag("importBody"))
        compose.onNodeWithTag("importBody").assertIsDisplayed()
        assertEquals(1, vm.repository.library.value.articles.size)
        compose.runOnUiThread { vm.importer.dismiss() }
        start(asset("scan.pdf"), ImportType.PDF)
        assertEquals(2, vm.importer.preview!!.pages.size)
        assertTrue(vm.importer.preview!!.pages.all { it.scanned })
        assertTrue(vm.importer.preview!!.pages.all { it.bitmap == null })
        compose.onNodeWithTag("importPagesTab").assertDoesNotExist()
        assertTrue(vm.importer.body.contains("Retrieval Practice"))
        compose.onNodeWithTag("importBack").performClick()
        compose.onNodeWithText("放弃导入").performClick()
        assertFalse(vm.importer.visible); assertEquals(listOf(existing), vm.repository.library.value.articles)
    }

    @Test fun cancelDuringReadingCreatesNoArticleAndAllowsReuse() {
        mount(); val uri = asset("scan.pdf")
        compose.runOnUiThread { vm.importer.start(uri, ImportType.PDF); vm.importer.dismiss() }
        compose.waitUntil(30000) { !vm.importer.running }
        assertFalse(vm.importer.visible); assertNull(vm.importer.preview)
        assertEquals(listOf(existing), vm.repository.library.value.articles)
        start(text("reuse.md", "重复读取成功".toByteArray()), ImportType.MARKDOWN)
        assertEquals("重复读取成功", vm.importer.body)
    }

    @Test fun invalidPngAndExplicitLegacyEncodingDoNotSilentlySave() {
        mount(); start(text("fake.png", "不是图片".toByteArray()), ImportType.PNG)
        assertEquals("INVALID_IMAGE", vm.importer.errorCode); assertNull(vm.importer.preview)
        val uri = text("legacy.md", "中文课程，逐字保留。".toByteArray(Charset.forName("GB18030")))
        start(uri, ImportType.MARKDOWN); assertEquals("ENCODING_REQUIRED", vm.importer.errorCode)
        compose.onNodeWithText("按 GB18030 重试").performClick()
        compose.waitUntil(10000) { !vm.importer.running }
        assertEquals("中文课程，逐字保留。", vm.importer.body)
        assertEquals(listOf(existing), vm.repository.library.value.articles)
    }
}
