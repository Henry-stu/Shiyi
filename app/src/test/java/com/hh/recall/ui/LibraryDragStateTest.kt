package com.hh.recall.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.hh.recall.core.Article
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryDragStateTest {
    @Test fun overlappingTargetsPreferTheSmallestContainingFolder() {
        val state = LibraryDragState()
        state.start(Article(id = "article", title = "标题", body = "正文"), Offset(50f, 50f))
        state.folderBar = Rect(0f, 0f, 300f, 100f)
        state.targets["wide"] = Rect(0f, 0f, 200f, 100f)
        state.targets["specific"] = Rect(40f, 20f, 80f, 80f)

        assertEquals("specific", state.target)
    }
}
