package com.hh.recall.data

import android.content.Context
import androidx.room.Room
import com.hh.recall.core.*
import com.hh.recall.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

class Repository(context: Context, databaseName: String = "recall.db") : java.io.Closeable {
    private val database = Room.databaseBuilder(context, RecallDatabase::class.java, databaseName).addMigrations(RecallDatabase.MIGRATION_1_2).build()
    override fun close() = database.close()
    private val dao = database.dao()
    private val gate = Mutex()
    val library = MutableStateFlow(Library())
    val drafts = MutableStateFlow<List<Draft>>(emptyList())

    private suspend fun <T> serial(action: () -> T): T = gate.withLock { withContext(Dispatchers.IO) { action() } }
    private fun reload() {
        library.value = Library(dao.articles().map(::fromRow), dao.folders().map { Folder(it.id, it.name, it.lastOpenedAt, it.updatedAt) })
        drafts.value = dao.drafts().map { JsonCodec.readDraft(it.json) }
    }
    suspend fun load() = serial { reload() }
    suspend fun save(article: Article, clearDraft: Boolean = true) = serial {
        require(article.body.isNotBlank() && article.title.isNotBlank())
        require(MaskRules.valid(article.body, article.masks))
        database.runInTransaction {
            val old = dao.article(article.id)?.let(::fromRow)
            val changed = old == null || old.title != article.title || old.body != article.body || old.masks != article.masks || old.folderId != article.folderId
            require(article.folderId == null || dao.folder(article.folderId) != null) { "文件夹已不存在" }
            dao.saveArticle(toRow(article))
            if (changed) {
                val now = System.currentTimeMillis()
                setOfNotNull(old?.folderId, article.folderId).forEach { dao.touchFolder(it, now) }
            }
            if (clearDraft) dao.deleteDraft(article.id)
        }
        reload()
        AppLog.event("repository_save", "masks=${article.masks.size} clearDraft=$clearDraft")
    }
    suspend fun position(id: String, offset: Int) = serial {
        dao.savePosition(id, offset)
        library.value = library.value.copy(articles = library.value.articles.map { if (it.id == id) it.copy(readingOffset = offset) else it })
    }
    suspend fun draft(draft: Draft) = serial {
        dao.saveDraft(DraftRow().apply { articleId = draft.articleId; json = JsonCodec.draft(draft) })
        drafts.value = dao.drafts().map { JsonCodec.readDraft(it.json) }
        AppLog.event("repository_draft", "mode=${draft.mode}")
    }
    suspend fun discardDraft(id: String) = serial { dao.deleteDraft(id); reload() }
    suspend fun deleteArticle(id: String) = serial {
        database.runInTransaction {
            dao.article(id)?.folderId?.let { dao.touchFolder(it, System.currentTimeMillis()) }
            dao.deleteArticle(id); dao.deleteDraft(id)
        }; reload()
    }
    suspend fun saveFolder(folder: Folder) = serial {
        database.runInTransaction {
            val old = dao.folder(folder.id)
            dao.saveFolder(toRow(folder.copy(lastOpenedAt = old?.lastOpenedAt ?: folder.lastOpenedAt,
                updatedAt = if (old == null || old.name != folder.name) System.currentTimeMillis() else old.updatedAt)))
        }; reload()
    }
    suspend fun openFolder(id: String) = serial { dao.openFolder(id, System.currentTimeMillis()); reload() }
    suspend fun deleteFolder(id: String) = serial {
        database.runInTransaction { dao.unfileArticles(id); dao.deleteFolder(id) }; reload()
    }
    suspend fun importCollection(value: Library) = serial {
        database.runInTransaction {
            value.folders.forEach { folder -> dao.saveFolder(toRow(folder)) }
            value.articles.forEach { dao.saveArticle(toRow(it)) }
        }; reload()
        AppLog.event("repository_import", "articles=${value.articles.size} folders=${value.folders.size}")
    }
    suspend fun snapshot(): Library = serial { reload(); library.value }

    private fun toRow(folder: Folder) = FolderRow().apply {
        id = folder.id; name = folder.name; lastOpenedAt = folder.lastOpenedAt; updatedAt = folder.updatedAt
    }

    private fun fromRow(row: ArticleRow) = Article(row.id, row.title, row.body, row.folderId,
        JsonCodec.readMasks(JSONArray(row.masksJson)), row.updatedAt, row.readingOffset)
    private fun toRow(article: Article) = ArticleRow().apply {
        id = article.id; title = article.title; body = article.body; folderId = article.folderId
        masksJson = JsonCodec.masks(article.masks).toString(); updatedAt = article.updatedAt; readingOffset = article.readingOffset
    }
}
