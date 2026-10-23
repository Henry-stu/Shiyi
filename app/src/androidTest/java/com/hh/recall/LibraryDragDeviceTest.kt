package com.hh.recall

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hh.recall.core.Article
import com.hh.recall.core.Folder
import com.hh.recall.core.Mask
import com.hh.recall.core.Draft
import com.hh.recall.core.TextSnapshot
import com.hh.recall.data.Repository
import com.hh.recall.ui.LibraryScreen
import com.hh.recall.ui.RecallApp
import com.hh.recall.ui.RecallTheme
import kotlinx.coroutines.runBlocking
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryDragDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: AppModel
    private val article = Article(id = "drag-source", title = "拖动测试材料", body = "保留原正文和挖空，长按卡片移动分类。")
    private val moves = mutableListOf<Pair<Article, String?>>()
    private var opens = 0

    private fun mount(source: Article = article, manyFolders: Boolean = false, manyArticles: Boolean = false) {
        compose.runOnUiThread { vm = AppModel(ApplicationProvider.getApplicationContext<Application>()) }
        compose.waitUntil(5000) { vm.loaded }
        val folders = if (manyFolders) (0..19).map { Folder("destination-$it", "分类文件夹$it") } else listOf(Folder("destination", "测试1"))
        val articles = listOf(source) + (1..if (manyArticles) 39 else 1).map { article.copy(id = "other-$it", title = "其他文章$it") }
        compose.setContent {
            RecallTheme(false) {
                Surface(Modifier.fillMaxSize()) {
                    LibraryScreen(vm, articles, folders, emptySet(), rememberLazyGridState(),
                        onOpen = { opens++ }, onRename = {}, onMove = {}, onDelete = {}, onFolder = {}, onFolderDelete = {},
                        onDrop = { value, folder -> moves += value to folder })
                }
            }
        }
    }

    private fun start() {
        val source = compose.onNodeWithTag("article-drag-source").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("library").performTouchInput {
            down(Offset(source.left + 48, source.top + 65))
            advanceEventTime(700)
            moveBy(Offset(0f, -12f))
        }
        compose.onNodeWithTag("dragPreview").assertExists()
    }

    private fun moveToTarget(id: String) {
        val target = compose.onNodeWithTag("folder-$id").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("library").performTouchInput { moveTo(target, 120) }
    }

    private fun release() = compose.onNodeWithTag("library").performTouchInput { up() }

    @Test fun longPressDropsIntoEmptyFolderOnlyOnRelease() {
        mount(); start(); moveToTarget("destination")
        compose.onNodeWithTag("folder-destination").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "松手移入"))
        compose.runOnIdle { assertTrue(moves.isEmpty()); assertEquals(0, opens) }
        release()
        compose.onNodeWithTag("dragPreview").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(article to "destination"), moves); assertEquals(0, opens); assertNull(vm.folderFilter) }
    }

    @Test fun allAndSameFolderAndBlankAreNotMoves() {
        mount(article.copy(folderId = "destination"))
        start(); moveToTarget("destination"); release()
        start(); moveToTarget("all"); release()
        start()
        compose.onNodeWithTag("library").performTouchInput { moveTo(Offset(10f, 10f), 120); up() }
        compose.runOnIdle { assertTrue(moves.isEmpty()); assertEquals(0, opens) }
        compose.onNodeWithTag("dragPreview").assertDoesNotExist()
    }

    @Test fun categorizedArticleCanBeUnfiledAndCancelledDragDoesNothing() {
        mount(article.copy(folderId = "destination"))
        start(); moveToTarget("unfiled")
        compose.onNodeWithTag("library").performTouchInput { cancel() }
        compose.onNodeWithTag("dragPreview").assertDoesNotExist()
        compose.runOnIdle { assertTrue(moves.isEmpty()) }
        start(); moveToTarget("unfiled"); release()
        compose.runOnIdle { assertEquals(listOf(article.copy(folderId = "destination") to null), moves) }
    }

    @Test fun ordinaryTapAndScrollStillWorkWithoutStartingDrag() {
        mount(manyArticles = true)
        compose.onNodeWithTag("article-drag-source").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
        compose.onNodeWithTag("articleGrid").performTouchInput { swipeUp() }
        compose.onNodeWithTag("dragPreview").assertDoesNotExist()
        compose.runOnIdle { assertTrue(moves.isEmpty()) }
    }

    @Test fun folderBarAutoScrollExposesOffscreenTargetWhileHoldingArticle() {
        mount(manyFolders = true); start()
        val bar = compose.onNodeWithTag("folderBar").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("library").performTouchInput { moveTo(Offset(bar.right - 3, bar.center.y), 120) }
        compose.mainClock.advanceTimeBy(12000)
        compose.waitForIdle()
        compose.onNodeWithTag("folder-destination-19").assertIsDisplayed()
        moveToTarget("destination-19"); release()
        compose.runOnIdle { assertEquals("destination-19", moves.single().second); assertEquals(0, opens) }
    }

    @Test fun realDropPersistsFolderAndPreservesMasksReadingPositionAndDraft() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        compose.runOnUiThread { vm = AppModel(context) }
        compose.waitUntil(5000) { vm.loaded }
        val originalArticles = vm.repository.library.value.articles
        val originalDrafts = vm.repository.drafts.value
        val folder = Folder("qa-drag-${UUID.randomUUID()}", "拖动验收临时目录")
        val source = article.copy(id = "qa-drag-${UUID.randomUUID()}", masks = listOf(Mask(0, 2)), readingOffset = 5)
        val draft = Draft(source.id, "未保存标题", TextSnapshot(source.body + "草稿", source.masks), "BODY", source.body, source.masks)
        try {
            runBlocking {
                vm.repository.saveFolder(folder)
                vm.repository.save(source)
                vm.repository.draft(draft)
            }
            compose.runOnUiThread { vm.pendingRecovery = null }
            compose.setContent { RecallApp(vm) }
            compose.onNodeWithTag("folder-${folder.id}").performScrollTo()
            val card = compose.onNodeWithTag("article-${source.id}").fetchSemanticsNode().boundsInRoot
            val root = compose.onNodeWithTag("library").fetchSemanticsNode().boundsInRoot
            val target = compose.onNodeWithTag("folder-${folder.id}").fetchSemanticsNode().boundsInRoot.center - root.topLeft
            compose.onNodeWithTag("library").performTouchInput {
                down(Offset(card.left + 48, card.top + 65) - root.topLeft)
                advanceEventTime(700)
                moveTo(target, 240)
                up()
            }
            compose.waitUntil(5000) { vm.repository.library.value.articles.find { it.id == source.id }?.folderId == folder.id }
            runBlocking {
                // A fresh repository reads the persisted database, rather than the UI's state flow.
                val reopened = Repository(context)
                reopened.load()
                val saved = reopened.library.value.articles.single { it.id == source.id }
                assertEquals(source.copy(folderId = folder.id, updatedAt = saved.updatedAt), saved)
                assertEquals(draft, reopened.drafts.value.single { it.articleId == source.id })
                assertEquals(originalArticles.toSet(), reopened.library.value.articles.filter { it.id != source.id }.toSet())
                assertEquals(originalDrafts.toSet(), reopened.drafts.value.filter { it.articleId != source.id }.toSet())
            }
        } finally {
            runBlocking { vm.repository.deleteArticle(source.id); vm.repository.deleteFolder(folder.id) }
        }
    }
}
