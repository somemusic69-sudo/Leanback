package com.leanback.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

data class TextDiff(val backspaces: Int, val appendText: String)

object TextDiffCalculator {
    /**
     * Compute minimal backspaces + appended text to transform [oldText] into [newText],
     * respecting UTF-16 surrogate pair boundaries so emojis are never split.
     */
    fun computeDiff(oldText: String, newText: String): TextDiff {
        if (oldText == newText) return TextDiff(0, "")

        val minLen = minOf(oldText.length, newText.length)
        var lcp = 0
        while (lcp < minLen && oldText[lcp] == newText[lcp]) {
            lcp++
        }
        // Step back if mismatch happened between high and low UTF-16 surrogates.
        if (lcp > 0 && lcp < minLen &&
            Character.isLowSurrogate(oldText[lcp]) &&
            Character.isHighSurrogate(oldText[lcp - 1])
        ) {
            lcp--
        }

        val backspaces = if (lcp < oldText.length) {
            Character.codePointCount(oldText, lcp, oldText.length)
        } else {
            0
        }
        val appendText = if (lcp < newText.length) newText.substring(lcp) else ""
        return TextDiff(backspaces, appendText)
    }
}

class RemoteImeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatEditText(context, attrs) {

    interface InputListener {
        fun onText(text: String)
        fun onKey(key: String, repeat: Int = 1)
        fun hasShortcutModifier(): Boolean
        fun onShortcutText(text: String)
    }

    var inputListener: InputListener? = null
    private var lastSnapshot = ""
    private var suppressWatcher = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isCursorVisible = false
        isLongClickable = false
        setTextIsSelectable(false)
        background = null
        setPadding(0, 0, 0, 0)
        setTextColor(Color.TRANSPARENT)
        alpha = 0f
        inputType = (
            InputType.TYPE_CLASS_TEXT
                or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            )
        imeOptions = (
            EditorInfo.IME_ACTION_NONE
                or EditorInfo.IME_FLAG_NO_ENTER_ACTION
                or EditorInfo.IME_FLAG_NO_FULLSCREEN
                or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            )

        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher || s == null) return
                val current = s.toString()
                val diff = TextDiffCalculator.computeDiff(lastSnapshot, current)
                if (diff.backspaces > 0) {
                    inputListener?.onKey("BACKSPACE", diff.backspaces)
                }
                if (diff.appendText.isNotEmpty()) {
                    inputListener?.onText(diff.appendText)
                }
                lastSnapshot = current

                if (diff.appendText.endsWith("\n")) {
                    post { resetBuffer() }
                    return
                }

                // Keep buffer bounded at word boundaries without breaking active composing spans.
                if (s.length > 100 && BaseInputConnection.getComposingSpanStart(s) == -1) {
                    val targetCut = s.length - 50
                    val spaceIdx = s.lastIndexOf(' ', targetCut)
                    val cut = if (spaceIdx > 0) spaceIdx + 1 else targetCut
                    suppressWatcher = true
                    s.delete(0, cut)
                    lastSnapshot = s.toString()
                    Selection.setSelection(s, s.length)
                    suppressWatcher = false
                }
            }
        })
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.imeOptions = (
            outAttrs.imeOptions
                or EditorInfo.IME_ACTION_NONE
                or EditorInfo.IME_FLAG_NO_ENTER_ACTION
                or EditorInfo.IME_FLAG_NO_FULLSCREEN
                or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            )
        return RemoteInputConnectionWrapper(base)
    }

    fun ensureKeyboardVisible(activity: Activity) {
        if (!hasFocus()) {
            requestFocus()
        }
        WindowCompat.getInsetsController(activity.window, this).show(WindowInsetsCompat.Type.ime())
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, 0)
    }

    fun hideKeyboard(activity: Activity) {
        WindowCompat.getInsetsController(activity.window, this).hide(WindowInsetsCompat.Type.ime())
    }

    fun resetBuffer() {
        val currentText = text
        if (currentText.isNullOrEmpty() && lastSnapshot.isEmpty()) return
        suppressWatcher = true
        setText("")
        lastSnapshot = ""
        suppressWatcher = false
    }

    private inner class RemoteInputConnectionWrapper(target: InputConnection) :
        InputConnectionWrapper(target, true) {

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (handleModifierShortcut(text)) return true
            return super.setComposingText(text, newCursorPosition)
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (handleModifierShortcut(text)) return true
            return super.commitText(text, newCursorPosition)
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val editable = text
            val selStart = if (editable != null) Selection.getSelectionStart(editable).coerceAtLeast(0) else 0
            val missingBefore = (beforeLength - selStart).coerceAtLeast(0)
            val ok = super.deleteSurroundingText(beforeLength, afterLength)
            if (missingBefore > 0) {
                inputListener?.onKey("BACKSPACE", missingBefore)
            }
            return ok
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            val editable = text
            val selStart = if (editable != null) Selection.getSelectionStart(editable).coerceAtLeast(0) else 0
            val existingCodePoints = if (editable != null && selStart > 0) {
                Character.codePointCount(editable, 0, selStart)
            } else {
                0
            }
            val missingBefore = (beforeLength - existingCodePoints).coerceAtLeast(0)
            val ok = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            if (missingBefore > 0) {
                inputListener?.onKey("BACKSPACE", missingBefore)
            }
            return ok
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            inputListener?.onKey("ENTER", 1)
            resetBuffer()
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_UP) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_TAB,
                    KeyEvent.KEYCODE_ESCAPE,
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                }
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> {
                        val editable = text
                        val selStart = if (editable != null) Selection.getSelectionStart(editable) else 0
                        if (editable.isNullOrEmpty() || selStart <= 0) {
                            inputListener?.onKey("BACKSPACE", 1)
                            return true
                        }
                    }

                    KeyEvent.KEYCODE_ENTER -> {
                        inputListener?.onKey("ENTER", 1)
                        resetBuffer()
                        return true
                    }

                    KeyEvent.KEYCODE_TAB -> {
                        inputListener?.onKey("TAB", 1)
                        resetBuffer()
                        return true
                    }

                    KeyEvent.KEYCODE_ESCAPE -> {
                        inputListener?.onKey("ESC", 1)
                        resetBuffer()
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_UP -> {
                        inputListener?.onKey("UP", 1)
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        inputListener?.onKey("DOWN", 1)
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        inputListener?.onKey("LEFT", 1)
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        inputListener?.onKey("RIGHT", 1)
                        return true
                    }

                    else -> {
                        val ch = event.unicodeChar
                        if (ch != 0 && inputListener?.hasShortcutModifier() == true) {
                            handleModifierShortcut(String(Character.toChars(ch)))
                            return true
                        }
                    }
                }
            }
            return super.sendKeyEvent(event)
        }

        private fun handleModifierShortcut(text: CharSequence?): Boolean {
            val listener = inputListener ?: return false
            if (!listener.hasShortcutModifier()) return false
            val str = text?.toString().orEmpty()
            if (str.isEmpty()) return false
            listener.onShortcutText(str)
            resetBuffer()
            return true
        }
    }
}
