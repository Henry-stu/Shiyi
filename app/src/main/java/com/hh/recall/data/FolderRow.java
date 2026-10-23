package com.hh.recall.data;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
@Entity(tableName = "folders")
public class FolderRow {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String name = "";
    @androidx.room.ColumnInfo(defaultValue = "0") public long lastOpenedAt;
    @androidx.room.ColumnInfo(defaultValue = "0") public long updatedAt;
}
