package com.hh.recall

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hh.recall.core.*
import com.hh.recall.data.Library
import com.hh.recall.ui.RecallApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CategoryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: AppModel
    private val databaseName = "categories-${UUID.randomUUID()}.db"
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var originalSort = "NAME"
    private var originalList = false
    private val source = Article("category-source", "分类内文章", "甲乙正文", "category-folder", listOf(Mask(0, 1, "category-mask")))
    private fun mount(many: Boolean = false) {
        val prefs = app.getSharedPreferences("settings", 0)
        originalSort = prefs.getString("folderSort", "NAME")!!; originalList = prefs.getBoolean("folderList", false)
        compose.runOnUiThread { vm = AppModel(app, databaseName) }
        compose.waitUntil(5000) { vm.loaded }
        runBlocking { vm.repository.importCollection(Library(listOf(source), listOf(Folder("category-folder", "测试目录"), Folder("empty-folder", "空目录")) +
                if (many) (0..49).map { Folder("folder-$it", "文件夹%02d".format(it)) } else emptyList())) }
        compose.runOnUiThread {
            vm.pendingRecovery = null
            vm.sortFolders(FolderSort.NAME)
            if (vm.folderList) vm.folderView()
        }
        compose.setContent { RecallApp(vm) }
    }
    @After fun cleanup() {
        if (::vm.isInitialized) vm.repository.close()
        app.deleteDatabase(databaseName)
        app.getSharedPreferences("settings", 0).edit().putString("folderSort", originalSort).putBoolean("folderList", originalList).commit()
    }

    @Test fun folderArticleRoundTripKeepsSourceAndAnswersReset() {
        mount()
        compose.onNodeWithContentDescription("新建文章").assertDoesNotExist()
        compose.onNodeWithTag("newArticle").assertExists()
        compose.onNodeWithTag("navCategories").performClick()
        compose.onNodeWithTag("categoryPage").assertExists()
        compose.onNodeWithTag("category-unfiled").assertExists()
        compose.onNodeWithTag("category-category-folder").performClick()
        compose.onNodeWithTag("bottomNavigation").assertExists()
        compose.onNodeWithTag("article-${source.id}").performClick()
        compose.onNodeWithTag("bottomNavigation").assertDoesNotExist()
        compose.runOnIdle { vm.reveal.toggle("category-mask") }
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("article-${source.id}").assertExists()
        compose.runOnIdle { assertEquals(AppPage.FOLDER, vm.page); assertTrue(vm.reveal.revealed.value.isEmpty()) }
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("categoryPage").assertExists()
    }

    @Test fun searchViewAndSortSurvivePageSwitchAndPreferencesReload() {
        mount()
        compose.onNodeWithTag("navCategories").performClick()
        compose.onNodeWithTag("categorySearchToggle").performClick()
        compose.onNodeWithTag("folderSearch").performTextInput("空目录")
        compose.onNodeWithTag("category-empty-folder").assertExists()
        compose.onNodeWithTag("category-category-folder").assertDoesNotExist()
        compose.onNodeWithTag("categoryView").performClick()
        compose.onNodeWithTag("categorySort").performClick()
        compose.onNodeWithText("修改时间排序").performClick()
        compose.onNodeWithTag("navHome").performClick()
        compose.onNodeWithTag("navCategories").performClick()
        compose.onNodeWithTag("folderSearch").assertTextContains("空目录")
        compose.runOnIdle {
            assertTrue(vm.folderList); assertEquals(FolderSort.MODIFIED, vm.folderSort)
            val reopened = AppModel(app, databaseName)
            assertEquals(AppPage.HOME, reopened.page); assertTrue(reopened.folderList); assertEquals(FolderSort.MODIFIED, reopened.folderSort)
            vm.sortFolders(FolderSort.NAME); vm.folderView()
        }
    }

    @Test fun emptyFolderNewArticleIsAssignedAndDiscardReturnsToFolder() {
        mount(); compose.onNodeWithTag("navCategories").performClick()
        compose.onNodeWithTag("category-empty-folder").performClick()
        compose.onNodeWithTag("newArticle").performClick()
        compose.runOnIdle { assertEquals("empty-folder", vm.selected?.folderId) }
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.waitUntil(5000) { vm.page == AppPage.FOLDER }
        compose.onNodeWithTag("newArticle").assertIsDisplayed()
    }

    @Test fun categoryScrollRestoresAfterFolderVisitAndBackClosesSearchFirst() {
        mount(many = true); compose.onNodeWithTag("navCategories").performClick()
        compose.onNodeWithTag("categoryGrid").performScrollToIndex(30)
        val node = compose.onNodeWithTag("category-folder-30")
        node.performScrollTo().performClick()
        compose.onNodeWithTag("back").performClick()
        node.assertIsDisplayed()
        compose.onNodeWithTag("categorySearchToggle").performClick()
        compose.onNodeWithTag("folderSearch").performTextInput("测试")
        compose.runOnIdle { vm.backLibrary() }
        compose.onNodeWithTag("folderSearch").assertDoesNotExist()
        compose.runOnIdle { assertEquals(AppPage.CATEGORIES, vm.page); assertEquals("", vm.categoryQuery); vm.backLibrary() }
        compose.onNodeWithTag("library").assertExists()
    }

    @Test fun directRenameReordersAndKeepsFolderContentsAndTimes() {
        mount(); compose.onNodeWithTag("navCategories").performClick()
        val before = vm.repository.library.value
        val original = before.folders.first { it.id == "category-folder" }
        compose.onNodeWithTag("renameFolder-unfiled").assertDoesNotExist()
        compose.onNodeWithTag("renameFolder-category-folder").performScrollTo().performClick()
        compose.onNodeWithText("重命名文件夹").assertIsDisplayed()
        compose.onNodeWithTag("nameInput").performScrollTo().performTextReplacement("最后目录")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.repository.library.value.folders.any { it.id == original.id && it.name == "最后目录" } }
        assertVisibleFolderOrder()
        compose.runOnIdle {
            val renamed = vm.repository.library.value.folders.first { it.id == original.id }
            assertEquals(original.lastOpenedAt, renamed.lastOpenedAt)
            assertTrue(renamed.updatedAt > original.updatedAt)
            assertEquals(before.articles, vm.repository.library.value.articles)
            assertEquals(AppPage.CATEGORIES, vm.page)
            vm.sortFolders(FolderSort.MODIFIED)
        }
        assertVisibleFolderOrder()
        runBlocking { vm.repository.openFolder("empty-folder") }
        compose.runOnIdle { vm.sortFolders(FolderSort.OPENED) }
        compose.onNodeWithTag("renameFolder-category-folder").performScrollTo().performClick()
        compose.onNodeWithTag("nameInput").performScrollTo().performTextReplacement("阿一目录")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.repository.library.value.folders.any { it.id == original.id && it.name == "阿一目录" } }
        assertVisibleFolderOrder()
        val persisted = com.hh.recall.data.Repository(app, databaseName)
        try {
            runBlocking { persisted.load() }
            assertEquals("阿一目录", persisted.library.value.folders.first { it.id == original.id }.name)
            assertEquals(before.articles, persisted.library.value.articles)
        } finally { persisted.close() }
    }

    @Test fun cardsKeepEqualSizeAndCountOnRightInBothViews() {
        mount(); compose.onNodeWithTag("navCategories").performClick()
        fun check() {
            val cards = listOf("unfiled", "category-folder", "empty-folder").map { id ->
                val card = compose.onNodeWithTag("category-$id").fetchSemanticsNode().boundsInRoot
                val name = compose.onNodeWithTag("folderName-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val count = compose.onNodeWithTag("folderCount-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue("篇数应在名称右侧", count.left >= name.right)
                assertTrue("名称应在卡片内", card.contains(name.topLeft) && card.contains(name.bottomRight - androidx.compose.ui.geometry.Offset(1f, 1f)))
                assertTrue("篇数应在卡片内", count.right <= card.right)
                if (id != "unfiled") {
                    val button = compose.onNodeWithTag("renameFolder-$id").fetchSemanticsNode().boundsInRoot
                    assertTrue(button.width >= 48 * app.resources.displayMetrics.density - 1)
                    assertTrue(button.right <= card.right && button.top >= card.top)
                }
                val full = compose.onNodeWithTag("category-$id").getUnclippedBoundsInRoot()
                (full.right - full.left).value to (full.bottom - full.top).value
            }
            cards.forEach { assertEquals(cards.first().first, it.first, 1f); assertEquals(cards.first().second, it.second, 1f) }
        }
        check()
        compose.onNodeWithTag("categoryView").performClick()
        check()
        compose.onNodeWithTag("renameFolder-empty-folder").performScrollTo().performClick()
        compose.onNodeWithText("取消").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(AppPage.CATEGORIES, vm.page) }
    }

    @Test fun renameUpdatesSearchAndCancelOrUnchangedSaveKeepsData() {
        mount(); compose.onNodeWithTag("navCategories").performClick()
        val before = vm.repository.library.value
        compose.onNodeWithTag("renameFolder-category-folder").performScrollTo().performClick()
        compose.onNodeWithTag("nameInput").performScrollTo().performTextReplacement("不会保存")
        compose.onNodeWithText("取消").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(before, vm.repository.library.value) }
        compose.onNodeWithTag("renameFolder-category-folder").performScrollTo().performClick()
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.waitUntil(5000) { !vm.busy }
        compose.runOnIdle { assertEquals(before, vm.repository.library.value) }
        compose.onNodeWithTag("categorySearchToggle").performClick()
        compose.onNodeWithTag("folderSearch").performTextInput("测试")
        compose.onNodeWithTag("renameFolder-category-folder").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("搜索后点击双点不能触发悬浮导航", AppPage.CATEGORIES, vm.page) }
        compose.onNodeWithTag("nameInput").performScrollTo().performTextReplacement("新名称")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.repository.library.value.folders.any { it.name == "新名称" } }
        compose.onNodeWithTag("category-category-folder").assertDoesNotExist()
        compose.onNodeWithTag("folderSearch").performTextReplacement("新名称")
        compose.onNodeWithTag("category-category-folder").performScrollTo().assertIsDisplayed()
    }

    private fun assertVisibleFolderOrder() {
        compose.waitForIdle()
        val expected = listOf("unfiled") + sortedFolders(vm.repository.library.value.folders, vm.folderSort).map { it.id }
        val actual = expected.sortedWith(compareBy<String> {
            compose.onNodeWithTag("category-$it").fetchSemanticsNode().boundsInRoot.top
        }.thenBy { compose.onNodeWithTag("category-$it").fetchSemanticsNode().boundsInRoot.left })
        assertEquals(expected, actual)
    }

    @Test fun responsiveGridUsesTwoThreeOrFourColumnsAndLargerCenteredArtwork() {
        mount(many = true); compose.onNodeWithTag("navCategories").performClick()
        val grid = compose.onNodeWithTag("categoryGrid").getUnclippedBoundsInRoot()
        val width = (grid.right - grid.left).value
        val expected = when { width >= 800f -> 4; width >= 560f -> 3; else -> 2 }
        val ids = listOf("unfiled") + sortedFolders(vm.repository.library.value.folders, vm.folderSort).map { it.id }
        val row = ids.take(expected).map { compose.onNodeWithTag("category-$it").fetchSemanticsNode().boundsInRoot }
        row.forEach { assertEquals(row.first().top, it.top, 1f); assertEquals(row.first().width, it.width, 1f) }
        assertTrue(compose.onNodeWithTag("category-${ids[expected]}").fetchSemanticsNode().boundsInRoot.top > row.first().top)
        val artwork = compose.onNodeWithTag("folderArtwork-unfiled", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(88f, (artwork.right - artwork.left).value, 1f)
        val card = compose.onNodeWithTag("category-unfiled").getUnclippedBoundsInRoot()
        assertEquals(((card.left + card.right) / 2).value, ((artwork.left + artwork.right) / 2).value, 1f)
        val shot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(app.cacheDir, "category-layout-${app.resources.configuration.orientation}.png").outputStream().use {
            shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }; shot.recycle()
    }
}
