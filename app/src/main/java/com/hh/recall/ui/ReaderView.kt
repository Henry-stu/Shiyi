package com.hh.recall.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView
import android.widget.TextView
import com.hh.recall.ScreenMode
import com.hh.recall.core.Mask
import com.hh.recall.core.MaskRules
import kotlin.math.abs

/** Native text layout keeps the same glyph positions whether an answer is hidden or visible. */
class ReaderView(context: Context) : ScrollView(context) {
    val textView = MaskTextView(context)
    var onReading: (Int) -> Unit = {}
    var initialOffset = 0
    private var bound = false
    private var lastFont = 0f
    init {
        isFillViewport = true
        clipToPadding = false
        addView(textView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setOnScrollChangeListener { _, _, _, _, _ ->
            textView.layout?.let { layout -> onReading(layout.getLineStart(layout.getLineForVertical((scrollY - textView.totalPaddingTop).coerceAtLeast(0)))) }
        }
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        if (textView.marking) false else super.onInterceptTouchEvent(event)

    fun bind(body: String, masks: List<Mask>, revealed: Set<String>, mode: ScreenMode, marking: Boolean,
             font: Float, foreground: Int, surface: Int, accent: Int,
             answerColor: Int = accent, markColor: Int = 0xFFBC4B48.toInt()) {
        val anchor = if (bound && lastFont != font) textView.layout?.let {
            it.getLineStart(it.getLineForVertical((scrollY - textView.totalPaddingTop).coerceAtLeast(0)))
        } else null
        setBackgroundColor(surface)
        textView.bind(body, masks, revealed, mode, marking, font, foreground, accent, answerColor, markColor)
        if (!bound || anchor != null) {
            val target = anchor ?: initialOffset
            post { textView.layout?.let { layout ->
                val line = layout.getLineForOffset(target.coerceIn(0, body.length))
                scrollTo(0, layout.getLineTop(line))
            } }
        }
        bound = true; lastFont = font
    }
}

class MaskTextView(context: Context) : TextView(context) {
    var onMark: (Int, Int) -> Unit = { _, _ -> }
    var onRemove: (Int) -> Unit = {}
    var onReveal: (String) -> Unit = {}
    var marking = false; private set
    private var mode = ScreenMode.READ
    private var body = ""
    private var masks: List<Mask> = emptyList()
    private var revealed: Set<String> = emptySet()
    private var foreground = 0
    private var accent = 0
    private var answer = 0
    private var marked = 0
    private var boundFont = 0f
    private var downX = 0f
    private var downY = 0f
    private var rawX = 0f
    private var rawY = 0f
    private var anchor = -1
    private var focusOffset = -1
    private var dragging = false
    private var clickOffset = -1
    private var multiTouchCancelled = false
    private var preview: Pair<Int, Int>? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionPath = Path()
    private val location = IntArray(2)
    private val tick = object : Runnable {
        override fun run() {
            if (!dragging || !marking) return
            val scroll = parent as? ScrollView ?: return
            scroll.getLocationOnScreen(location)
            val relative = rawY - location[1]
            val edge = 56 * resources.displayMetrics.density
            val speed = when {
                relative < edge -> -((edge - relative) / edge * 14 * resources.displayMetrics.density).toInt()
                relative > scroll.height - edge -> ((relative - scroll.height + edge) / edge * 14 * resources.displayMetrics.density).toInt()
                else -> 0
            }.coerceIn(-40, 40)
            if (speed != 0) {
                scroll.scrollBy(0, speed)
                getLocationOnScreen(location)
                updatePreview(offsetAt(rawX - location[0], rawY - location[1], false))
            }
            postDelayed(this, 16)
        }
    }
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            if (mode == ScreenMode.READ) {
                clickOffset = offsetAt(event.x, event.y, true)
                performClick()
            }
            return true
        }
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            if (marking && !dragging) {
                val at = offsetAt(event.x, event.y, true)
                if (at >= 0 && masks.none { at in it.start until it.end }) onMark(at, nextCharacter(at))
            }
            return true
        }
        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (marking) onRemove(offsetAt(event.x, event.y, true))
            else if (mode == ScreenMode.READ) {
                clickOffset = offsetAt(event.x, event.y, true)
                performClick()
            }
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    init {
        val pad = (28 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad * 2)
        setLineSpacing(9 * resources.displayMetrics.density, 1.22f)
        includeFontPadding = true
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun bind(value: String, ranges: List<Mask>, visible: Set<String>, screen: ScreenMode,
             mark: Boolean, font: Float, color: Int, lineColor: Int, answerColor: Int, markColor: Int) {
        val changed = body != value || masks != ranges || revealed != visible || mode != screen ||
            foreground != color || accent != lineColor || boundFont != font || answer != answerColor || marked != markColor
        body = value; masks = ranges; revealed = visible; mode = screen
        marking = screen == ScreenMode.MASK && mark
        foreground = color; accent = lineColor
        answer = answerColor; marked = markColor
        if (changed) { boundFont = font; textSize = font; applySpans() }
    }

    private fun applySpans() {
        val spans = SpannableString(body)
        setTextColor(foreground)
        masks.forEach { mask ->
            if (mask.start >= 0 && mask.end <= body.length) {
                val color = when {
                    mode == ScreenMode.MASK -> marked
                    mask.id in revealed -> answer
                    else -> android.graphics.Color.TRANSPARENT
                }
                spans.setSpan(ForegroundColorSpan(color), mask.start, mask.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        preview?.let { (start, end) ->
            if (start < end) spans.setSpan(BackgroundColorSpan(0x35BC4B48), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text = spans
        contentDescription = if (mode != ScreenMode.READ) body else buildString {
            var previous = 0
            masks.forEach { mask ->
                append(body.substring(previous, mask.start))
                append(if (mask.id in revealed) body.substring(mask.start, mask.end) else "〔挖空〕")
                previous = mask.end
            }
            append(body.substring(previous))
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mode != ScreenMode.READ) return
        val textLayout = layout ?: return
        canvas.save(); canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
        linePaint.color = accent
        val thickness = 1.4f * resources.displayMetrics.density
        masks.filterNot { it.id in revealed }.forEach { mask ->
            selectionPath.reset(); textLayout.getSelectionPath(mask.start, mask.end, selectionPath)
            val firstLine = textLayout.getLineForOffset(mask.start)
            val lastLine = textLayout.getLineForOffset((mask.end - 1).coerceAtLeast(mask.start))
            for (line in firstLine..lastLine) {
                val y = textLayout.getLineBaseline(line) + 2 * resources.displayMetrics.density
                canvas.save(); canvas.clipRect(0f, y, width.toFloat(), y + thickness)
                canvas.drawPath(selectionPath, linePaint); canvas.restore()
            }
        }
        canvas.restore()
    }

    private fun nextCharacter(at: Int): Int = MaskRules.boundary(body, (at + 1).coerceAtMost(body.length), true)
    override fun performClick(): Boolean {
        super.performClick()
        if (mode == ScreenMode.READ) masks.find { clickOffset in it.start until it.end }?.let { onReveal(it.id) }
        clickOffset = -1
        return true
    }
    private fun offsetAt(x: Float, y: Float, strict: Boolean): Int {
        val textLayout = layout ?: return -1
        if (body.isEmpty()) return -1
        val localY = y - totalPaddingTop
        if (strict && (localY < 0 || localY > textLayout.height)) return -1
        val line = textLayout.getLineForVertical(localY.toInt().coerceIn(0, textLayout.height))
        val localX = x - totalPaddingLeft
        if (strict && (localX < textLayout.getLineLeft(line) || localX > textLayout.getLineRight(line))) return -1
        var at = textLayout.getOffsetForHorizontal(line, localX)
        if (at > textLayout.getLineStart(line) && textLayout.getPrimaryHorizontal(at) > localX) at--
        at = at.coerceIn(0, body.length - 1)
        return MaskRules.boundary(body, at, false)
    }

    private fun updatePreview(offset: Int) {
        if (anchor < 0 || offset < 0) return
        focusOffset = offset
        preview = minOf(anchor, offset) to nextCharacter(maxOf(anchor, offset))
        applySpans()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode == ScreenMode.MASK && !marking) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) multiTouchCancelled = false
        if (event.pointerCount > 1 || multiTouchCancelled) {
            multiTouchCancelled = true
            cancelStroke()
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            gestures.onTouchEvent(cancel); cancel.recycle()
            return true
        }
        rawX = event.rawX; rawY = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; dragging = false
                anchor = offsetAt(event.x, event.y, true)
                if (marking) parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (marking && anchor >= 0) {
                if (!dragging && (abs(event.x - downX) > slop || abs(event.y - downY) > slop)) {
                    dragging = true; post(tick)
                }
                if (dragging) updatePreview(offsetAt(event.x, event.y, false))
            }
            MotionEvent.ACTION_UP -> if (dragging) {
                preview?.let { onMark(it.first, it.second) }
                cancelStroke()
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                gestures.onTouchEvent(cancel); cancel.recycle()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { cancelStroke(); gestures.onTouchEvent(event); return true }
        }
        gestures.onTouchEvent(event)
        return true
    }

    private fun cancelStroke() { removeCallbacks(tick); dragging = false; anchor = -1; preview = null; applySpans(); parent?.requestDisallowInterceptTouchEvent(false) }
    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        gestures.onTouchEvent(cancel); cancel.recycle()
        super.onDetachedFromWindow()
    }
}
