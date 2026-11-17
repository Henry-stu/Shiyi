package com.hh.recall

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hh.recall.core.Article
import com.hh.recall.core.Mask
import com.hh.recall.data.Library
import com.hh.recall.ui.RecallApp
import com.hh.recall.ui.ReaderView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.After
import kotlinx.coroutines.runBlocking
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: AppModel
    private val databaseName = "navigation-${UUID.randomUUID()}.db"
    private val article = Article(id = "ui-regression-article", title = "界面回归材料", body = "手动挖空保持文字排版。\n这是第二段文字。", masks = listOf(Mask(0, 4, "test-mask")))

    private fun mount() {
        compose.runOnUiThread { vm = AppModel(ApplicationProvider.getApplicationContext<Application>(), databaseName) }
        compose.waitUntil(5000) { vm.loaded }
        compose.runOnUiThread { vm.pendingRecovery = null; vm.repository.library.value = Library(listOf(article)) }
        compose.setContent { RecallApp(vm) }
        compose.waitForIdle()
    }

    @After fun closeDatabase() { if (::vm.isInitialized) runBlocking { vm.repository.close() } }

    @Test fun homeAndArticleAreSeparateAndBackResetsAnswers() {
        mount()
        compose.onNodeWithTag("library").assertExists()
        compose.onNodeWithTag("importButton").assertExists()
        compose.onNodeWithTag("themeToggle").assertExists()
        assertTrue(compose.onNodeWithTag("themeToggle").fetchSemanticsNode().boundsInRoot.right <=
            compose.onNodeWithTag("importButton").fetchSemanticsNode().boundsInRoot.left)
        compose.onNodeWithTag("newArticle").assertExists()
        compose.onNodeWithContentDescription("新建文章").assertDoesNotExist()
        compose.onNodeWithTag("article-${article.id}").performClick()
        compose.onNodeWithTag("articlePage").assertExists()
        compose.onNodeWithTag("newArticle").assertDoesNotExist()
        compose.onNodeWithTag("library").assertDoesNotExist()
        compose.runOnIdle { vm.reveal.toggle("test-mask") }
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("library").assertExists()
        compose.runOnIdle { assertTrue(vm.reveal.revealed.value.isEmpty()) }
    }

    @Test fun emptyOrWhitespaceTitleShowsPromptAndFocusesTitleFieldBeforeSaving() {
        mount()
        compose.runOnUiThread {
            vm.newArticle()
            vm.changeTitle("   ")
            vm.replaceBody(0, 0, "粘贴的正文内容", false)
        }
        compose.onNodeWithTag("save").performClick()
        compose.onNodeWithText("文章标题还未命名").assertExists()
        compose.onNodeWithText("去命名").performClick()
        compose.onNodeWithTag("title").assertIsFocused().performTextInput("独立标题")
        compose.onNodeWithTag("save").performClick()
        compose.waitUntil(5000) { vm.mode == ScreenMode.MASK }
        compose.runOnIdle {
            assertEquals("独立标题", vm.selected?.title)
            assertEquals("粘贴的正文内容", vm.selected?.body)
        }
    }

    @Test fun homeImportMenuOffersMarkdownPdfAndPngImageTypes() {
        mount()
        compose.onNodeWithTag("importButton").performClick()
        compose.onNodeWithText("Markdown 文件").assertExists()
        compose.onNodeWithText("PDF 文件").assertExists()
        compose.onNodeWithText("PNG图片").assertExists()
    }

    @Test fun homeThemeSwitchPersistsAndSettingsNoLongerDuplicatesIt() {
        mount()
        val original = vm.dark
        try {
            compose.onNodeWithTag("themeToggle").performClick()
            compose.runOnIdle {
                assertEquals(!original, vm.dark)
                assertEquals(!original, ApplicationProvider.getApplicationContext<Application>()
                    .getSharedPreferences("settings", 0).getBoolean("dark", original))
            }
            compose.onNodeWithContentDescription("更多").performClick()
            compose.onNodeWithText("显示设置").performClick()
            compose.onNodeWithText("阅读设置").assertExists()
            // The home toolbar remains in the background semantics tree; only that switch exists.
            compose.onAllNodes(isToggleable()).assertCountEquals(1)
        } finally { compose.runOnUiThread { vm.theme(original) } }
    }

    @Test fun toolbarExpansionDoesNotMoveOrReflowNativeText() {
        mount(); compose.onNodeWithTag("article-${article.id}").performClick()
        val before = compose.onNodeWithTag("reader").fetchSemanticsNode().boundsInRoot
        var positions = emptyList<Float>()
        compose.runOnIdle {
            val text = findReader(compose.activity.window.decorView)!!.textView
            positions = (0..article.body.length).map { text.layout.getPrimaryHorizontal(it) }
        }
        compose.onNodeWithTag("editMasks").assertDoesNotExist()
        compose.onNodeWithTag("toolbarToggle").performClick()
        compose.onNodeWithTag("editMasks").assertExists()
        assertEquals(before, compose.onNodeWithTag("reader").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle {
            val text = findReader(compose.activity.window.decorView)!!.textView
            assertEquals(positions, (0..article.body.length).map { text.layout.getPrimaryHorizontal(it) })
        }
        compose.onNodeWithTag("toggleAll").performClick()
        compose.runOnIdle { assertEquals(setOf("test-mask"), vm.reveal.revealed.value) }
        compose.onNodeWithTag("toolbarToggle").performClick()
        assertEquals(before, compose.onNodeWithTag("reader").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun searchAndGridPositionSurviveArticleRoundTrip() {
        mount()
        compose.runOnIdle { vm.repository.library.value = Library((0..39).map { article.copy(id = "ui-grid-$it", title = "材料 $it") }) }
        compose.onNodeWithTag("search").performTextInput("材料")
        compose.onNodeWithTag("articleGrid").performScrollToIndex(12)
        compose.onNodeWithTag("article-ui-grid-12").assertIsDisplayed().performClick()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("article-ui-grid-12").assertIsDisplayed()
        compose.onNodeWithTag("search").assertTextContains("材料")
    }

    @Test fun editingKeepsSaveVisibleAndDiscardRestoresOriginal() {
        mount(); compose.onNodeWithTag("article-${article.id}").performClick()
        compose.onNodeWithTag("toolbarToggle").performClick()
        compose.onNodeWithTag("editBody").performClick()
        compose.onNodeWithTag("save").assertIsDisplayed()
        compose.onNodeWithText("完成").assertDoesNotExist()
        compose.onNodeWithTag("toolbarToggle").assertDoesNotExist()
        compose.runOnIdle { vm.replaceBody(0, 0, "X", false) }
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithTag("save").assertIsDisplayed()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.waitUntil(5000) { vm.mode == ScreenMode.READ }
        compose.runOnIdle { assertEquals(article.body, vm.snapshot.text); assertEquals(article.masks, vm.snapshot.masks) }
    }

    private fun findReader(view: android.view.View): ReaderView? {
        if (view is ReaderView) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findReader(view.getChildAt(i))?.let { return it }
        return null
    }
}
