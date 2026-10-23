package com.hh.recall

import com.hh.recall.core.*
import com.hh.recall.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import org.json.JSONObject

class BackupCodecTest {
    private val source = Library(listOf(Article(id = "article", title = "标题", body = "甲乙😀\nEnglish", folderId = "folder", masks = listOf(Mask(1, 4, "mask")), updatedAt = 123, readingOffset = 2)), listOf(Folder("folder", "文件夹")))
    private fun bytes(value: Library): ByteArray = ByteArrayOutputStream().also { BackupCodec.write(value, it) }.toByteArray()
    @Test fun roundTripPreservesAllData() { assertEquals(source, BackupCodec.read(ByteArrayInputStream(bytes(source)))) }
    @Test fun restoreRemapsIdsWithoutOverwritingExistingArticles() {
        val imported = BackupCodec.asNewCollection(source, "恢复")
        assertNotEquals(source.articles[0].id, imported.articles[0].id)
        assertNotEquals(source.articles[0].masks[0].id, imported.articles[0].masks[0].id)
        assertTrue(imported.folders.any { it.id == imported.articles[0].folderId })
        assertEquals(source.articles[0].body, imported.articles[0].body)
    }
    @Test fun restoringAnAlreadyRestoredCollectionDoesNotGrowFolderNames() {
        val first = BackupCodec.asNewCollection(source, "恢复 2026-10-23")
        val second = BackupCodec.asNewCollection(first, "恢复 2026-10-24")
        assertEquals("文件夹", second.folders.last().name)
        assertEquals("恢复 2026-10-24", second.folders.first().name)
    }
    @Test fun invalidRangeIsRejectedBeforeImport() {
        val invalid = source.copy(articles = source.articles.map { it.copy(masks = listOf(Mask(2, 3))) })
        assertThrows(Exception::class.java) { BackupCodec.read(ByteArrayInputStream(bytes(invalid))) }
    }
    @Test fun unknownFolderAndDuplicateArticleAreRejected() {
        assertThrows(Exception::class.java) { BackupCodec.read(ByteArrayInputStream(bytes(source.copy(folders = emptyList())))) }
        assertThrows(Exception::class.java) { BackupCodec.read(ByteArrayInputStream(bytes(source.copy(articles = source.articles + source.articles)))) }
    }
    @Test fun corruptedAndUnexpectedZipEntriesAreRejected() {
        assertThrows(Exception::class.java) { BackupCodec.read(ByteArrayInputStream(byteArrayOf(1, 2, 3))) }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { it.putNextEntry(ZipEntry("../recall.json")); it.write("{}".toByteArray()); it.closeEntry() }
        assertThrows(Exception::class.java) { BackupCodec.read(ByteArrayInputStream(output.toByteArray())) }
    }
    @Test fun draftRoundTripIncludesOriginalAndEditedMasks() {
        val article = source.articles[0]
        val draft = Draft(article.id, "草稿", TextSnapshot(article.body, emptyList()), "BODY", article.body, article.masks, folderId = "folder")
        assertEquals(draft, JsonCodec.readDraft(JsonCodec.draft(draft)))
    }
    private fun editedFolder(key: String, value: Any?, remove: Boolean = false): ByteArray {
        val json = ZipInputStream(ByteArrayInputStream(bytes(source))).use { it.nextEntry; JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        val folder = json.getJSONArray("folders").getJSONObject(0)
        if (remove) { folder.remove("lastOpenedAt"); folder.remove("updatedAt") } else folder.put(key, value)
        return ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { it.putNextEntry(ZipEntry("recall.json")); it.write(json.toString().toByteArray()) } }.toByteArray()
    }
    @Test fun folderTimesRoundTripAndRestoreRetainsThem() {
        val timed = source.copy(folders = source.folders.map { it.copy(lastOpenedAt = 100, updatedAt = 200) })
        assertEquals(timed, BackupCodec.read(ByteArrayInputStream(bytes(timed))))
        assertEquals(100L, BackupCodec.asNewCollection(timed, "恢复").folders.last().lastOpenedAt)
        assertEquals(200L, BackupCodec.asNewCollection(timed, "恢复").folders.last().updatedAt)
    }
    @Test fun oldBackupWithoutFolderTimesDefaultsToZero() {
        assertEquals(source, BackupCodec.read(ByteArrayInputStream(editedFolder("", null, remove = true))))
    }
    @Test fun invalidFolderTimesAreRejected() {
        for (value in listOf(-1, 1.5, "123", JSONObject.NULL)) {
            assertThrows(Exception::class.java) { BackupCodec.read(ByteArrayInputStream(editedFolder("updatedAt", value))) }
        }
    }
}
