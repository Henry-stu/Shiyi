package com.hh.recall

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hh.recall.core.*
import com.hh.recall.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FolderStorageDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun migrationFromVersionOnePreservesEveryExistingTable() {
        val name = "migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE articles (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL, folderId TEXT, masksJson TEXT NOT NULL, updatedAt INTEGER NOT NULL, readingOffset INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE folders (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
                db.execSQL("CREATE TABLE drafts (articleId TEXT NOT NULL PRIMARY KEY, json TEXT NOT NULL)")
                db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                db.execSQL("INSERT INTO room_master_table VALUES (42, '6b9c0daec98ea9974c7969c0810edd31')")
                db.execSQL("INSERT INTO folders VALUES ('f', '旧目录')")
                db.execSQL("INSERT INTO articles VALUES ('a', '旧标题', '甲乙正文', 'f', '[{\"start\":0,\"end\":1,\"id\":\"mask\"}]', 123, 2)")
                db.execSQL("INSERT INTO drafts VALUES ('a', '原有草稿')")
                db.version = 1
            }
            val db = Room.databaseBuilder(context, RecallDatabase::class.java, name).addMigrations(RecallDatabase.MIGRATION_1_2).build()
            try {
                val article = db.dao().articles().single()
                assertEquals("旧标题", article.title); assertEquals("甲乙正文", article.body)
                assertEquals("f", article.folderId); assertEquals(2, article.readingOffset)
                assertTrue(article.masksJson.contains("mask")); assertEquals(123L, article.updatedAt)
                assertEquals("原有草稿", db.dao().drafts().single().json)
                assertEquals(0L, db.dao().folders().single().lastOpenedAt)
                assertEquals(0L, db.dao().folders().single().updatedAt)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun folderTimesTrackActualContentChangesButIgnoreReadingAndDrafts() = runBlocking {
        val name = "folder-times-${UUID.randomUUID()}.db"
        val repo = Repository(context, name)
        val a = Folder("a", "甲目录", updatedAt = 10)
        val b = Folder("b", "乙目录", updatedAt = 20)
        val article = Article(id = "article", title = "标题", body = "甲乙正文", folderId = "a")
        fun folder(id: String) = repo.library.value.folders.single { it.id == id }
        try {
            repo.importCollection(Library(listOf(article), listOf(a, b)))
            repo.openFolder("a")
            assertTrue(folder("a").lastOpenedAt > 0); assertEquals(10L, folder("a").updatedAt)
            repo.position(article.id, 2)
            repo.draft(Draft(article.id, "草稿", TextSnapshot("编辑草稿", emptyList()), "BODY", article.body, emptyList()))
            repo.save(article.copy(readingOffset = 2, updatedAt = 99), clearDraft = false)
            assertEquals(10L, folder("a").updatedAt)
            repo.save(article.copy(body = "甲乙改正文"), clearDraft = false)
            assertTrue(folder("a").updatedAt > 10)
            repo.importCollection(Library(listOf(article), listOf(a, b)))
            repo.save(article.copy(folderId = "b"), clearDraft = false)
            assertTrue(folder("a").updatedAt > 10); assertEquals(folder("a").updatedAt, folder("b").updatedAt)
            assertEquals(1, repo.drafts.value.size)
            repo.importCollection(Library(listOf(article.copy(folderId = "b")), listOf(b)))
            repo.deleteArticle(article.id)
            assertTrue(folder("b").updatedAt > 20)
            repo.saveFolder(a.copy(name = "重命名")); assertTrue(folder("a").updatedAt > 10)
            val previous = folder("a")
            repo.saveFolder(previous); assertEquals(previous, folder("a"))
        } finally { repo.close(); context.deleteDatabase(name) }
    }
}
