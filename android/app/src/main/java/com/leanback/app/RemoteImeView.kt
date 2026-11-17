package com.leanback.app

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
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
) : View(context, attrs) {

    interface InputListener {
        fun onText(text: String)
        fun onKey(key: String, repeat: Int = 1)
        fun hasShortcutModifier(): Boolean
        fun onShortcutText(text: String)
    }

    var inputListener: InputListener? = null
    private val buffer = SpannableStringBuilder()
    private var lastSnapshot = ""

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
        outAttrs.imeOptions = (
            EditorInfo.IME_ACTION_NONE
                or EditorInfo.IME_FLAG_NO_ENTER_ACTION
                or EditorInfo.IME_FLAG_NO_FULLSCREEN
                or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            )
        return RemoteInputConnection(this)
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
        buffer.clear()
        Selection.setSelection(buffer, 0)
        lastSnapshot = ""
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.restartInput(this)
    }

    private fun syncBufferDiff() {
        val current = buffer.toString()
        val diff = TextDiffCalculator.computeDiff(lastSnapshot, current)
        if (diff.backspaces > 0) {
            inputListener?.onKey("BACKSPACE", diff.backspaces)
        }
        if (diff.appendText.isNotEmpty()) {
            inputListener?.onText(diff.appendText)
        }
        lastSnapshot = current

        // Trim old committed prefix silently so long typing sessions stay bounded.
        if (buffer.length > 80 && BaseInputConnection.getComposingSpanStart(buffer) == -1) {
            val drop = buffer.length - 40
            buffer.delete(0, drop)
            lastSnapshot = buffer.toString()
            Selection.setSelection(buffer, buffer.length)
        }
    }

    private inner class RemoteInputConnection(targetView: View) :
        BaseInputConnection(targetView, true) {

        override fun getEditable(): Editable = buffer

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (handleModifierShortcut(text)) return true
            val ok = super.setComposingText(text, newCursorPosition)
            syncBufferDiff()
            return ok
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (handleModifierShortcut(text)) return true
            val ok = super.commitText(text, newCursorPosition)
            syncBufferDiff()
            return ok
        }

        override fun finishComposingText(): Boolean {
            val ok = super.finishComposingText()
            syncBufferDiff()
            return ok
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val cursor = Selection.getSelectionStart(buffer).coerceAtLeast(0)
            val missingBefore = (beforeLength - cursor).coerceAtLeast(0)
            val ok = super.deleteSurroundingText(beforeLength, afterLength)
            syncBufferDiff()
            if (missingBefore > 0) {
                inputListener?.onKey("BACKSPACE", missingBefore)
            }
            return ok
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            val cursor = Selection.getSelectionStart(buffer).coerceAtLeast(0)
            val existingCodePoints = if (cursor > 0) {
                Character.codePointCount(buffer, 0, cursor)
            } else {
                0
            }
            val missingBefore = (beforeLength - existingCodePoints).coerceAtLeast(0)
            val ok = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            syncBufferDiff()
            if (missingBefore > 0) {
                inputListener?.onKey("BACKSPACE", missingBefore)
            }
            return ok
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> {
                        val cursor = Selection.getSelectionStart(buffer).coerceAtLeast(0)
                        if (cursor > 0) {
                            val start = Character.offsetByCodePoints(buffer, cursor, -1)
                            buffer.delete(start, cursor)
                            lastSnapshot = buffer.toString()
                        }
                        inputListener?.onKey("BACKSPACE", 1)
                        return true
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
                        if (ch != 0) {
                            val str = String(Character.toChars(ch))
                            if (!handleModifierShortcut(str)) {
                                inputListener?.onText(str)
                            }
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
