package com.hh.recall

import com.hh.recall.core.*
import org.junit.Assert.*
import org.junit.Test

class MaskRulesTest {
    private val source = TextSnapshot("甲乙丙丁戊己庚辛壬癸", listOf(Mask(2, 4, "a"), Mask(6, 8, "b")))
    @Test fun insertionBeforeShiftsBothMasks() {
        val next = MaskRules.replace(source, 1, 1, "新增")
        assertEquals(listOf(Mask(4, 6, "a"), Mask(8, 10, "b")), next.masks)
        assertEquals("丙丁", next.text.substring(next.masks[0].start, next.masks[0].end))
    }
    @Test fun insertionAtStartIsOutside() {
        assertEquals(listOf(Mask(3, 5, "a"), Mask(7, 9, "b")), MaskRules.replace(source, 2, 2, "X").masks)
    }
    @Test fun insertionAtEndIsOutside() {
        assertEquals(listOf(Mask(2, 4, "a"), Mask(7, 9, "b")), MaskRules.replace(source, 4, 4, "X").masks)
    }
    @Test fun insertionInsideRemovesOnlyTouchedMask() {
        assertEquals(listOf(Mask(7, 9, "b")), MaskRules.replace(source, 3, 3, "X").masks)
    }
    @Test fun deletionBeforeShiftsWithoutChangingAnswer() {
        val next = MaskRules.replace(source, 0, 2, "")
        assertEquals(listOf(Mask(0, 2, "a"), Mask(4, 6, "b")), next.masks)
    }
    @Test fun replaceAcrossMultipleMasksCancelsBoth() {
        assertTrue(MaskRules.replace(source, 3, 7, "X").masks.isEmpty())
    }
    @Test fun deletionTouchingButNotIntersectingMaskKeepsIt() {
        assertEquals(listOf(Mask(2, 4, "a"), Mask(4, 6, "b")), MaskRules.replace(source, 4, 6, "").masks)
    }
    @Test fun noChangeKeepsOriginalIdentity() { assertSame(source, MaskRules.replace(source, 2, 4, "丙丁")) }
    @Test fun repeatedSentencesUseActualEditingRange() {
        val original = TextSnapshot("学习学习学习", listOf(Mask(2, 4, "middle")))
        val next = MaskRules.replace(original, 0, 2, "")
        assertEquals(listOf(Mask(0, 2, "middle")), next.masks)
    }
    @Test fun addMergesAdjacentOverlappingAndBridgeRanges() {
        val ranges = listOf(Mask(0, 2, "a"), Mask(2, 5, "b"), Mask(7, 9, "c"))
        val merged = MaskRules.add(source.text, ranges, 4, 8)
        assertEquals(1, merged.size); assertEquals(0, merged[0].start); assertEquals(9, merged[0].end)
    }
    @Test fun backwardsAndMultilineSelectionWork() {
        val result = MaskRules.add("一二\n三四", emptyList(), 5, 1)
        assertEquals(1, result[0].start); assertEquals(5, result[0].end)
    }
    @Test fun whitespaceOnlyIsNotAnAnswer() { assertTrue(MaskRules.add(" \n ", emptyList(), 0, 3).isEmpty()) }
    @Test fun emojiSurrogatePairIsNeverSplit() {
        val result = MaskRules.add("甲😀乙", emptyList(), 2, 3).single()
        assertEquals(1, result.start); assertEquals(3, result.end)
        assertTrue(MaskRules.valid("甲😀乙", listOf(result)))
        assertFalse(MaskRules.valid("甲😀乙", listOf(Mask(2, 3))))
    }
    @Test fun combiningAccentStaysWithItsLetter() {
        val result = MaskRules.add("e\u0301x", emptyList(), 1, 2).single()
        assertEquals(0, result.start); assertEquals(2, result.end)
    }
    @Test fun joinedEmojiSkinTonesAndFlagsStayWhole() {
        for (emoji in listOf("👩‍👩‍👧‍👦", "👍🏽", "🇨🇳")) {
            val text = "甲${emoji}乙"
            val result = MaskRules.add(text, emptyList(), 2, 3).single()
            assertEquals(1, result.start); assertEquals(1 + emoji.length, result.end)
        }
    }
    @Test fun historyRestoresBothTextAndMasks() {
        val history = EditHistory(source)
        val edited = MaskRules.replace(source, 3, 3, "X")
        history.push(edited); assertEquals(source, history.undo()); assertEquals(edited, history.redo())
        history.undo(); history.push(MaskRules.replace(source, 0, 0, "Y")); assertFalse(history.canRedo)
    }
    @Test fun invalidOverlapsAndDuplicateIdsAreRejected() {
        assertFalse(MaskRules.valid(source.text, listOf(Mask(1, 4, "a"), Mask(3, 5, "b"))))
        assertFalse(MaskRules.valid(source.text, listOf(Mask(1, 2, "a"), Mask(3, 4, "a"))))
        assertFalse(MaskRules.valid(source.text, listOf(Mask(1, 11))))
    }
}
