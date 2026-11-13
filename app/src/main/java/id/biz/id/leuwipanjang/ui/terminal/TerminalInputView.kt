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

    // Called by TerminalActivity to get arrow keys in correct mode
    var getArrowKey: ((direction: Char) -> String)? = null

    // D-pad center / OK on a TV remote (opens the on-screen keyboard)
    var onCenterPressed: (() -> Unit)? = null

    // Paste request: Ctrl+Shift+V, Shift+Insert, the Paste key or an IME paste action.
    // Plain Ctrl+V stays ^V, which nano, vim and readline use.
    var onPaste: (() -> Unit)? = null

    companion object {
        /** Maps a character to its Ctrl+<char> control code, or null if it has none. */
        fun controlCharFor(c: Char): Char? = when (c) {
            in 'a'..'z', in 'A'..'Z' -> (c.uppercaseChar().code - 64).toChar()
            '\\' -> '\u001C' // SIGQUIT (Ctrl+\)
            '[' -> '\u001B'  // ESC
            ']' -> '\u001D'
            '^' -> '\u001E'
            '_' -> '\u001F'
            '?' -> '\u007F'
            '@', ' ' -> '\u0000'
            else -> null
        }
    }

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
                    KeyEvent.KEYCODE_ENTER -> {
                        onEnter?.invoke()
                        composingText = ""
                        return true
                    }
                    KeyEvent.KEYCODE_TAB -> {
                        if (event.isShiftPressed) {
                            onCommitTextInput?.invoke("\u001B[Z")
                        } else {
                            onCommitTextInput?.invoke("\t")
                        }
                        composingText = ""
                        return true
                    }
                    KeyEvent.KEYCODE_ESCAPE -> {
                        onCommitTextInput?.invoke("\u001B")
                        composingText = ""
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        onCommitTextInput?.invoke(getArrowKey?.invoke('A') ?: "\u001B[A")
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        onCommitTextInput?.invoke(getArrowKey?.invoke('B') ?: "\u001B[B")
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onCommitTextInput?.invoke(getArrowKey?.invoke('C') ?: "\u001B[C")
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onCommitTextInput?.invoke(getArrowKey?.invoke('D') ?: "\u001B[D")
                        return true
                    }
                    KeyEvent.KEYCODE_PAGE_UP -> {
                        onCommitTextInput?.invoke("\u001B[5~")
                        return true
                    }
                    KeyEvent.KEYCODE_PAGE_DOWN -> {
                        onCommitTextInput?.invoke("\u001B[6~")
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
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_TAB,
                    KeyEvent.KEYCODE_ESCAPE,
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_PAGE_UP,
                    KeyEvent.KEYCODE_PAGE_DOWN -> return true
                }
            }
            return super.sendKeyEvent(event)
        }

        override fun performContextMenuAction(id: Int): Boolean {
            if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
                onPaste?.invoke()
                composingText = ""
                return true
            }
            return super.performContextMenuAction(id)
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
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            onCenterPressed?.invoke()
            return true
        }

        val isPaste = keyCode == KeyEvent.KEYCODE_PASTE ||
            (keyCode == KeyEvent.KEYCODE_V && event.isCtrlPressed && event.isShiftPressed) ||
            (keyCode == KeyEvent.KEYCODE_INSERT && event.isShiftPressed)
        if (isPaste) {
            onPaste?.invoke()
            resetComposing()
            return true
        }

        // Physical keyboard Ctrl+<key> (e.g. Ctrl+C) -> control character
        if (event.isCtrlPressed) {
            val base = event.getUnicodeChar(
                event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
            )
            val ctrl = if (base > 0) controlCharFor(base.toChar()) else null
            if (ctrl != null) {
                onCommitTextInput?.invoke(ctrl.toString())
                resetComposing()
                return true
            }
        }

        // Physical keyboard left Alt+<key> -> ESC prefix (Meta). Right Alt stays AltGr.
        if (event.metaState and KeyEvent.META_ALT_LEFT_ON != 0) {
            val base = event.getUnicodeChar(event.metaState and KeyEvent.META_ALT_MASK.inv())
            if (base > 0) {
                onCommitTextInput?.invoke("\u001B" + base.toChar())
                resetComposing()
                return true
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_MOVE_HOME -> {
                onCommitTextInput?.invoke("\u001B[H")
                return true
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                onCommitTextInput?.invoke("\u001B[F")
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                onCommitTextInput?.invoke("\u001B[3~")
                return true
            }
            KeyEvent.KEYCODE_INSERT -> {
                onCommitTextInput?.invoke("\u001B[2~")
                return true
            }
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
                if (event.isShiftPressed) {
                    onCommitTextInput?.invoke("\u001B[Z")
                } else {
                    onCommitTextInput?.invoke("\t")
                }
                resetComposing()
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                onCommitTextInput?.invoke("\u001B")
                resetComposing()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                onCommitTextInput?.invoke(getArrowKey?.invoke('A') ?: "\u001B[A")
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                onCommitTextInput?.invoke(getArrowKey?.invoke('B') ?: "\u001B[B")
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                onCommitTextInput?.invoke(getArrowKey?.invoke('C') ?: "\u001B[C")
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                onCommitTextInput?.invoke(getArrowKey?.invoke('D') ?: "\u001B[D")
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                onCommitTextInput?.invoke("\u001B[5~")
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                onCommitTextInput?.invoke("\u001B[6~")
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
