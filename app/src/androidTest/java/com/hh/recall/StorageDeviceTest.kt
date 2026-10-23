package com.hh.recall

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hh.recall.data.*
import com.hh.recall.core.MaskRules
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageDeviceTest {
    @Test fun androidGraphemeBoundariesIncludeJoinedEmoji() {
        val emoji = "👩‍👩‍👧‍👦"
        val mask = MaskRules.add("甲${emoji}乙", emptyList(), 2, 3).single()
        assertEquals(1, mask.start); assertEquals(1 + emoji.length, mask.end)
    }
    @Test fun roomTransactionsPreserveOfficialArticleWhenDraftIsDiscarded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, RecallDatabase::class.java).build()
        try {
            val dao = database.dao()
            dao.saveArticle(ArticleRow().apply { id = "test"; title = "标题"; body = "原正文" })
            dao.saveDraft(DraftRow().apply { articleId = "test"; json = "草稿" })
            dao.deleteDraft("test")
            assertEquals("原正文", dao.articles().single().body)
            assertTrue(dao.drafts().isEmpty())
            database.runInTransaction {
                dao.saveArticle(ArticleRow().apply { id = "test"; title = "标题"; body = "修改后正文" })
                dao.deleteDraft("test")
            }
            assertEquals("修改后正文", dao.articles().single().body)
        } finally { database.close() }
    }

    @Test fun deletingFolderUnfilesArticlesWithoutDeletingThem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, RecallDatabase::class.java).build()
        try {
            val dao = database.dao()
            dao.saveFolder(FolderRow().apply { id = "folder"; name = "测试文件夹" })
            dao.saveArticle(ArticleRow().apply { id = "article"; title = "标题"; body = "正文"; folderId = "folder" })
            database.runInTransaction { dao.unfileArticles("folder"); dao.deleteFolder("folder") }
            assertTrue(dao.folders().isEmpty()); assertEquals(1, dao.articles().size)
            assertNull(dao.articles().single().folderId)
        } finally { database.close() }
    }
}
