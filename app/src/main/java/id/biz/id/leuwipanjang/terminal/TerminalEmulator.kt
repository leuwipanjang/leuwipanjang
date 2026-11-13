package id.biz.id.leuwipanjang.terminal

/**
 * A screen-grid terminal emulator (the xterm subset used by shells and TUI programs).
 *
 * It keeps what a real terminal keeps: a [rows] x [cols] grid of cells with a cursor, a
 * separate alternate screen, a scroll region, and lines that scroll off the top of the main
 * screen become scrollback. Programs that redraw by moving the cursor (Ink/React CLIs, Bubble
 * Tea, ncurses, readline) therefore end up with the same picture they would get in xterm.
 *
 * Pure Kotlin with no Android dependency, so it can be tested on the JVM; rendering to a
 * TextView lives in [id.biz.id.leuwipanjang.utils.AnsiParser].
 */
class TerminalEmulator(cols: Int = 80, rows: Int = 24) {

    /** Colours: [DEFAULT_COLOR], 0-255 palette index, or [RGB_FLAG] or 0xRRGGBB. */
    data class Style(val fg: Int = DEFAULT_COLOR, val bg: Int = DEFAULT_COLOR, val flags: Int = 0) {
        fun has(flag: Int) = flags and flag != 0
    }

    class Row(cols: Int) {
        var chars = CharArray(cols) { ' ' }
            private set
        var styles: Array<Style> = Array(cols) { DEFAULT_STYLE }
            private set

        /** True when the text continues on the next row because it was wrapped, not ended. */
        var wrapped = false

        val size get() = chars.size

        fun resize(newCols: Int) {
            if (newCols == chars.size) return
            val old = chars.size
            chars = chars.copyOf(newCols)
            styles = styles.copyOf(newCols).let { arr ->
                Array(newCols) { i -> arr[i] ?: DEFAULT_STYLE }
            }
            for (i in old until newCols) chars[i] = ' '
        }

        fun clear(from: Int, to: Int, style: Style) {
            for (i in from.coerceAtLeast(0) until to.coerceAtMost(chars.size)) {
                chars[i] = ' '
                styles[i] = style
            }
        }

        fun isBlank(): Boolean {
            for (i in chars.indices) {
                if (chars[i] != ' ' || styles[i].bg != DEFAULT_COLOR || styles[i].has(INVERSE)) return false
            }
            return true
        }

        /** Number of cells up to the last one that shows something. */
        fun contentLength(): Int {
            for (i in chars.indices.reversed()) {
                if (chars[i] != ' ' || styles[i].bg != DEFAULT_COLOR || styles[i].has(INVERSE)) return i + 1
            }
            return 0
        }

        fun text(): String = String(chars, 0, contentLength())
    }

    private class SavedCursor(
        val x: Int, val y: Int, val style: Style, val pendingWrap: Boolean,
        val originMode: Boolean, val g0Graphics: Boolean, val g1Graphics: Boolean, val useG1: Boolean
    )

    var cols = cols.coerceAtLeast(1)
        private set
    var rows = rows.coerceAtLeast(1)
        private set

    private var mainScreen = MutableList(this.rows) { Row(this.cols) }
    private var altScreen = MutableList(this.rows) { Row(this.cols) }

    var isAlternateScreen = false
        private set
    val screen: List<Row> get() = if (isAlternateScreen) altScreen else mainScreen
    private val buffer: MutableList<Row> get() = if (isAlternateScreen) altScreen else mainScreen

    var cursorX = 0
        private set
    var cursorY = 0
        private set
    private var pendingWrap = false

    private var scrollTop = 0
    private var scrollBottom = this.rows - 1

    private var style = DEFAULT_STYLE

    // Modes
    var cursorVisible = true
        private set
    var applicationCursorKeys = false
        private set
    var bracketedPaste = false
        private set
    private var autoWrap = true
    private var originMode = false
    private var insertMode = false

    // Character sets (DEC special graphics for line drawing)
    private var g0Graphics = false
    private var g1Graphics = false
    private var useG1 = false

    private var savedMain: SavedCursor? = null
    private var savedAlt: SavedCursor? = null
    private var lastPrinted: Char = ' '

    /** Rows that scrolled off the top of the main screen since the last [drainScrollback]. */
    private val newScrollback = ArrayList<Row>()

    /** Set by ED 3 / RIS; the renderer drops its scrollback once and clears the flag. */
    var scrollbackCleared = false
        private set

    var onTitle: ((String) -> Unit)? = null
    var onResponse: ((String) -> Unit)? = null

    /** Default colours as 0xRRGGBB, reported to programs that ask (OSC 10 / 11). */
    var defaultForegroundRgb = 0xE5E7EB
    var defaultBackgroundRgb = 0x0F172A

    // ---- Parser state ----
    private enum class State { GROUND, ESC, ESC_INTERMEDIATE, CSI, OSC, OSC_ESC, STRING, STRING_ESC }

    private var state = State.GROUND
    private val csiParams = StringBuilder()
    private var csiPrivate: Char? = null
    private val intermediates = StringBuilder()
    private val oscBuffer = StringBuilder()

    fun drainScrollback(): List<Row> {
        if (newScrollback.isEmpty()) return emptyList()
        val rows = ArrayList(newScrollback)
        newScrollback.clear()
        return rows
    }

    fun consumeScrollbackCleared(): Boolean {
        val cleared = scrollbackCleared
        scrollbackCleared = false
        return cleared
    }

    // ---------------------------------------------------------------- input

    fun append(text: CharSequence) {
        for (i in 0 until text.length) process(text[i])
    }

    private fun process(c: Char) {
        when (state) {
            State.GROUND -> when {
                c == ESC -> enterEscape()
                c.code < 0x20 || c.code == 0x7F -> executeControl(c)
                c.code in 0x80..0x9F -> Unit // C1 controls: ignored
                else -> print(c)
            }
            State.ESC -> when {
                c == '[' -> {
                    state = State.CSI
                    csiParams.setLength(0)
                    csiPrivate = null
                    intermediates.setLength(0)
                }
                c == ']' -> {
                    state = State.OSC
                    oscBuffer.setLength(0)
                }
                c == 'P' || c == 'X' || c == '^' || c == '_' -> state = State.STRING
                c.code in 0x20..0x2F -> {
                    intermediates.append(c)
                    state = State.ESC_INTERMEDIATE
                }
                c == ESC -> enterEscape()
                c.code < 0x20 -> executeControl(c)
                else -> {
                    state = State.GROUND
                    escDispatch(c)
                }
            }
            State.ESC_INTERMEDIATE -> when {
                c.code in 0x20..0x2F -> intermediates.append(c)
                c == ESC -> enterEscape()
                c.code < 0x20 -> executeControl(c)
                else -> {
                    state = State.GROUND
                    escIntermediateDispatch(intermediates.toString(), c)
                }
            }
            State.CSI -> when {
                c in '0'..'9' || c == ';' || c == ':' -> {
                    if (csiParams.length < MAX_PARAMS_LENGTH) csiParams.append(c)
                }
                c in '<'..'?' -> {
                    if (csiParams.isEmpty() && intermediates.isEmpty()) csiPrivate = c
                }
                c.code in 0x20..0x2F -> intermediates.append(c)
                c.code in 0x40..0x7E -> {
                    state = State.GROUND
                    csiDispatch(c)
                }
                c == ESC -> enterEscape()
                c.code < 0x20 -> executeControl(c)
                else -> state = State.GROUND
            }
            State.OSC -> when {
                c == BEL -> {
                    state = State.GROUND
                    oscDispatch(oscBuffer.toString(), BEL.toString())
                }
                c == ESC -> state = State.OSC_ESC
                else -> if (oscBuffer.length < MAX_OSC_LENGTH) oscBuffer.append(c)
            }
            State.OSC_ESC -> {
                state = State.GROUND
                oscDispatch(oscBuffer.toString(), "$ESC\\")
                if (c != '\\') process(c)
            }
            State.STRING -> when (c) {
                BEL -> state = State.GROUND
                ESC -> state = State.STRING_ESC
                else -> Unit
            }
            State.STRING_ESC -> {
                state = if (c == '\\') State.GROUND else State.STRING
            }
        }
    }

    private fun enterEscape() {
        state = State.ESC
        intermediates.setLength(0)
    }

    private fun executeControl(c: Char) {
        when (c.code) {
            0x08 -> { // BS
                pendingWrap = false
                if (cursorX > 0) cursorX--
            }
            0x09 -> { // HT
                cursorX = nextTabStop(cursorX)
            }
            0x0A, 0x0B, 0x0C -> lineFeed() // LF / VT / FF (no implicit CR, as in xterm)
            0x0D -> { // CR
                cursorX = 0
                pendingWrap = false
            }
            0x0E -> useG1 = true // SO
            0x0F -> useG1 = false // SI
            else -> Unit // BEL and the rest
        }
    }

    // ---------------------------------------------------------------- printing

    private fun print(raw: Char) {
        val c = if (if (useG1) g1Graphics else g0Graphics) decGraphics(raw) else raw
        if (pendingWrap && autoWrap) {
            buffer[cursorY].wrapped = true
            cursorX = 0
            lineFeed()
        }
        pendingWrap = false
        val row = buffer[cursorY]
        if (insertMode) insertCells(row, cursorX, 1)
        row.chars[cursorX] = c
        row.styles[cursorX] = style
        lastPrinted = c
        if (cursorX >= cols - 1) {
            cursorX = cols - 1
            pendingWrap = autoWrap
        } else {
            cursorX++
        }
    }

    private fun decGraphics(c: Char): Char = when (c) {
        '`' -> '◆'; 'a' -> '▒'; 'f' -> '°'; 'g' -> '±'; 'j' -> '┘'; 'k' -> '┐'; 'l' -> '┌'
        'm' -> '└'; 'n' -> '┼'; 'o' -> '⎺'; 'p' -> '⎻'; 'q' -> '─'; 'r' -> '⎼'; 's' -> '⎽'
        't' -> '├'; 'u' -> '┤'; 'v' -> '┴'; 'w' -> '┬'; 'x' -> '│'; 'y' -> '≤'; 'z' -> '≥'
        '{' -> 'π'; '|' -> '≠'; '}' -> '£'; '~' -> '·'
        else -> c
    }

    private fun blankStyle(): Style =
        if (style.bg == DEFAULT_COLOR) DEFAULT_STYLE else Style(bg = style.bg) // background colour erase

    private fun insertCells(row: Row, at: Int, count: Int) {
        val n = count.coerceAtMost(cols - at)
        if (n <= 0) return
        for (i in cols - 1 downTo at + n) {
            row.chars[i] = row.chars[i - n]
            row.styles[i] = row.styles[i - n]
        }
        row.clear(at, at + n, blankStyle())
    }

    private fun deleteCells(row: Row, at: Int, count: Int) {
        val n = count.coerceAtMost(cols - at)
        if (n <= 0) return
        for (i in at until cols - n) {
            row.chars[i] = row.chars[i + n]
            row.styles[i] = row.styles[i + n]
        }
        row.clear(cols - n, cols, blankStyle())
    }

    // ---------------------------------------------------------------- scrolling

    private fun lineFeed() {
        pendingWrap = false
        if (cursorY == scrollBottom) {
            scrollUp(1)
        } else if (cursorY < rows - 1) {
            cursorY++
        }
    }

    private fun reverseIndex() {
        pendingWrap = false
        if (cursorY == scrollTop) {
            scrollDown(1)
        } else if (cursorY > 0) {
            cursorY--
        }
    }

    private fun scrollUp(count: Int) {
        val n = count.coerceAtMost(scrollBottom - scrollTop + 1)
        repeat(n) {
            val removed = buffer.removeAt(scrollTop)
            if (!isAlternateScreen && scrollTop == 0) {
                newScrollback.add(removed)
                if (newScrollback.size > MAX_PENDING_SCROLLBACK) newScrollback.removeAt(0)
            }
            buffer.add(scrollBottom, Row(cols).also { it.clear(0, cols, blankStyle()) })
        }
    }

    private fun scrollDown(count: Int) {
        val n = count.coerceAtMost(scrollBottom - scrollTop + 1)
        repeat(n) {
            buffer.removeAt(scrollBottom)
            buffer.add(scrollTop, Row(cols).also { it.clear(0, cols, blankStyle()) })
        }
    }

    // ---------------------------------------------------------------- ESC

    private fun escDispatch(c: Char) {
        when (c) {
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> lineFeed()
            'E' -> {
                cursorX = 0
                lineFeed()
            }
            'M' -> reverseIndex()
            'c' -> fullReset()
            else -> Unit // '=', '>' keypad modes, 'H' tab set, '\\' ST...
        }
    }

    private fun escIntermediateDispatch(inter: String, c: Char) {
        when (inter) {
            "(" -> g0Graphics = c == '0'
            ")" -> g1Graphics = c == '0'
            "#" -> if (c == '8') { // DECALN: fill screen with E
                for (row in buffer) for (i in 0 until cols) {
                    row.chars[i] = 'E'
                    row.styles[i] = DEFAULT_STYLE
                }
            }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- CSI

    private fun params(): List<Int?> {
        if (csiParams.isEmpty()) return emptyList()
        return csiParams.split(';').map { it.substringBefore(':').toIntOrNull() }
    }

    private fun csiDispatch(final: Char) {
        val p = params()
        fun arg(i: Int, default: Int = 1): Int {
            val v = p.getOrNull(i) ?: return default
            return if (v == 0 && default == 1) 1 else v
        }
        val inter = intermediates.toString()

        if (csiPrivate == null && inter.isEmpty()) {
            when (final) {
                '@' -> { pendingWrap = false; insertCells(buffer[cursorY], cursorX, arg(0)) }
                'A' -> cursorUp(arg(0))
                'B', 'e' -> cursorDown(arg(0))
                'C', 'a' -> { pendingWrap = false; cursorX = (cursorX + arg(0)).coerceAtMost(cols - 1) }
                'D' -> { pendingWrap = false; cursorX = (cursorX - arg(0)).coerceAtLeast(0) }
                'E' -> { cursorDown(arg(0)); cursorX = 0 }
                'F' -> { cursorUp(arg(0)); cursorX = 0 }
                'G', '`' -> { pendingWrap = false; cursorX = (arg(0) - 1).coerceIn(0, cols - 1) }
                'H', 'f' -> setCursor(arg(0) - 1, arg(1) - 1)
                'I' -> repeat(arg(0)) { cursorX = nextTabStop(cursorX) }
                'Z' -> repeat(arg(0)) { cursorX = ((cursorX - 1) / 8 * 8).coerceAtLeast(0) }
                'J' -> eraseInDisplay(arg(0, 0))
                'K' -> eraseInLine(arg(0, 0))
                'L' -> insertLines(arg(0))
                'M' -> deleteLines(arg(0))
                'P' -> { pendingWrap = false; deleteCells(buffer[cursorY], cursorX, arg(0)) }
                'S' -> scrollUp(arg(0))
                'T' -> if (p.size <= 1) scrollDown(arg(0))
                'X' -> {
                    pendingWrap = false
                    buffer[cursorY].clear(cursorX, cursorX + arg(0), blankStyle())
                }
                'b' -> repeat(arg(0).coerceAtMost(cols * rows)) { print(lastPrinted) }
                'c' -> if (arg(0, 0) == 0) onResponse?.invoke("$ESC[?1;2c")
                'd' -> setCursor(arg(0) - 1, cursorX, keepColumn = true)
                'h' -> setAnsiMode(p, true)
                'l' -> setAnsiMode(p, false)
                'm' -> selectGraphicRendition(csiParams.toString())
                'n' -> deviceStatusReport(arg(0, 0))
                'r' -> setScrollRegion(arg(0, 1), arg(1, rows))
                's' -> saveCursor()
                'u' -> restoreCursor()
                't' -> if (arg(0, 0) == 18) onResponse?.invoke("$ESC[8;$rows;${cols}t")
                else -> Unit
            }
            return
        }

        when {
            csiPrivate == '?' && inter.isEmpty() && (final == 'h' || final == 'l') ->
                setDecMode(p, final == 'h')
            csiPrivate == '>' && inter.isEmpty() && final == 'c' ->
                onResponse?.invoke("$ESC[>1;10;0c")
            csiPrivate == null && inter == "!" && final == 'p' -> softReset()
            else -> Unit // DECSCUSR (" q"), XTVERSION (">q"), kitty keyboard ("?u", ">u"), ...
        }
    }

    private fun cursorUp(n: Int) {
        pendingWrap = false
        val limit = if (cursorY >= scrollTop) scrollTop else 0
        cursorY = (cursorY - n).coerceAtLeast(limit)
    }

    private fun cursorDown(n: Int) {
        pendingWrap = false
        val limit = if (cursorY <= scrollBottom) scrollBottom else rows - 1
        cursorY = (cursorY + n).coerceAtMost(limit)
    }

    private fun setCursor(row: Int, col: Int, keepColumn: Boolean = false) {
        pendingWrap = false
        val top = if (originMode) scrollTop else 0
        val bottom = if (originMode) scrollBottom else rows - 1
        cursorY = (row + top).coerceIn(top, bottom)
        if (!keepColumn) cursorX = col.coerceIn(0, cols - 1)
    }

    private fun nextTabStop(x: Int) = ((x / 8 + 1) * 8).coerceAtMost(cols - 1)

    private fun eraseInDisplay(mode: Int) {
        pendingWrap = false
        val blank = blankStyle()
        when (mode) {
            0 -> {
                buffer[cursorY].clear(cursorX, cols, blank)
                for (y in cursorY + 1 until rows) buffer[y].clear(0, cols, blank)
            }
            1 -> {
                for (y in 0 until cursorY) buffer[y].clear(0, cols, blank)
                buffer[cursorY].clear(0, cursorX + 1, blank)
            }
            2 -> for (y in 0 until rows) {
                buffer[y].clear(0, cols, blank)
                buffer[y].wrapped = false
            }
            3 -> {
                newScrollback.clear()
                scrollbackCleared = true
            }
        }
    }

    private fun eraseInLine(mode: Int) {
        pendingWrap = false
        val row = buffer[cursorY]
        val blank = blankStyle()
        when (mode) {
            0 -> row.clear(cursorX, cols, blank)
            1 -> row.clear(0, cursorX + 1, blank)
            2 -> row.clear(0, cols, blank)
        }
    }

    private fun insertLines(count: Int) {
        if (cursorY < scrollTop || cursorY > scrollBottom) return
        val n = count.coerceAtMost(scrollBottom - cursorY + 1)
        repeat(n) {
            buffer.removeAt(scrollBottom)
            buffer.add(cursorY, Row(cols).also { it.clear(0, cols, blankStyle()) })
        }
        cursorX = 0
        pendingWrap = false
    }

    private fun deleteLines(count: Int) {
        if (cursorY < scrollTop || cursorY > scrollBottom) return
        val n = count.coerceAtMost(scrollBottom - cursorY + 1)
        repeat(n) {
            buffer.removeAt(cursorY)
            buffer.add(scrollBottom, Row(cols).also { it.clear(0, cols, blankStyle()) })
        }
        cursorX = 0
        pendingWrap = false
    }

    private fun setScrollRegion(top: Int, bottom: Int) {
        val t = (top - 1).coerceIn(0, rows - 1)
        val b = (bottom - 1).coerceIn(0, rows - 1)
        if (t < b) {
            scrollTop = t
            scrollBottom = b
        } else {
            scrollTop = 0
            scrollBottom = rows - 1
        }
        setCursor(0, 0)
    }

    private fun deviceStatusReport(code: Int) {
        when (code) {
            5 -> onResponse?.invoke("$ESC[0n")
            6 -> {
                val row = if (originMode) cursorY - scrollTop else cursorY
                onResponse?.invoke("$ESC[${row + 1};${cursorX + 1}R")
            }
        }
    }

    private fun setAnsiMode(p: List<Int?>, enable: Boolean) {
        for (mode in p) if (mode == 4) insertMode = enable
    }

    private fun setDecMode(p: List<Int?>, enable: Boolean) {
        for (mode in p) {
            when (mode) {
                1 -> applicationCursorKeys = enable
                6 -> {
                    originMode = enable
                    setCursor(0, 0)
                }
                7 -> autoWrap = enable
                25 -> cursorVisible = enable
                47, 1047 -> switchScreen(enable, saveRestoreCursor = false)
                1048 -> if (enable) saveCursor() else restoreCursor()
                1049 -> switchScreen(enable, saveRestoreCursor = true)
                2004 -> bracketedPaste = enable
                else -> Unit // mouse reporting, focus events, synchronized output, ...
            }
        }
    }

    private fun switchScreen(toAlternate: Boolean, saveRestoreCursor: Boolean) {
        if (toAlternate == isAlternateScreen) return
        if (toAlternate) {
            if (saveRestoreCursor) saveCursor()
            isAlternateScreen = true
            altScreen = MutableList(rows) { Row(cols) }
        } else {
            isAlternateScreen = false
            if (saveRestoreCursor) restoreCursor()
        }
        pendingWrap = false
    }

    private fun saveCursor() {
        val saved = SavedCursor(cursorX, cursorY, style, pendingWrap, originMode, g0Graphics, g1Graphics, useG1)
        if (isAlternateScreen) savedAlt = saved else savedMain = saved
    }

    private fun restoreCursor() {
        val saved = (if (isAlternateScreen) savedAlt else savedMain) ?: run {
            cursorX = 0
            cursorY = 0
            pendingWrap = false
            return
        }
        cursorX = saved.x.coerceIn(0, cols - 1)
        cursorY = saved.y.coerceIn(0, rows - 1)
        style = saved.style
        pendingWrap = saved.pendingWrap
        originMode = saved.originMode
        g0Graphics = saved.g0Graphics
        g1Graphics = saved.g1Graphics
        useG1 = saved.useG1
    }

    private fun softReset() {
        cursorVisible = true
        originMode = false
        autoWrap = true
        insertMode = false
        applicationCursorKeys = false
        scrollTop = 0
        scrollBottom = rows - 1
        style = DEFAULT_STYLE
        g0Graphics = false
        g1Graphics = false
        useG1 = false
        savedMain = null
        savedAlt = null
    }

    private fun fullReset() {
        softReset()
        bracketedPaste = false
        isAlternateScreen = false
        mainScreen = MutableList(rows) { Row(cols) }
        altScreen = MutableList(rows) { Row(cols) }
        cursorX = 0
        cursorY = 0
        pendingWrap = false
        newScrollback.clear()
        scrollbackCleared = true
    }

    // ---------------------------------------------------------------- SGR

    private fun selectGraphicRendition(raw: String) {
        if (raw.isEmpty()) {
            style = DEFAULT_STYLE
            return
        }
        val groups = raw.split(';')
        var fg = style.fg
        var bg = style.bg
        var flags = style.flags
        var i = 0
        fun num(s: String?) = s?.toIntOrNull()

        /** Parses 38/48 colours, both "38;5;n" / "38;2;r;g;b" and "38:5:n" / "38:2::r:g:b". */
        fun extendedColor(group: String): Int? {
            if (group.contains(':')) {
                val sub = group.split(':')
                return when (num(sub.getOrNull(1))) {
                    5 -> num(sub.getOrNull(2))?.coerceIn(0, 255)
                    2 -> {
                        val rgb = sub.drop(2).let { if (it.size >= 4) it.drop(1) else it }
                        rgbColor(num(rgb.getOrNull(0)), num(rgb.getOrNull(1)), num(rgb.getOrNull(2)))
                    }
                    else -> null
                }
            }
            return when (num(groups.getOrNull(i + 1))) {
                5 -> {
                    val idx = num(groups.getOrNull(i + 2))
                    i += 2
                    idx?.coerceIn(0, 255)
                }
                2 -> {
                    val c = rgbColor(num(groups.getOrNull(i + 2)), num(groups.getOrNull(i + 3)), num(groups.getOrNull(i + 4)))
                    i += 4
                    c
                }
                else -> null
            }
        }

        while (i < groups.size) {
            val group = groups[i]
            val code = num(group.substringBefore(':')) ?: if (group.isEmpty()) 0 else -1
            when (code) {
                0 -> { fg = DEFAULT_COLOR; bg = DEFAULT_COLOR; flags = 0 }
                1 -> flags = flags or BOLD
                2 -> flags = flags or FAINT
                3 -> flags = flags or ITALIC
                4 -> flags = if (group == "4:0") flags and UNDERLINE.inv() else flags or UNDERLINE
                7 -> flags = flags or INVERSE
                8 -> flags = flags or HIDDEN
                9 -> flags = flags or STRIKE
                21 -> flags = flags or UNDERLINE
                22 -> flags = flags and (BOLD or FAINT).inv()
                23 -> flags = flags and ITALIC.inv()
                24 -> flags = flags and UNDERLINE.inv()
                27 -> flags = flags and INVERSE.inv()
                28 -> flags = flags and HIDDEN.inv()
                29 -> flags = flags and STRIKE.inv()
                in 30..37 -> fg = code - 30
                38 -> extendedColor(group)?.let { fg = it }
                39 -> fg = DEFAULT_COLOR
                in 40..47 -> bg = code - 40
                48 -> extendedColor(group)?.let { bg = it }
                49 -> bg = DEFAULT_COLOR
                in 90..97 -> fg = code - 90 + 8
                in 100..107 -> bg = code - 100 + 8
                else -> Unit
            }
            i++
        }
        style = if (fg == DEFAULT_COLOR && bg == DEFAULT_COLOR && flags == 0) DEFAULT_STYLE else Style(fg, bg, flags)
    }

    private fun rgbColor(r: Int?, g: Int?, b: Int?): Int? {
        if (r == null || g == null || b == null) return null
        return RGB_FLAG or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }

    // ---------------------------------------------------------------- OSC

    private fun oscDispatch(content: String, terminator: String) {
        val code = content.substringBefore(';')
        val value = content.substringAfter(';', "")
        when (code) {
            "0", "2" -> if (value.isNotBlank()) onTitle?.invoke(value)
            "10" -> if (value == "?") onResponse?.invoke("$ESC]10;${xColor(defaultForegroundRgb)}$terminator")
            "11" -> if (value == "?") onResponse?.invoke("$ESC]11;${xColor(defaultBackgroundRgb)}$terminator")
        }
    }

    private fun xColor(rgb: Int): String {
        fun c(v: Int) = "%02x%02x".format(v, v)
        return "rgb:${c(rgb shr 16 and 0xFF)}/${c(rgb shr 8 and 0xFF)}/${c(rgb and 0xFF)}"
    }

    // ---------------------------------------------------------------- resize

    /**
     * Changes the grid size. When the screen gets shorter, blank rows below the cursor go
     * first, then rows from the top move into scrollback, so the cursor stays on screen.
     */
    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceAtLeast(1)
        val r = newRows.coerceAtLeast(1)
        if (c == cols && r == rows) return

        fun fit(rowsList: MutableList<Row>, isMain: Boolean, cursorRow: Int): Int {
            var cy = cursorRow
            for (row in rowsList) row.resize(c)
            var excess = rowsList.size - r
            while (excess > 0 && rowsList.size - 1 > cy && rowsList.last().isBlank()) {
                rowsList.removeAt(rowsList.size - 1)
                excess--
            }
            while (excess > 0) {
                val removed = rowsList.removeAt(0)
                if (isMain) newScrollback.add(removed)
                cy--
                excess--
            }
            while (rowsList.size < r) rowsList.add(Row(c))
            return cy.coerceIn(0, r - 1)
        }

        val mainCursor = if (isAlternateScreen) (savedMain?.y ?: 0) else cursorY
        val newMainCursor = fit(mainScreen, true, mainCursor)
        val newAltCursor = fit(altScreen, false, if (isAlternateScreen) cursorY else 0)
        cols = c
        rows = r
        cursorY = if (isAlternateScreen) newAltCursor else newMainCursor
        cursorX = cursorX.coerceIn(0, cols - 1)
        pendingWrap = false
        scrollTop = 0
        scrollBottom = rows - 1
    }

    companion object {
        const val ESC = '\u001B'
        const val BEL = '\u0007'

        const val DEFAULT_COLOR = -1
        const val RGB_FLAG = 0x1000000

        const val BOLD = 1
        const val FAINT = 2
        const val ITALIC = 4
        const val UNDERLINE = 8
        const val INVERSE = 16
        const val STRIKE = 32
        const val HIDDEN = 64

        val DEFAULT_STYLE = Style()

        private const val MAX_PARAMS_LENGTH = 256
        private const val MAX_OSC_LENGTH = 4096
        private const val MAX_PENDING_SCROLLBACK = 5000
    }
}
