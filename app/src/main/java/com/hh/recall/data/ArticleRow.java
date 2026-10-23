package com.hh.recall.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "articles")
public class ArticleRow {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String title = "";
    @NonNull public String body = "";
    public String folderId;
    @NonNull public String masksJson = "[]";
    public long updatedAt;
    public int readingOffset;
}
