package com.hh.recall.data;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
@Entity(tableName = "drafts")
public class DraftRow {
    @PrimaryKey @NonNull public String articleId = "";
    @NonNull public String json = "";
}
