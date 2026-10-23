package com.hh.recall.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.hh.recall.core.Article

/** Coordinates stay in the Compose root, independent of grid and folder scrolling. */
@Stable
internal class LibraryDragState {
    var article by mutableStateOf<Article?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var folderBar by mutableStateOf(Rect.Zero)
    val targets = mutableStateMapOf<String, Rect>()

    val target: String?
        get() = if (article == null || !folderBar.contains(pointer)) null else
            targets.entries.firstOrNull { (_, bounds) -> bounds.intersect(folderBar).contains(pointer) }?.key

    fun start(value: Article, position: Offset) { article = value; pointer = position }
    fun move(delta: Offset) { if (article != null) pointer += delta }
    fun cancel() { article = null }
}
