package id.biz.id.leuwipanjang.ui.terminal

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

class TerminalInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    var onCommitTextInput: ((String) -> Unit)? = null
    var onBackspace: (() -> Unit)? = null
    var onEnter: (() -> Unit)? = null

    private var isResetting = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setTextColor(Color.TRANSPARENT)
        isCursorVisible = false
        inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        imeOptions = EditorInfo.IME_ACTION_NONE or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN
        ensureDummyText()
    }

    fun ensureDummyText() {
        if (text.toString() != " ") {
            isResetting = true
            setText(" ")
            setSelection(1)
            isResetting = false
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN

        ensureDummyText()
        val target = super.onCreateInputConnection(outAttrs) ?: return null

        return object : InputConnectionWrapper(target, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty() && !isResetting) {
                    onCommitTextInput?.invoke(text.toString())
                }
                resetLater()
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty() && !isResetting) {
                    onCommitTextInput?.invoke(text.toString())
                }
                resetLater()
                return true
            }

            override fun finishComposingText(): Boolean {
                resetLater()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (!isResetting) {
                    onBackspace?.invoke()
                }
                resetLater()
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DEL -> {
                            onBackspace?.invoke()
                            resetLater()
                            return true
                        }
                        KeyEvent.KEYCODE_ENTER -> {
                            onEnter?.invoke()
                            resetLater()
                            return true
                        }
                    }
                    val unicode = event.unicodeChar
                    if (unicode > 0) {
                        onCommitTextInput?.invoke(unicode.toChar().toString())
                        resetLater()
                        return true
                    }
                }
                return super.sendKeyEvent(event)
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                onEnter?.invoke()
                resetLater()
                return true
            }

            private fun resetLater() {
                post {
                    ensureDummyText()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                onBackspace?.invoke()
                ensureDummyText()
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                onEnter?.invoke()
                ensureDummyText()
                return true
            }
        }
        val unicode = event.unicodeChar
        if (unicode > 0) {
            onCommitTextInput?.invoke(unicode.toChar().toString())
            ensureDummyText()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
