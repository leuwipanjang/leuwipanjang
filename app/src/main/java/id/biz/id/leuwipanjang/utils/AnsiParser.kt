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
    var onSendTerminalResponse: ((String) -> Unit)? = null

    private var currentFgColor = theme.foregroundColor
    private var currentBgColor: Int? = null
    private var isBold = false
    private var isUnderline = false
    private var isInverse = false

    private var cursorRow = 1
    private var cursorCol = 1
    private var savedCursorRow = 1
    private var savedCursorCol = 1

    private var isAlternateScreen = false
    private var mainScreenBackup: SpannableStringBuilder? = null

    private var pendingBuffer = ""

    companion object {
        private const val MAX_BUFFER_SIZE = 100_000
        private const val TRIM_TARGET_SIZE = 75_000

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
        val normalized = rawText.replace("\r\n", "\n")
        val combined = pendingBuffer + normalized
        pendingBuffer = ""

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

        if (textToParse.isEmpty()) return

        val matcher = ESCAPE_PATTERN.matcher(textToParse)
        var lastEnd = 0

        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()

            if (start > lastEnd) {
                val chunk = textToParse.substring(lastEnd, start)
                processTextChunk(target, chunk)
            }

            val matched = matcher.group()
            when {
                matched.startsWith("\u001B]") -> {
                    // OSC sequence
                    val content = matched.removePrefix("\u001B]").trimEnd('\u0007', '\\', '\u001B')
                    val parts = content.split(";", limit = 2)
                    if (parts.size == 2 && (parts[0] == "0" || parts[0] == "2")) {
                        val title = parts[1].trim()
                        if (title.isNotEmpty()) {
                            onTitleChanged?.invoke(title)
                        }
                    }
                }
                matched.startsWith("\u001B[") -> {
                    // CSI sequence
                    val params = matcher.group(1) ?: ""
                    val command = matcher.group(2) ?: ""
                    handleCsi(target, command, params)
                }
                matched == "\u001B7" -> {
                    // Save cursor position
                    savedCursorRow = cursorRow
                    savedCursorCol = cursorCol
                }
                matched == "\u001B8" -> {
                    // Restore cursor position
                    cursorRow = savedCursorRow
                    cursorCol = savedCursorCol
                }
                matched == "\u001Bc" -> {
                    // Full reset
                    target.clear()
                    cursorRow = 1
                    cursorCol = 1
                    onClearScreen?.invoke()
                }
            }

            lastEnd = end
        }

        if (lastEnd < textToParse.length) {
            val chunk = textToParse.substring(lastEnd)
            processTextChunk(target, chunk)
        }

        if (!isAlternateScreen) {
            trimBufferIfNeeded(target)
        }
    }

    private fun handleCsi(target: Editable, command: String, params: String) {
        when (command) {
            "m" -> handleSgr(params)
            "H", "f" -> {
                // Cursor position (CUP)
                val parts = params.split(";")
                cursorRow = parts.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                cursorCol = parts.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            }
            "A" -> {
                // Cursor Up
                val count = params.toIntOrNull() ?: 1
                cursorRow = (cursorRow - count).coerceAtLeast(1)
            }
            "B" -> {
                // Cursor Down
                val count = params.toIntOrNull() ?: 1
                cursorRow += count
            }
            "C" -> {
                // Cursor Forward
                val count = params.toIntOrNull() ?: 1
                cursorCol += count
            }
            "D" -> {
                // Cursor Backward
                val count = params.toIntOrNull() ?: 1
                cursorCol = (cursorCol - count).coerceAtLeast(1)
            }
            "G", "`" -> {
                // Cursor horizontal absolute
                cursorCol = (params.toIntOrNull() ?: 1).coerceAtLeast(1)
            }
            "d" -> {
                // Line position absolute
                cursorRow = (params.toIntOrNull() ?: 1).coerceAtLeast(1)
            }
            "J" -> {
                // Erase in Display
                if (params == "2" || params == "3") {
                    target.clear()
                    cursorRow = 1
                    cursorCol = 1
                    onClearScreen?.invoke()
                } else if (params == "" || params == "0") {
                    // Clear from cursor to end of screen
                    val (lineStart, _) = getLineRange(target, cursorRow)
                    val eraseStart = (lineStart + cursorCol - 1).coerceIn(0, target.length)
                    if (eraseStart < target.length) {
                        target.delete(eraseStart, target.length)
                    }
                }
            }
            "K" -> {
                // Erase in Line
                val (lineStart, lineEnd) = getLineRange(target, cursorRow)
                when (params) {
                    "2" -> {
                        // Clear entire line
                        if (lineEnd > lineStart) {
                            target.delete(lineStart, lineEnd)
                        }
                    }
                    "1" -> {
                        // Clear from start of line to cursor
                        val eraseEnd = (lineStart + cursorCol - 1).coerceIn(lineStart, lineEnd)
                        if (eraseEnd > lineStart) {
                            target.delete(lineStart, eraseEnd)
                        }
                    }
                    else -> {
                        // 0 or empty: clear from cursor to end of line
                        val eraseStart = (lineStart + cursorCol - 1).coerceIn(lineStart, lineEnd)
                        if (lineEnd > eraseStart) {
                            target.delete(eraseStart, lineEnd)
                        }
                    }
                }
            }
            "h" -> {
                // Set Mode
                if (params == "?1049" || params == "?47" || params == "?1047") {
                    if (!isAlternateScreen) {
                        isAlternateScreen = true
                        mainScreenBackup = SpannableStringBuilder(target)
                        target.clear()
                        cursorRow = 1
                        cursorCol = 1
                    }
                }
            }
            "l" -> {
                // Reset Mode
                if (params == "?1049" || params == "?47" || params == "?1047") {
                    if (isAlternateScreen) {
                        isAlternateScreen = false
                        target.clear()
                        mainScreenBackup?.let { target.append(it) }
                        mainScreenBackup = null
                        cursorRow = 1
                        cursorCol = 1
                    }
                }
            }
            "n" -> {
                // Device Status Report
                if (params == "6") {
                    onSendTerminalResponse?.invoke("\u001B[${cursorRow};${cursorCol}R")
                } else if (params == "5") {
                    onSendTerminalResponse?.invoke("\u001B[0n")
                }
            }
            "c" -> {
                // Device Attributes
                onSendTerminalResponse?.invoke("\u001B[?1;2c")
            }
        }
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
                    isInverse = false
                }
                1 -> isBold = true
                4 -> isUnderline = true
                7 -> isInverse = true
                22 -> isBold = false
                24 -> isUnderline = false
                27 -> isInverse = false
                in 30..37 -> currentFgColor = getStandardColor(code - 30)
                38 -> {
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

    private fun processTextChunk(target: Editable, chunk: String) {
        val segment = StringBuilder()
        for (i in chunk.indices) {
            val c = chunk[i]
            when (c) {
                '\r' -> {
                    if (segment.isNotEmpty()) {
                        writeSegment(target, segment.toString())
                        segment.setLength(0)
                    }
                    cursorCol = 1
                }
                '\n' -> {
                    if (segment.isNotEmpty()) {
                        writeSegment(target, segment.toString())
                        segment.setLength(0)
                    }
                    cursorRow++
                    cursorCol = 1
                }
                '\b' -> {
                    if (segment.isNotEmpty()) {
                        segment.deleteCharAt(segment.length - 1)
                    } else if (cursorCol > 1) {
                        cursorCol--
                        val (lineStart, lineEnd) = getLineRange(target, cursorRow)
                        val delPos = lineStart + cursorCol - 1
                        if (delPos < lineEnd) {
                            target.delete(delPos, delPos + 1)
                        }
                    }
                }
                '\t' -> {
                    // Tab stop (every 8 spaces)
                    val spaces = 8 - ((cursorCol - 1 + segment.length) % 8)
                    for (s in 0 until spaces) segment.append(' ')
                }
                else -> {
                    if (c >= ' ' && c.code != 127) {
                        segment.append(c)
                    }
                }
            }
        }

        if (segment.isNotEmpty()) {
            writeSegment(target, segment.toString())
        }
    }

    private fun writeSegment(target: Editable, text: String) {
        ensureLineCount(target, cursorRow)
        val (lineStart, lineEnd) = getLineRange(target, cursorRow)
        val colOffset = (cursorCol - 1).coerceAtLeast(0)
        val lineLen = lineEnd - lineStart

        // Pad line with spaces if cursor is past end of line
        if (colOffset > lineLen) {
            val padding = " ".repeat(colOffset - lineLen)
            target.insert(lineEnd, padding)
        }

        val updatedLineEnd = lineEnd + if (colOffset > lineLen) (colOffset - lineLen) else 0
        val insertPos = lineStart + colOffset

        // Overwrite existing characters if any, up to the end of the line
        val overwriteLen = minOf(text.length, (updatedLineEnd - insertPos).coerceAtLeast(0))
        if (overwriteLen > 0) {
            target.delete(insertPos, insertPos + overwriteLen)
        }

        insertStyled(target, insertPos, text)
        cursorCol += text.length
    }

    private fun insertStyled(target: Editable, position: Int, text: String) {
        val fg = if (isInverse) (currentBgColor ?: theme.backgroundColor) else currentFgColor
        val bg = if (isInverse) currentFgColor else currentBgColor

        val insertIndex = position.coerceIn(0, target.length)
        target.insert(insertIndex, text)
        val end = insertIndex + text.length

        target.setSpan(ForegroundColorSpan(fg), insertIndex, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bg != null) {
            target.setSpan(BackgroundColorSpan(bg), insertIndex, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (isBold) {
            target.setSpan(StyleSpan(Typeface.BOLD), insertIndex, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (isUnderline) {
            target.setSpan(UnderlineSpan(), insertIndex, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun ensureLineCount(target: Editable, requiredLines: Int) {
        var count = 1
        for (i in 0 until target.length) {
            if (target[i] == '\n') count++
        }
        while (count < requiredLines) {
            target.append('\n')
            count++
        }
    }

    private fun getLineRange(target: Editable, row: Int): Pair<Int, Int> {
        ensureLineCount(target, row)
        var currentLine = 1
        var start = 0
        for (i in 0 until target.length) {
            if (currentLine == row) {
                val end = target.indexOf('\n', start).let { if (it >= 0) it else target.length }
                return Pair(start, end)
            }
            if (target[i] == '\n') {
                currentLine++
                start = i + 1
            }
        }
        return Pair(start, target.length)
    }

    private fun trimBufferIfNeeded(target: Editable) {
        if (target.length > MAX_BUFFER_SIZE) {
            val excess = target.length - TRIM_TARGET_SIZE
            val nextNl = target.indexOf('\n', excess)
            if (nextNl >= 0) {
                target.delete(0, nextNl + 1)
                // Recalculate cursorRow relative to trimmed lines
                var linesRemoved = 1
                for (i in 0 until nextNl) {
                    if (target[i] == '\n') linesRemoved++
                }
                cursorRow = (cursorRow - linesRemoved).coerceAtLeast(1)
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
