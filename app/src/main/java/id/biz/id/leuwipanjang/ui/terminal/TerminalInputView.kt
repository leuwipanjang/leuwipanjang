package id.biz.id.leuwipanjang.ui.terminal

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

class TerminalInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onCommitTextInput: ((String) -> Unit)? = null
    var onBackspace: (() -> Unit)? = null
    var onEnter: (() -> Unit)? = null

    private var currentInputConnection: TerminalInputConnection? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI

        val connection = TerminalInputConnection(this)
        currentInputConnection = connection
        return connection
    }

    fun resetComposing() {
        currentInputConnection?.resetComposing()
    }

    private inner class TerminalInputConnection(
        targetView: View
    ) : BaseInputConnection(targetView, false) {

        private var composingText = ""

        fun resetComposing() {
            composingText = ""
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (!text.isNullOrEmpty()) {
                val str = text.toString()
                if (composingText.isNotEmpty()) {
                    if (str.startsWith(composingText)) {
                        val diff = str.substring(composingText.length)
                        if (diff.isNotEmpty()) {
                            onCommitTextInput?.invoke(diff)
                        }
                    } else {
                        onCommitTextInput?.invoke(str)
                    }
                    composingText = ""
                } else {
                    onCommitTextInput?.invoke(str)
                }
            }
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val str = text?.toString().orEmpty()
            if (str.isEmpty()) {
                if (composingText.isNotEmpty()) {
                    for (i in composingText.indices) {
                        onBackspace?.invoke()
                    }
                    composingText = ""
                }
                return true
            }

            if (composingText.isEmpty()) {
                onCommitTextInput?.invoke(str)
                composingText = str
            } else if (str.startsWith(composingText)) {
                val diff = str.substring(composingText.length)
                if (diff.isNotEmpty()) {
                    onCommitTextInput?.invoke(diff)
                }
                composingText = str
            } else if (composingText.startsWith(str)) {
                val deleteCount = composingText.length - str.length
                for (i in 0 until deleteCount) {
                    onBackspace?.invoke()
                }
                composingText = str
            } else {
                for (i in composingText.indices) {
                    onBackspace?.invoke()
                }
                onCommitTextInput?.invoke(str)
                composingText = str
            }
            return true
        }

        override fun finishComposingText(): Boolean {
            composingText = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val count = if (beforeLength > 0) beforeLength else 1
            for (i in 0 until count) {
                onBackspace?.invoke()
            }
            if (composingText.isNotEmpty()) {
                if (composingText.length >= count) {
                    composingText = composingText.dropLast(count)
                } else {
                    composingText = ""
                }
            }
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            return deleteSurroundingText(beforeLength, afterLength)
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> {
                        onBackspace?.invoke()
                        composingText = ""
                        return true
                    }
                    KeyEvent.KEYCODE_ENTER -> {
                        onEnter?.invoke()
                        composingText = ""
                        return true
                    }
                    KeyEvent.KEYCODE_TAB -> {
                        onCommitTextInput?.invoke("\t")
                        composingText = ""
                        return true
                    }
                    KeyEvent.KEYCODE_ESCAPE -> {
                        onCommitTextInput?.invoke("\u001B")
                        composingText = ""
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        onCommitTextInput?.invoke("\u001B[A")
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        onCommitTextInput?.invoke("\u001B[B")
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onCommitTextInput?.invoke("\u001B[C")
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onCommitTextInput?.invoke("\u001B[D")
                        return true
                    }
                }
                val unicode = event.unicodeChar
                if (unicode > 0) {
                    onCommitTextInput?.invoke(unicode.toChar().toString())
                    composingText = ""
                    return true
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_TAB,
                    KeyEvent.KEYCODE_ESCAPE,
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_LEFT -> return true
                }
            }
            return super.sendKeyEvent(event)
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            onEnter?.invoke()
            composingText = ""
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                onBackspace?.invoke()
                resetComposing()
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                onEnter?.invoke()
                resetComposing()
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                onCommitTextInput?.invoke("\t")
                resetComposing()
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                onCommitTextInput?.invoke("\u001B")
                resetComposing()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                onCommitTextInput?.invoke("\u001B[A")
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                onCommitTextInput?.invoke("\u001B[B")
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                onCommitTextInput?.invoke("\u001B[C")
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                onCommitTextInput?.invoke("\u001B[D")
                return true
            }
        }
        val unicode = event.unicodeChar
        if (unicode > 0) {
            onCommitTextInput?.invoke(unicode.toChar().toString())
            resetComposing()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
