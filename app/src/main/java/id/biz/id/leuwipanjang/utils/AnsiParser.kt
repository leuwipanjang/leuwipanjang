package id.biz.id.leuwipanjang.utils

import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import id.biz.id.leuwipanjang.data.model.TerminalTheme
import java.util.regex.Pattern

class AnsiParser(private var theme: TerminalTheme) {

    var onTitleChanged: ((String) -> Unit)? = null
    var onClearScreen: (() -> Unit)? = null

    private var currentFgColor = theme.foregroundColor
    private var currentBgColor: Int? = null
    private var isBold = false
    private var isUnderline = false

    private var pendingBuffer = ""

    companion object {
        private const val MAX_BUFFER_SIZE = 100_000
        private const val TRIM_TARGET_SIZE = 75_000

        // Pattern matches:
        // 1. OSC sequences: \e] ... (\a | \e\)
        // 2. CSI sequences: \e[ <params> <command>
        // 3. Charset selection: \e(B, etc.
        // 4. Two-char escapes: \e=, \e>, etc.
        // 5. DCS/APC/PM: \eP...\e\
        // 6. Orphan ESC: \e
        private val ESCAPE_PATTERN = Pattern.compile(
            "\\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)?" +
            "|" +
            "\\u001B\\[([0-9;?><=!'\"]*)([@A-Za-z~])" +
            "|" +
            "\\u001B[()\\*+][0-9A-Za-z]" +
            "|" +
            "\\u001B[=>78cDENMH]" +
            "|" +
            "\\u001B[P_\\^][^\\u001B]*(?:\\u001B\\\\|\\u0007)?" +
            "|" +
            "\\u001B"
        )
    }

    fun setTheme(newTheme: TerminalTheme) {
        this.theme = newTheme
        this.currentFgColor = newTheme.foregroundColor
    }

    fun parse(rawText: String): CharSequence {
        val builder = SpannableStringBuilder()
        appendTo(builder, rawText)
        return builder
    }

    fun appendTo(target: Editable, rawText: String) {
        // Normalize CRLF to LF, but keep standalone CR for in-place line overwrites
        val normalized = rawText.replace("\r\n", "\n")
        val combined = pendingBuffer + normalized
        pendingBuffer = ""

        // Check if the stream ends with an incomplete escape sequence
        val textToParse: String
        val lastEsc = combined.lastIndexOf('\u001B')
        if (lastEsc >= 0) {
            val tail = combined.substring(lastEsc)
            val isIncomplete = when {
                tail == "\u001B" -> true
                tail.startsWith("\u001B[") -> !tail.any { it in '@'..'~' }
                tail.startsWith("\u001B]") -> !tail.contains('\u0007') && !tail.contains("\u001B\\")
                tail.length == 2 && tail[1] in "()*+" -> true
                else -> false
            }
            if (isIncomplete && tail.length < 256) {
                pendingBuffer = tail
                textToParse = combined.substring(0, lastEsc)
            } else {
                textToParse = combined
            }
        } else {
            textToParse = combined
        }

        if (textToParse.isEmpty()) {
            return
        }

        val matcher = ESCAPE_PATTERN.matcher(textToParse)
        var lastEnd = 0

        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()

            // Append preceding text if any
            if (start > lastEnd) {
                val chunk = textToParse.substring(lastEnd, start)
                appendCleanChunk(target, chunk, currentFgColor, currentBgColor, isBold, isUnderline)
            }

            val matched = matcher.group()
            if (matched.startsWith("\u001B]")) {
                // Operating System Command (e.g. \e]0;title\a)
                val content = matched.removePrefix("\u001B]").trimEnd('\u0007', '\\', '\u001B')
                val parts = content.split(";", limit = 2)
                if (parts.size == 2 && (parts[0] == "0" || parts[0] == "2")) {
                    val title = parts[1].trim()
                    if (title.isNotEmpty()) {
                        onTitleChanged?.invoke(title)
                    }
                }
            } else if (matched.startsWith("\u001B[")) {
                // CSI sequence
                val params = matcher.group(1) ?: ""
                val command = matcher.group(2) ?: ""

                when (command) {
                    "m" -> handleSgr(params)
                    "J" -> {
                        if (params.contains("2") || params.contains("3")) {
                            target.clear()
                            onClearScreen?.invoke()
                        }
                    }
                    "K" -> {
                        // Erase in line
                        if (params == "2") {
                            val lastNl = target.lastIndexOf('\n')
                            if (lastNl >= 0) {
                                target.delete(lastNl + 1, target.length)
                            } else {
                                target.clear()
                            }
                        }
                    }
                    "D" -> {
                        // Cursor Backward (Left) - delete up to current line start
                        val count = params.toIntOrNull() ?: 1
                        for (c in 0 until count) {
                            if (target.isNotEmpty() && target[target.length - 1] != '\n') {
                                target.delete(target.length - 1, target.length)
                            }
                        }
                    }
                    // Other CSI commands consumed
                }
            }

            lastEnd = end
        }

        // Append any remaining text after the last match
        if (lastEnd < textToParse.length) {
            val chunk = textToParse.substring(lastEnd)
            appendCleanChunk(target, chunk, currentFgColor, currentBgColor, isBold, isUnderline)
        }

        // Prevent memory exhaustion on very long sessions
        trimBufferIfNeeded(target)
    }

    private fun handleSgr(params: String) {
        val codes = if (params.isEmpty()) listOf("0") else params.split(";")
        var i = 0
        while (i < codes.size) {
            val code = codes[i].toIntOrNull()
            if (code == null) {
                i++
                continue
            }

            when (code) {
                0 -> {
                    currentFgColor = theme.foregroundColor
                    currentBgColor = null
                    isBold = false
                    isUnderline = false
                }
                1 -> isBold = true
                4 -> isUnderline = true
                22 -> isBold = false
                24 -> isUnderline = false
                in 30..37 -> currentFgColor = getStandardColor(code - 30)
                38 -> {
                    // Extended foreground: 38;5;n or 38;2;r;g;b
                    if (i + 2 < codes.size && codes[i + 1] == "5") {
                        val colorIdx = codes[i + 2].toIntOrNull()
                        if (colorIdx != null) {
                            currentFgColor = getXtermColor(colorIdx)
                        }
                        i += 2
                    } else if (i + 4 < codes.size && codes[i + 1] == "2") {
                        val r = codes[i + 2].toIntOrNull() ?: 0
                        val g = codes[i + 3].toIntOrNull() ?: 0
                        val b = codes[i + 4].toIntOrNull() ?: 0
                        currentFgColor = Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
                        i += 4
                    }
                }
                39 -> currentFgColor = theme.foregroundColor
                in 40..47 -> currentBgColor = getStandardColor(code - 40)
                48 -> {
                    // Extended background: 48;5;n or 48;2;r;g;b
                    if (i + 2 < codes.size && codes[i + 1] == "5") {
                        val colorIdx = codes[i + 2].toIntOrNull()
                        if (colorIdx != null) {
                            currentBgColor = getXtermColor(colorIdx)
                        }
                        i += 2
                    } else if (i + 4 < codes.size && codes[i + 1] == "2") {
                        val r = codes[i + 2].toIntOrNull() ?: 0
                        val g = codes[i + 3].toIntOrNull() ?: 0
                        val b = codes[i + 4].toIntOrNull() ?: 0
                        currentBgColor = Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
                        i += 4
                    }
                }
                49 -> currentBgColor = null
                in 90..97 -> currentFgColor = getBrightColor(code - 90)
                in 100..107 -> currentBgColor = getBrightColor(code - 100)
            }
            i++
        }
    }

    private fun appendCleanChunk(
        target: Editable,
        chunk: String,
        fgColor: Int,
        bgColor: Int?,
        bold: Boolean,
        underline: Boolean
    ) {
        val clean = StringBuilder()
        for (i in chunk.indices) {
            val c = chunk[i]
            when {
                c == '\b' -> {
                    if (clean.isNotEmpty()) {
                        clean.deleteCharAt(clean.length - 1)
                    } else if (target.isNotEmpty() && target[target.length - 1] != '\n') {
                        target.delete(target.length - 1, target.length)
                    }
                }
                c == '\r' -> {
                    // Flush accumulated text first
                    if (clean.isNotEmpty()) {
                        appendStyled(target, clean.toString(), fgColor, bgColor, bold, underline)
                        clean.setLength(0)
                    }
                    // Carriage return: overwrite from the beginning of the current line
                    val lastNl = target.lastIndexOf('\n')
                    if (lastNl >= 0) {
                        target.delete(lastNl + 1, target.length)
                    } else {
                        target.clear()
                    }
                }
                c == '\t' || c == '\n' -> {
                    clean.append(c)
                }
                c < ' ' || c.code == 127 -> {
                    // Drop unprintable control characters to prevent square box glyphs
                }
                else -> {
                    clean.append(c)
                }
            }
        }

        if (clean.isNotEmpty()) {
            appendStyled(target, clean.toString(), fgColor, bgColor, bold, underline)
        }
    }

    private fun appendStyled(
        target: Editable,
        text: String,
        fgColor: Int,
        bgColor: Int?,
        bold: Boolean,
        underline: Boolean
    ) {
        val start = target.length
        target.append(text)
        val end = target.length

        target.setSpan(ForegroundColorSpan(fgColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        if (bgColor != null) {
            target.setSpan(BackgroundColorSpan(bgColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        if (bold) {
            target.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        if (underline) {
            target.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun trimBufferIfNeeded(target: Editable) {
        if (target.length > MAX_BUFFER_SIZE) {
            val excess = target.length - TRIM_TARGET_SIZE
            val nextNl = target.indexOf('\n', excess)
            if (nextNl >= 0) {
                target.delete(0, nextNl + 1)
            } else {
                target.delete(0, excess)
            }
        }
    }

    private fun getStandardColor(index: Int): Int {
        return when (index) {
            0 -> theme.black
            1 -> theme.red
            2 -> theme.green
            3 -> theme.yellow
            4 -> theme.blue
            5 -> theme.magenta
            6 -> theme.cyan
            7 -> theme.white
            else -> theme.foregroundColor
        }
    }

    private fun getBrightColor(index: Int): Int {
        return when (index) {
            0 -> Color.parseColor("#6B7280")
            1 -> Color.parseColor("#F87171")
            2 -> Color.parseColor("#4ADE80")
            3 -> Color.parseColor("#FDE047")
            4 -> Color.parseColor("#60A5FA")
            5 -> Color.parseColor("#E879F9")
            6 -> Color.parseColor("#22D3EE")
            7 -> Color.parseColor("#FFFFFF")
            else -> theme.foregroundColor
        }
    }

    private fun getXtermColor(index: Int): Int {
        if (index in 0..7) return getStandardColor(index)
        if (index in 8..15) return getBrightColor(index - 8)
        if (index in 16..231) {
            val i = index - 16
            val r = (i / 36)
            val g = (i % 36) / 6
            val b = i % 6
            fun comp(v: Int) = if (v == 0) 0 else (55 + v * 40)
            return Color.rgb(comp(r), comp(g), comp(b))
        }
        if (index in 232..255) {
            val gray = 8 + (index - 232) * 10
            return Color.rgb(gray, gray, gray)
        }
        return theme.foregroundColor
    }
}
