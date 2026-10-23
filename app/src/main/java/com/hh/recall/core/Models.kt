package com.hh.recall.core

import java.util.UUID
import java.util.regex.Pattern

data class Mask(val start: Int, val end: Int, val id: String = UUID.randomUUID().toString())
data class Article(
    val id: String = UUID.randomUUID().toString(), val title: String, val body: String,
    val folderId: String? = null, val masks: List<Mask> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(), val readingOffset: Int = 0,
)
data class Folder(val id: String = UUID.randomUUID().toString(), val name: String,
    val lastOpenedAt: Long = 0, val updatedAt: Long = 0)

enum class FolderSort { NAME, OPENED, MODIFIED }

fun sortedFolders(folders: List<Folder>, sort: FolderSort): List<Folder> {
    val collator = java.text.Collator.getInstance(java.util.Locale.CHINA).apply { strength = java.text.Collator.PRIMARY }
    val names = Comparator<Folder> { a, b -> collator.compare(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id) }
    return folders.sortedWith(when (sort) {
        FolderSort.NAME -> names
        FolderSort.OPENED -> compareByDescending<Folder> { it.lastOpenedAt }.then(names)
        FolderSort.MODIFIED -> compareByDescending<Folder> { it.updatedAt }.then(names)
    })
}
data class TextSnapshot(val text: String, val masks: List<Mask>)
data class Draft(
    val articleId: String, val title: String, val snapshot: TextSnapshot,
    val mode: String, val originalBody: String, val originalMasks: List<Mask>,
    val folderId: String? = null,
)

object MaskRules {
    private data class Boundaries(val text: String, val offsets: IntArray)
    @Volatile private var cached: Boundaries? = null
    private val grapheme = Pattern.compile("\\X")

    private fun boundaries(text: String): IntArray {
        cached?.takeIf { it.text == text }?.let { return it.offsets }
        val offsets = ArrayList<Int>(text.length + 1)
        offsets += 0
        val matcher = grapheme.matcher(text)
        while (matcher.find()) offsets += matcher.end()
        val result = offsets.toIntArray()
        cached = Boundaries(text, result)
        return result
    }

    fun boundary(text: String, offset: Int, forward: Boolean): Int {
        val at = offset.coerceIn(0, text.length)
        val offsets = boundaries(text)
        val index = offsets.binarySearch(at)
        return if (index >= 0) at else offsets[if (forward) -index - 1 else -index - 2]
    }

    fun add(text: String, masks: List<Mask>, from: Int, until: Int): List<Mask> {
        val start = boundary(text, minOf(from, until), false)
        val end = boundary(text, maxOf(from, until), true)
        if (start >= end || text.substring(start, end).isBlank()) return masks
        val result = mutableListOf<Mask>()
        for (mask in (masks + Mask(start, end)).sortedBy { it.start }) {
            val last = result.lastOrNull()
            if (last != null && mask.start <= last.end) {
                result[result.lastIndex] = last.copy(end = maxOf(last.end, mask.end))
            } else result += mask
        }
        return result
    }

    /** Ranges are UTF-16 offsets, matching Android Editable and Compose TextLayoutResult. */
    fun replace(before: TextSnapshot, start: Int, end: Int, replacement: String): TextSnapshot {
        require(start in 0..end && end <= before.text.length)
        val text = before.text.replaceRange(start, end, replacement)
        if (text == before.text) return before
        val delta = replacement.length - (end - start)
        val masks = before.masks.mapNotNull { mask ->
            when {
                // Insertion at the start belongs outside; insertion at the end also stays outside.
                start == end && start <= mask.start -> mask.copy(start = mask.start + delta, end = mask.end + delta)
                start == end && start >= mask.end -> mask
                start == end -> null
                end <= mask.start -> mask.copy(start = mask.start + delta, end = mask.end + delta)
                start >= mask.end -> mask
                else -> null
            }
        }.filter { isBoundary(text, it.start) && isBoundary(text, it.end) }
        return TextSnapshot(text, masks)
    }

    fun isBoundary(text: String, offset: Int): Boolean =
        offset in 0..text.length && boundary(text, offset, false) == offset

    fun valid(text: String, masks: List<Mask>): Boolean {
        var end = 0
        val ids = mutableSetOf<String>()
        for (mask in masks.sortedBy { it.start }) {
            if (mask.start < end || mask.start >= mask.end || !isBoundary(text, mask.start) ||
                !isBoundary(text, mask.end) || mask.id.isBlank() || !ids.add(mask.id)) return false
            end = mask.end
        }
        return true
    }
}

class EditHistory(initial: TextSnapshot) {
    var current = initial
        private set
    private val past = ArrayDeque<TextSnapshot>()
    private val future = ArrayDeque<TextSnapshot>()
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    fun push(next: TextSnapshot) {
        if (next == current) return
        past.addLast(current)
        if (past.size > 150) past.removeFirst()
        current = next
        future.clear()
    }
    fun undo(): TextSnapshot {
        if (past.isNotEmpty()) { future.addLast(current); current = past.removeLast() }
        return current
    }
    fun redo(): TextSnapshot {
        if (future.isNotEmpty()) { past.addLast(current); current = future.removeLast() }
        return current
    }
}
