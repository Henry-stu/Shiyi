package com.hh.recall.data;
import androidx.room.Database;
import androidx.room.RoomDatabase;
@Database(entities = {ArticleRow.class, FolderRow.class, DraftRow.class}, version = 2, exportSchema = true)
public abstract class RecallDatabase extends RoomDatabase {
    public static final androidx.room.migration.Migration MIGRATION_1_2 = new androidx.room.migration.Migration(1, 2) {
        @Override public void migrate(androidx.sqlite.db.SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE folders ADD COLUMN lastOpenedAt INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE folders ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0");
        }
    };
    public abstract RecallDao dao();
}
