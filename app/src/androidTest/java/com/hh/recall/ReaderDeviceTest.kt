package com.hh.recall

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hh.recall.core.Mask
import com.hh.recall.ui.ReaderView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var reader: ReaderView
    private var maskCallback: Pair<Int, Int>? = null
    private var removed = -1
    private var revealed: String? = null
    private val body = "手动挖空保持文字排版。\n第二行可以跨行选择，English 😀。"

    private fun mount(mode: ScreenMode, visible: State<Set<String>> = mutableStateOf(emptySet()), content: String = body) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = { context -> ReaderView(context).also {
                        reader = it
                        it.textView.onMark = { start, end -> maskCallback = start to end }
                        it.textView.onRemove = { removed = it }
                        it.textView.onReveal = { revealed = it }
                    } }, update = { it.bind(content, listOf(Mask(0, 4, "a")), visible.value, mode, true, 24f, 0xFF222222.toInt(), 0xFFFFFFFF.toInt(), 0xFF246B5B.toInt()) }, modifier = Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()
    }

    private fun point(offset: Int): Pair<Float, Float> {
        val text = reader.textView
        val layout = text.layout
        val line = layout.getLineForOffset(offset)
        val next = (offset + 1).coerceAtMost(body.length)
        val x = (layout.getPrimaryHorizontal(offset) + layout.getPrimaryHorizontal(next)) / 2 + text.totalPaddingLeft
        val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f + text.totalPaddingTop
        return x to y
    }

    private fun dispatch(action: Int, point: Pair<Float, Float>, start: Long, at: Long) {
        val location = IntArray(2); reader.textView.getLocationOnScreen(location)
        val event = MotionEvent.obtain(start, at, action, point.first + location[0], point.second + location[1], 0)
        event.offsetLocation(-location[0].toFloat(), -location[1].toFloat())
        reader.textView.dispatchTouchEvent(event); event.recycle()
    }

    private fun tap(offset: Int) {
        compose.runOnIdle { val time = SystemClock.uptimeMillis(); val point = point(offset)
            dispatch(MotionEvent.ACTION_DOWN, point, time, time)
            dispatch(MotionEvent.ACTION_UP, point, time, time + 30)
        }
    }

    @Test fun revealingDoesNotChangeGlyphPositionsOrLineBreaks() {
        val visible = mutableStateOf<Set<String>>(emptySet()); mount(ScreenMode.READ, visible)
        var before = emptyList<Float>()
        compose.runOnIdle { before = (0..body.length).map { reader.textView.layout.getPrimaryHorizontal(it) }; visible.value = setOf("a") }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(before, (0..body.length).map { reader.textView.layout.getPrimaryHorizontal(it) }) }
    }

    @Test fun tappingHiddenMaskRoutesCorrectId() {
        mount(ScreenMode.READ); tap(1)
        compose.runOnIdle { assertEquals("a", revealed) }
    }

    @Test fun directDragSelectsAcrossParagraphWithoutLongPress() {
        mount(ScreenMode.MASK)
        compose.runOnIdle {
            val time = SystemClock.uptimeMillis(); val start = point(4); val end = point(20)
            dispatch(MotionEvent.ACTION_DOWN, start, time, time)
            dispatch(MotionEvent.ACTION_MOVE, end, time, time + 50)
            dispatch(MotionEvent.ACTION_UP, end, time, time + 80)
            assertNotNull(maskCallback); assertEquals(4, maskCallback!!.first); assertTrue(maskCallback!!.second >= 20)
        }
    }

    @Test fun doubleTapExistingMaskRemovesIt() {
        mount(ScreenMode.MASK)
        compose.runOnIdle {
            val time = SystemClock.uptimeMillis(); val p = point(1)
            dispatch(MotionEvent.ACTION_DOWN, p, time, time)
            dispatch(MotionEvent.ACTION_UP, p, time, time + 25)
            dispatch(MotionEvent.ACTION_DOWN, p, time + 100, time + 100)
            dispatch(MotionEvent.ACTION_UP, p, time + 100, time + 125)
            assertTrue(removed in 0..3)
        }
    }

    @Test fun draggingToBottomEdgeAutomaticallyScrollsLongText() {
        mount(ScreenMode.MASK, content = (body + "\n\n").repeat(100))
        val time = SystemClock.uptimeMillis()
        compose.runOnIdle {
            dispatch(MotionEvent.ACTION_DOWN, point(5), time, time)
            dispatch(MotionEvent.ACTION_MOVE, 140f to (reader.height - 15f), time, time + 50)
        }
        Thread.sleep(300)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue("edge drag should scroll", reader.scrollY > 0)
            dispatch(MotionEvent.ACTION_UP, 140f to (reader.height - 15f + reader.scrollY), time, SystemClock.uptimeMillis())
            assertNotNull(maskCallback)
        }
        compose.waitForIdle()
    }
}
