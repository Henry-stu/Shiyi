package com.hh.recall.ui

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText

class BodyEditor(context: Context) : EditText(context) {
    var onReplace: (Int, Int, String, Boolean) -> Unit = { _, _, _, _ -> }
    var onUndo: () -> Unit = {}
    var onRedo: () -> Unit = {}
    var suppress = false
    private var changeStart = 0
    private var changeBefore = 0
    private var inserted = ""
    init {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        gravity = Gravity.TOP or Gravity.START
        background = null
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setPadding(24.dp, 20.dp, 24.dp, 24.dp)
        setLineSpacing(8f * resources.displayMetrics.density, 1.16f)
        hint = "在这里输入文字，或长按粘贴学习材料……"
        setHorizontallyScrolling(false)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (suppress) return
                changeStart = start; changeBefore = before; inserted = s?.subSequence(start, start + count)?.toString() ?: ""
            }
            override fun afterTextChanged(s: Editable?) {
                if (!suppress && s != null) {
                    val composing = BaseInputConnection.getComposingSpanStart(s) >= 0
                    onReplace(changeStart, changeBefore, inserted, composing)
                }
            }
        })
    }
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
    override fun onTextContextMenuItem(id: Int): Boolean = when (id) {
        android.R.id.undo -> { onUndo(); true }
        android.R.id.redo -> { onRedo(); true }
        else -> super.onTextContextMenuItem(id)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_Z) {
            if (event.isShiftPressed) onRedo() else onUndo()
            return true
        }
        if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_Y) { onRedo(); return true }
        return super.onKeyDown(keyCode, event)
    }
    fun bind(value: String, font: Float, foreground: Int, hintColor: Int) {
        if (text.toString() != value) {
            val cursor = selectionStart.coerceAtLeast(0)
            suppress = true
            setText(value); setSelection(cursor.coerceAtMost(value.length))
            suppress = false
        }
        textSize = font; setTextColor(foreground); setHintTextColor(hintColor)
    }
}
