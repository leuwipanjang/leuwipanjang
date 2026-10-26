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

    var terminalCols = 80
        private set
    var terminalRows = 24
        private set

    private var currentFgColor = theme.foregroundColor
    private var currentBgColor: Int? = null
    private var isBold = false
    private var isUnderline = false
    private var isInverse = false

    // Alternate Screen State (for nano, vi, htop, less)
    private var isAlternateScreen = false
    val isAlternateScreenActive: Boolean
        get() = isAlternateScreen

    // Application Cursor Key Mode (DECCKM) - set by \e[?1h, reset by \e[?1l
    // When active: arrow keys send \eOA/B/C/D instead of \e[A/B/C/D
    private var isApplicationCursorKeys = false
    val isApplicationCursorKeysActive: Boolean
        get() = isApplicationCursorKeys
    private var mainScreenBackup: SpannableStringBuilder? = null
    private val altRows = ArrayList<SpannableStringBuilder>()
    private var altCursorRow = 0 // 0-based
    private var altCursorCol = 0 // 0-based
    private var savedAltRow = 0
    private var savedAltCol = 0

    // Visual Cursor tracking
    class TerminalCursorSpan(color: Int) : BackgroundColorSpan(color)
    class TerminalCursorFgSpan(color: Int) : ForegroundColorSpan(color)

    private var normalCursorOffsetFromEnd = 0
    private var hasCursorPlaceholder = false

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

    fun setTerminalDimensions(cols: Int, rows: Int) {
        this.terminalCols = cols.coerceIn(20, 240)
        this.terminalRows = rows.coerceIn(10, 100)
    }

    fun parse(rawText: String): CharSequence {
        val builder = SpannableStringBuilder()
        appendTo(builder, rawText)
        return builder
    }

    fun appendTo(target: Editable, rawText: String) {
        removeNormalCursor(target)

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

        if (textToParse.isEmpty()) {
            if (isAlternateScreen) {
                renderAltToTarget(target)
            } else {
                applyNormalCursor(target)
            }
            return
        }

        val matcher = ESCAPE_PATTERN.matcher(textToParse)
        var lastEnd = 0

        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()

            if (start > lastEnd) {
                val chunk = textToParse.substring(lastEnd, start)
                if (isAlternateScreen) {
                    processAltChunk(chunk)
                } else {
                    appendCleanChunk(target, chunk)
                }
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
                    val params = matcher.group(1) ?: ""
                    val command = matcher.group(2) ?: ""
                    handleCsi(target, command, params)
                }
                matched == "\u001B7" -> {
                    if (isAlternateScreen) {
                        savedAltRow = altCursorRow
                        savedAltCol = altCursorCol
                    }
                }
                matched == "\u001B8" -> {
                    if (isAlternateScreen) {
                        altCursorRow = savedAltRow
                        altCursorCol = savedAltCol
                    }
                }
                matched == "\u001Bc" -> {
                    if (isAlternateScreen) {
                        initAltScreen(terminalRows)
                        renderAltToTarget(target)
                    } else {
                        target.clear()
                        normalCursorOffsetFromEnd = 0
                        hasCursorPlaceholder = false
                        onClearScreen?.invoke()
                    }
                }
            }

            lastEnd = end
        }

        if (lastEnd < textToParse.length) {
            val chunk = textToParse.substring(lastEnd)
            if (isAlternateScreen) {
                processAltChunk(chunk)
            } else {
                appendCleanChunk(target, chunk)
            }
        }

        if (isAlternateScreen) {
            renderAltToTarget(target)
        } else {
            trimBufferIfNeeded(target)
            applyNormalCursor(target)
        }
    }

    private fun handleCsi(target: Editable, command: String, params: String) {
        when (command) {
            "m" -> handleSgr(params)
            "H", "f" -> {
                // Cursor position (CUP)
                val parts = params.split(";")
                val r = (parts.getOrNull(0)?.toIntOrNull() ?: 1) - 1
                val c = (parts.getOrNull(1)?.toIntOrNull() ?: 1) - 1
                if (!isAlternateScreen && (r > 0 || c > 0)) {
                    enterAlternateScreen(target)
                }
                if (isAlternateScreen) {
                    altCursorRow = r.coerceIn(0, (terminalRows - 1).coerceAtLeast(0))
                    altCursorCol = c.coerceAtLeast(0)
                }
            }
            "A" -> {
                val count = params.toIntOrNull() ?: 1
                if (isAlternateScreen) {
                    altCursorRow = (altCursorRow - count).coerceAtLeast(0)
                }
            }
            "B" -> {
                val count = params.toIntOrNull() ?: 1
                if (isAlternateScreen) {
                    altCursorRow = (altCursorRow + count).coerceAtMost((terminalRows - 1).coerceAtLeast(0))
                }
            }
            "C" -> {
                val count = params.toIntOrNull() ?: 1
                if (isAlternateScreen) {
                    altCursorCol += count
                } else {
                    normalCursorOffsetFromEnd = (normalCursorOffsetFromEnd - count).coerceAtLeast(0)
                }
            }
            "D" -> {
                val count = params.toIntOrNull() ?: 1
                if (isAlternateScreen) {
                    altCursorCol = (altCursorCol - count).coerceAtLeast(0)
                } else {
                    val lineStart = target.lastIndexOf('\n').let { if (it >= 0) it + 1 else 0 }
                    val maxOffset = target.length - lineStart
                    normalCursorOffsetFromEnd = (normalCursorOffsetFromEnd + count).coerceAtMost(maxOffset)
                }
            }
            "G", "`" -> {
                val col = (params.toIntOrNull() ?: 1) - 1
                if (isAlternateScreen) {
                    altCursorCol = col.coerceAtLeast(0)
                }
            }
            "d" -> {
                val row = (params.toIntOrNull() ?: 1) - 1
                if (isAlternateScreen) {
                    altCursorRow = row.coerceIn(0, (terminalRows - 1).coerceAtLeast(0))
                }
            }
            "J" -> {
                if (params == "2" || params == "3") {
                    if (isAlternateScreen) {
                        initAltScreen(terminalRows)
                    } else {
                        target.clear()
                        onClearScreen?.invoke()
                    }
                }
            }
            "K" -> {
                if (isAlternateScreen) {
                    ensureAltRowExists(altCursorRow)
                    val rowBuilder = altRows[altCursorRow]
                    when (params) {
                        "2" -> rowBuilder.clear()
                        "1" -> {
                            val end = altCursorCol.coerceIn(0, rowBuilder.length)
                            if (end > 0) rowBuilder.delete(0, end)
                        }
                        else -> {
                            val start = altCursorCol.coerceIn(0, rowBuilder.length)
                            if (start < rowBuilder.length) rowBuilder.delete(start, rowBuilder.length)
                        }
                    }
                } else {
                    val lineStart = target.lastIndexOf('\n').let { if (it >= 0) it + 1 else 0 }
                    when (params) {
                        "2" -> {
                            if (lineStart < target.length) {
                                target.delete(lineStart, target.length)
                            }
                            normalCursorOffsetFromEnd = 0
                        }
                        "1" -> {
                            val cursorPos = (target.length - normalCursorOffsetFromEnd).coerceIn(lineStart, target.length)
                            if (cursorPos > lineStart) {
                                target.delete(lineStart, cursorPos)
                            }
                        }
                        else -> {
                            // "0" or "": erase from cursor to end of line
                            val cursorPos = (target.length - normalCursorOffsetFromEnd).coerceIn(lineStart, target.length)
                            if (cursorPos < target.length) {
                                target.delete(cursorPos, target.length)
                            }
                            normalCursorOffsetFromEnd = 0
                        }
                    }
                }
            }
            "h" -> {
                if (params == "?1049" || params == "?47" || params == "?1047") {
                    enterAlternateScreen(target)
                } else if (params == "?1") {
                    // DECCKM: Application Cursor Key Mode
                    isApplicationCursorKeys = true
                }
            }
            "l" -> {
                if (params == "?1049" || params == "?47" || params == "?1047") {
                    exitAlternateScreen(target)
                } else if (params == "?1") {
                    // DECCKM: Normal Cursor Key Mode
                    isApplicationCursorKeys = false
                }
            }
            "n" -> {
                if (params == "6") {
                    val r = (altCursorRow + 1)
                    val c = (altCursorCol + 1)
                    onSendTerminalResponse?.invoke("\u001B[${r};${c}R")
                } else if (params == "5") {
                    onSendTerminalResponse?.invoke("\u001B[0n")
                }
            }
            "c" -> {
                if (params.isEmpty() || params == "0") {
                    onSendTerminalResponse?.invoke("\u001B[?1;2c")
                }
            }
        }
    }

    private fun enterAlternateScreen(target: Editable) {
        if (!isAlternateScreen) {
            removeNormalCursor(target)
            isAlternateScreen = true
            mainScreenBackup = SpannableStringBuilder(target)
            initAltScreen(terminalRows)
            renderAltToTarget(target)
        }
    }

    private fun exitAlternateScreen(target: Editable) {
        if (isAlternateScreen) {
            isAlternateScreen = false
            target.clear()
            mainScreenBackup?.let { target.append(it) }
            mainScreenBackup = null
            altRows.clear()
            altCursorRow = 0
            altCursorCol = 0
            normalCursorOffsetFromEnd = 0
            hasCursorPlaceholder = false
        }
    }

    private fun initAltScreen(rows: Int) {
        altRows.clear()
        val count = rows.coerceIn(10, 100)
        for (i in 0 until count) {
            altRows.add(SpannableStringBuilder())
        }
        altCursorRow = 0
        altCursorCol = 0
    }

    private fun ensureAltRowExists(row: Int) {
        while (altRows.size <= row) {
            altRows.add(SpannableStringBuilder())
        }
    }

    private fun processAltChunk(chunk: String) {
        val segment = StringBuilder()
        for (i in chunk.indices) {
            val c = chunk[i]
            when (c) {
                '\r' -> {
                    if (segment.isNotEmpty()) {
                        writeAltSegment(segment.toString())
                        segment.setLength(0)
                    }
                    altCursorCol = 0
                }
                '\n' -> {
                    if (segment.isNotEmpty()) {
                        writeAltSegment(segment.toString())
                        segment.setLength(0)
                    }
                    altCursorRow = (altCursorRow + 1).coerceAtMost((terminalRows - 1).coerceAtLeast(0))
                    altCursorCol = 0
                }
                '\b' -> {
                    if (segment.isNotEmpty()) {
                        segment.deleteCharAt(segment.length - 1)
                    } else if (altCursorCol > 0) {
                        altCursorCol--
                    }
                }
                '\t' -> {
                    val spaces = 8 - ((altCursorCol + segment.length) % 8)
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
            writeAltSegment(segment.toString())
        }
    }

    private fun writeAltSegment(text: String) {
        ensureAltRowExists(altCursorRow)
        val rowBuilder = altRows[altCursorRow]
        if (altCursorCol > rowBuilder.length) {
            val pad = " ".repeat(altCursorCol - rowBuilder.length)
            rowBuilder.append(pad)
        }
        val insertPos = altCursorCol.coerceIn(0, rowBuilder.length)
        val overwriteLen = minOf(text.length, rowBuilder.length - insertPos)
        if (overwriteLen > 0) {
            rowBuilder.delete(insertPos, insertPos + overwriteLen)
        }
        insertStyled(rowBuilder, insertPos, text)
        altCursorCol += text.length
    }

    private fun renderAltToTarget(target: Editable) {
        val full = SpannableStringBuilder()
        val rCursor = altCursorRow.coerceIn(0, (terminalRows - 1).coerceAtLeast(0))
        val cCursor = altCursorCol.coerceAtLeast(0)

        for (i in altRows.indices) {
            if (i > 0) full.append("\n")
            val row = SpannableStringBuilder(altRows[i])
            if (i == rCursor) {
                if (cCursor < row.length) {
                    row.setSpan(
                        TerminalCursorSpan(theme.cursorColor),
                        cCursor,
                        cCursor + 1,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    row.setSpan(
                        TerminalCursorFgSpan(theme.backgroundColor),
                        cCursor,
                        cCursor + 1,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } else {
                    val pad = " ".repeat(cCursor - row.length)
                    row.append(pad)
                    val cursorIndex = row.length
                    row.append(" ")
                    row.setSpan(
                        TerminalCursorSpan(theme.cursorColor),
                        cursorIndex,
                        cursorIndex + 1,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            full.append(row)
        }
        target.replace(0, target.length, full)
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

    private fun removeNormalCursor(target: Editable) {
        if (hasCursorPlaceholder && target.isNotEmpty()) {
            target.delete(target.length - 1, target.length)
            hasCursorPlaceholder = false
        }
        val bgSpans = target.getSpans(0, target.length, TerminalCursorSpan::class.java)
        for (s in bgSpans) target.removeSpan(s)
        val fgSpans = target.getSpans(0, target.length, TerminalCursorFgSpan::class.java)
        for (s in fgSpans) target.removeSpan(s)
    }

    private fun applyNormalCursor(target: Editable) {
        val lineStart = target.lastIndexOf('\n').let { if (it >= 0) it + 1 else 0 }
        val lineLen = target.length - lineStart
        val offset = normalCursorOffsetFromEnd.coerceIn(0, lineLen)
        if (offset > 0) {
            val cursorIdx = target.length - offset
            if (cursorIdx in 0 until target.length) {
                target.setSpan(
                    TerminalCursorSpan(theme.cursorColor),
                    cursorIdx,
                    cursorIdx + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                target.setSpan(
                    TerminalCursorFgSpan(theme.backgroundColor),
                    cursorIdx,
                    cursorIdx + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        } else {
            val insertPos = target.length
            target.append(" ")
            target.setSpan(
                TerminalCursorSpan(theme.cursorColor),
                insertPos,
                insertPos + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            hasCursorPlaceholder = true
        }
    }

    private fun appendCleanChunk(target: Editable, chunk: String) {
        val clean = StringBuilder()
        var i = 0

        fun flushClean() {
            if (clean.isNotEmpty()) {
                val textStr = clean.toString()
                clean.setLength(0)
                if (normalCursorOffsetFromEnd > 0) {
                    val pos = (target.length - normalCursorOffsetFromEnd).coerceIn(0, target.length)
                    val overwriteLen = minOf(textStr.length, normalCursorOffsetFromEnd)
                    if (overwriteLen > 0) {
                        target.delete(pos, pos + overwriteLen)
                    }
                    insertStyled(target, pos, textStr)
                    normalCursorOffsetFromEnd = (normalCursorOffsetFromEnd - textStr.length).coerceAtLeast(0)
                } else {
                    appendStyled(target, textStr)
                    normalCursorOffsetFromEnd = 0
                }
            }
        }

        while (i < chunk.length) {
            val c = chunk[i]
            when {
                c == '\b' -> {
                    flushClean()
                    val isEraseSeq = i + 2 < chunk.length && chunk[i + 1] == ' ' && chunk[i + 2] == '\b'
                    if (isEraseSeq) {
                        val lineStart = target.lastIndexOf('\n').let { if (it >= 0) it + 1 else 0 }
                        val cursorPos = (target.length - normalCursorOffsetFromEnd).coerceIn(lineStart, target.length)
                        if (cursorPos > lineStart) {
                            target.delete(cursorPos - 1, cursorPos)
                        }
                        i += 2
                    } else {
                        val lineStart = target.lastIndexOf('\n').let { if (it >= 0) it + 1 else 0 }
                        val maxOffset = target.length - lineStart
                        if (normalCursorOffsetFromEnd < maxOffset) {
                            normalCursorOffsetFromEnd++
                        }
                    }
                }
                c == '\r' -> {
                    flushClean()
                    val lastNl = target.lastIndexOf('\n')
                    if (lastNl >= 0) {
                        target.delete(lastNl + 1, target.length)
                    } else {
                        target.clear()
                    }
                    normalCursorOffsetFromEnd = 0
                }
                c == '\n' -> {
                    flushClean()
                    appendStyled(target, "\n")
                    normalCursorOffsetFromEnd = 0
                }
                c == '\t' -> {
                    clean.append(c)
                }
                c < ' ' || c.code == 127 -> {
                    // Drop unprintable control characters
                }
                else -> {
                    clean.append(c)
                }
            }
            i++
        }

        flushClean()
    }

    private fun appendStyled(target: Editable, text: String) {
        insertStyled(target, target.length, text)
    }

    private fun insertStyled(target: Editable, position: Int, text: String) {
        if (text.isEmpty()) return
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
