package com.hh.recall

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hh.recall.ui.BodyEditor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorDeviceTest {
    @Test fun nativeEditableReportsActualRangeForRepeatedSentences() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val editor = BodyEditor(instrumentation.targetContext)
            editor.bind("学习学习学习", 22f, 0xFF222222.toInt(), 0xFF888888.toInt())
            var replacement = ""
            editor.onReplace = { start, before, inserted, _ -> replacement = "$start:$before:$inserted" }
            editor.text.replace(2, 4, "复习")
            assertEquals("2:2:复习", replacement)
        }
    }
    @Test fun nativeContextUndoUsesSharedTextAndMaskHistory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val editor = BodyEditor(instrumentation.targetContext)
            var undo = false; var redo = false
            editor.onUndo = { undo = true }; editor.onRedo = { redo = true }
            editor.onTextContextMenuItem(android.R.id.undo)
            editor.onTextContextMenuItem(android.R.id.redo)
            assertTrue(undo); assertTrue(redo)
        }
    }
}
