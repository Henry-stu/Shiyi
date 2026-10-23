package com.hh.recall.data;
import androidx.room.*;
import java.util.List;
@Dao
public interface RecallDao {
    @Query("SELECT * FROM articles ORDER BY updatedAt DESC") List<ArticleRow> articles();
    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE") List<FolderRow> folders();
    @Query("SELECT * FROM drafts") List<DraftRow> drafts();
    @Query("SELECT * FROM articles WHERE id = :id") ArticleRow article(String id);
    @Query("SELECT * FROM folders WHERE id = :id") FolderRow folder(String id);
    @Query("UPDATE folders SET updatedAt = :time WHERE id = :id") void touchFolder(String id, long time);
    @Query("UPDATE folders SET lastOpenedAt = :time WHERE id = :id") void openFolder(String id, long time);
    @Upsert void saveArticle(ArticleRow row);
    @Upsert void saveFolder(FolderRow row);
    @Upsert void saveDraft(DraftRow row);
    @Query("DELETE FROM drafts WHERE articleId = :id") void deleteDraft(String id);
    @Query("DELETE FROM articles WHERE id = :id") void deleteArticle(String id);
    @Query("DELETE FROM folders WHERE id = :id") void deleteFolder(String id);
    @Query("UPDATE articles SET folderId = NULL WHERE folderId = :id") void unfileArticles(String id);
    @Query("UPDATE articles SET readingOffset = :offset WHERE id = :id") void savePosition(String id, int offset);
}
