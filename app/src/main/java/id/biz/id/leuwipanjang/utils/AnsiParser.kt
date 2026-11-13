package id.biz.id.leuwipanjang.utils

import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import id.biz.id.leuwipanjang.data.model.TerminalTheme
import id.biz.id.leuwipanjang.terminal.TerminalEmulator
import id.biz.id.leuwipanjang.terminal.TerminalEmulator.Companion.BOLD
import id.biz.id.leuwipanjang.terminal.TerminalEmulator.Companion.DEFAULT_COLOR
import id.biz.id.leuwipanjang.terminal.TerminalEmulator.Companion.FAINT
import id.biz.id.leuwipanjang.terminal.TerminalEmulator.Companion.HIDDEN
import id.biz.id.leuwipanjang.terminal.TerminalEmulator.Companion.INVERSE
import id.biz.id.leuwipanjang.terminal.TerminalEmulator.Companion.ITALIC
import id.biz.id.leuwipanjang.terminal.TerminalEmulator.Companion.RGB_FLAG
import id.biz.id.leuwipanjang.terminal.TerminalEmulator.Companion.STRIKE
import id.biz.id.leuwipanjang.terminal.TerminalEmulator.Companion.UNDERLINE

/**
 * Shows a [TerminalEmulator] in a TextView's Editable.
 *
 * The Editable holds the scrollback (lines that scrolled off the top, never changed again)
 * followed by the live screen, which is re-rendered from the emulator's grid after every
 * chunk of output. While a full-screen program uses the alternate screen, only that screen
 * is shown and the scrollback is kept aside until it exits.
 */
class AnsiParser(private var theme: TerminalTheme) {

    var onTitleChanged: ((String) -> Unit)? = null
    var onSendTerminalResponse: ((String) -> Unit)? = null

    /** Kept for compatibility; the emulator clears its own screen now. */
    var onClearScreen: (() -> Unit)? = null

    private val emulator = TerminalEmulator().apply {
        onTitle = { onTitleChanged?.invoke(it) }
        onResponse = { onSendTerminalResponse?.invoke(it) }
    }

    val terminalCols: Int get() = emulator.cols
    val terminalRows: Int get() = emulator.rows
    val isAlternateScreenActive: Boolean get() = emulator.isAlternateScreen
    val isApplicationCursorKeysActive: Boolean get() = emulator.applicationCursorKeys
    val isBracketedPasteActive: Boolean get() = emulator.bracketedPaste

    // Visual cursor (also matched when the selection copy strips it)
    class TerminalCursorSpan(color: Int) : BackgroundColorSpan(color)
    class TerminalCursorFgSpan(color: Int) : ForegroundColorSpan(color)

    /**
     * The TextView's paint. When set, characters drawn from a fallback font with a different
     * width are scaled to exactly one cell, so columns stay aligned.
     */
    var measurePaint: android.text.TextPaint? = null
    private val glyphWidths = HashMap<Char, Float>()
    private var glyphWidthsKey = 0f
    private var cellWidth = 0f

    /** Where the live screen starts in the Editable; everything before it is scrollback. */
    private var screenStart = 0
    private var renderedAlternate = false

    /** Main-screen scrollback set aside while the alternate screen is shown. */
    private var savedScrollback: SpannableStringBuilder? = null

    init {
        applyThemeColors()
    }

    fun setTheme(newTheme: TerminalTheme) {
        theme = newTheme
        applyThemeColors()
    }

    private fun applyThemeColors() {
        emulator.defaultForegroundRgb = theme.foregroundColor and 0xFFFFFF
        emulator.defaultBackgroundRgb = theme.backgroundColor and 0xFFFFFF
    }

    /** Resizes the grid; call [refresh] afterwards to show the result. */
    fun setTerminalDimensions(cols: Int, rows: Int) {
        emulator.resize(cols.coerceIn(20, 240), rows.coerceIn(10, 100))
    }

    fun parse(rawText: String): CharSequence {
        val builder = SpannableStringBuilder()
        appendTo(builder, rawText)
        return builder
    }

    fun appendTo(target: Editable, rawText: String) {
        emulator.append(rawText)
        refresh(target)
    }

    /** Brings [target] up to date with the emulator. */
    fun refresh(target: Editable) {
        if (screenStart > target.length) screenStart = 0 // cleared from outside

        if (emulator.isAlternateScreen != renderedAlternate) {
            if (emulator.isAlternateScreen) {
                savedScrollback = SpannableStringBuilder(target.subSequence(0, screenStart))
                screenStart = 0
            } else {
                val restored = savedScrollback ?: SpannableStringBuilder()
                removeStyleSpans(target, 0)
                target.replace(0, target.length, restored)
                screenStart = restored.length
                savedScrollback = null
            }
            renderedAlternate = emulator.isAlternateScreen
        }

        if (emulator.consumeScrollbackCleared()) {
            if (renderedAlternate) {
                savedScrollback?.clear()
            } else {
                target.delete(0, screenStart)
                screenStart = 0
            }
        }

        val committed = SpannableStringBuilder()
        for (row in emulator.drainScrollback()) {
            appendRow(committed, row, -1)
            committed.append('\n')
        }

        val screen = renderScreen()
        if (renderedAlternate) {
            // Rows leaving the main screen while the alternate one is shown (resize).
            if (committed.isNotEmpty()) savedScrollback?.append(committed)
            removeStyleSpans(target, 0)
            target.replace(0, target.length, screen)
            screenStart = 0
        } else {
            committed.append(screen)
            removeStyleSpans(target, screenStart)
            target.replace(screenStart, target.length, committed)
            screenStart += committed.length - screen.length
            trimScrollback(target)
        }
    }

    /**
     * Removes the colour/cursor spans of the text from [from] on before it is replaced.
     * Editable.replace() does not drop spans lying inside the replaced range: it stretches
     * them over the new text (a one-cell cursor would then cover the whole screen). Only
     * character styles are touched, not the TextView's own layout watchers.
     */
    private fun removeStyleSpans(target: Editable, from: Int) {
        for (span in target.getSpans(from, target.length, android.text.style.CharacterStyle::class.java)) {
            if (target.getSpanStart(span) >= from) target.removeSpan(span)
        }
    }

    private fun renderScreen(): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        val rows = emulator.screen
        // Show rows down to the cursor or the last row with content; the blank rows under
        // them would only add empty space below the prompt.
        var last = if (emulator.cursorVisible) emulator.cursorY else 0
        for (i in rows.indices.reversed()) {
            if (rows[i].contentLength() > 0) {
                last = maxOf(last, i)
                break
            }
        }
        val cursorRow = if (emulator.cursorVisible) emulator.cursorY else -1
        for (i in 0..last.coerceAtMost(rows.size - 1)) {
            if (i > 0) out.append('\n')
            appendRow(out, rows[i], if (i == cursorRow) emulator.cursorX else -1)
        }
        return out
    }

    /** Appends one grid row with its colours; [cursorCol] >= 0 also draws the cursor. */
    private fun appendRow(out: SpannableStringBuilder, row: TerminalEmulator.Row, cursorCol: Int) {
        val start = out.length
        val length = maxOf(row.contentLength(), cursorCol + 1)
        var col = 0
        while (col < length) {
            val style = if (col < row.size) row.styles[col] else TerminalEmulator.DEFAULT_STYLE
            var end = col + 1
            while (end < length && (if (end < row.size) row.styles[end] else TerminalEmulator.DEFAULT_STYLE) === style) end++
            val runStart = out.length
            for (i in col until end) out.append(if (i < row.size) row.chars[i] else ' ')
            applyStyle(out, runStart, out.length, style)
            col = end
        }
        fitWideGlyphs(out, start, row, length)
        if (cursorCol >= 0) {
            val idx = start + cursorCol
            out.setSpan(TerminalCursorSpan(theme.cursorColor), idx, idx + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(TerminalCursorFgSpan(theme.backgroundColor), idx, idx + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun fitWideGlyphs(out: SpannableStringBuilder, start: Int, row: TerminalEmulator.Row, length: Int) {
        val paint = measurePaint ?: return
        val key = paint.textSize // the typeface is fixed; widths change with the font size
        if (key != glyphWidthsKey) {
            glyphWidths.clear()
            glyphWidthsKey = key
            cellWidth = paint.measureText("M")
        }
        if (cellWidth <= 0f) return
        for (i in 0 until minOf(length, row.size)) {
            val c = row.chars[i]
            if (c.code < 0x80 || Character.isSurrogate(c)) continue
            val w = glyphWidths.getOrPut(c) { paint.measureText(c.toString()) }
            if (w > 0f && kotlin.math.abs(w - cellWidth) > 0.5f) {
                out.setSpan(android.text.style.ScaleXSpan(cellWidth / w), start + i, start + i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun applyStyle(out: SpannableStringBuilder, start: Int, end: Int, style: TerminalEmulator.Style) {
        if (style === TerminalEmulator.DEFAULT_STYLE || start >= end) return
        var fg = resolveColor(style.fg) ?: theme.foregroundColor
        var bg = resolveColor(style.bg)
        if (style.has(BOLD) && style.fg in 0..7) fg = getBrightColor(style.fg) // bold = bright, like xterm
        if (style.has(INVERSE)) {
            val newBg = fg
            fg = bg ?: theme.backgroundColor
            bg = newBg
        }
        if (style.has(FAINT)) fg = Color.argb(0x99, Color.red(fg), Color.green(fg), Color.blue(fg))
        if (style.has(HIDDEN)) fg = bg ?: theme.backgroundColor

        val flag = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        if (fg != theme.foregroundColor) out.setSpan(ForegroundColorSpan(fg), start, end, flag)
        if (bg != null) out.setSpan(BackgroundColorSpan(bg), start, end, flag)
        val typeface = when {
            style.has(BOLD) && style.has(ITALIC) -> Typeface.BOLD_ITALIC
            style.has(BOLD) -> Typeface.BOLD
            style.has(ITALIC) -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        if (typeface != Typeface.NORMAL) out.setSpan(StyleSpan(typeface), start, end, flag)
        if (style.has(UNDERLINE)) out.setSpan(UnderlineSpan(), start, end, flag)
        if (style.has(STRIKE)) out.setSpan(StrikethroughSpan(), start, end, flag)
    }

    private fun resolveColor(spec: Int): Int? = when {
        spec == DEFAULT_COLOR -> null
        spec and RGB_FLAG != 0 -> Color.rgb(spec shr 16 and 0xFF, spec shr 8 and 0xFF, spec and 0xFF)
        else -> getXtermColor(spec)
    }

    private fun trimScrollback(target: Editable) {
        if (screenStart <= MAX_SCROLLBACK_CHARS) return
        val cut = target.indexOf('\n', screenStart - TRIM_SCROLLBACK_TO).let { if (it in 0 until screenStart) it + 1 else 0 }
        if (cut > 0) {
            target.delete(0, cut)
            screenStart -= cut
        }
    }

    private fun Editable.indexOf(c: Char, from: Int): Int {
        for (i in from.coerceAtLeast(0) until length) if (this[i] == c) return i
        return -1
    }

    private fun getStandardColor(index: Int): Int = when (index) {
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

    private fun getBrightColor(index: Int): Int = when (index) {
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

    private fun getXtermColor(index: Int): Int {
        if (index in 0..7) return getStandardColor(index)
        if (index in 8..15) return getBrightColor(index - 8)
        if (index in 16..231) {
            val i = index - 16
            fun comp(v: Int) = if (v == 0) 0 else (55 + v * 40)
            return Color.rgb(comp(i / 36), comp((i % 36) / 6), comp(i % 6))
        }
        if (index in 232..255) {
            val gray = 8 + (index - 232) * 10
            return Color.rgb(gray, gray, gray)
        }
        return theme.foregroundColor
    }

    companion object {
        private const val MAX_SCROLLBACK_CHARS = 200_000
        private const val TRIM_SCROLLBACK_TO = 150_000
    }
}
