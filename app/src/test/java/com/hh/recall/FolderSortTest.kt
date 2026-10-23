package com.hh.recall

import com.hh.recall.core.*
import org.junit.Assert.*
import org.junit.Test

class FolderSortTest {
    private val folders = listOf(Folder("z", "中文", 10, 20), Folder("b", "背诵", 30, 0), Folder("a", "安卓", 0, 40))
    @Test fun chineseNamesUsePinyinOrder() { assertEquals(listOf("a", "b", "z"), sortedFolders(folders, FolderSort.NAME).map { it.id }) }
    @Test fun openedAndModifiedSortNewestFirstWithUnknownLast() {
        assertEquals(listOf("b", "z", "a"), sortedFolders(folders, FolderSort.OPENED).map { it.id })
        assertEquals(listOf("a", "z", "b"), sortedFolders(folders, FolderSort.MODIFIED).map { it.id })
    }
    @Test fun equalTimestampsFallBackToNamesAndStableIds() {
        assertEquals(listOf("a", "b", "z"), sortedFolders(folders.map { it.copy(lastOpenedAt = 0) }, FolderSort.OPENED).map { it.id })
        assertEquals(listOf("a", "z"), sortedFolders(listOf(Folder("z", "same"), Folder("a", "same")), FolderSort.NAME).map { it.id })
    }
}
