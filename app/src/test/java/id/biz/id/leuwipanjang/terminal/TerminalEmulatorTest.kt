package id.biz.id.leuwipanjang.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TerminalEmulatorTest {

    private fun TerminalEmulator.lines() = screen.map { it.text() }

    @Test
    fun lineFeedKeepsColumn() {
        val t = TerminalEmulator(20, 5)
        t.append("abc\ndef")
        assertEquals(listOf("abc", "   def", "", "", ""), t.lines())
    }

    @Test
    fun cursorForwardPastEndOfText() {
        val t = TerminalEmulator(20, 3)
        t.append("\u001B[6Cxy")
        assertEquals("      xy", t.lines()[0])
    }

    @Test
    fun redrawInPlaceDoesNotGrow() {
        val t = TerminalEmulator(20, 6)
        t.append("one\r\ntwo\r\nthree")
        repeat(5) { t.append("\u001B[2A\r\u001B[Jone\r\ntwo\r\nthree") }
        assertEquals(listOf("one", "two", "three", "", "", ""), t.lines())
        assertTrue(t.drainScrollback().isEmpty())
    }

    @Test
    fun scrollingMovesTopLinesToScrollback() {
        val t = TerminalEmulator(10, 3)
        t.append("1\r\n2\r\n3\r\n4\r\n5")
        assertEquals(listOf("3", "4", "5"), t.lines())
        assertEquals(listOf("1", "2"), t.drainScrollback().map { it.text() })
    }

    @Test
    fun wrapsAtRightMargin() {
        val t = TerminalEmulator(4, 3)
        t.append("abcdef")
        assertEquals(listOf("abcd", "ef", ""), t.lines())
        assertTrue(t.screen[0].wrapped)
    }

    @Test
    fun reverseIndexAndEscapesWithIntermediatesPrintNothing() {
        val t = TerminalEmulator(10, 3)
        t.append("\u001B[0 q\u001B[>4;2m\u001B[?u\u001B[>5uhi\r\n\u001BMX")
        assertEquals(listOf("Xi", "", ""), t.lines())
    }

    @Test
    fun alternateScreenRestoresMainScreen() {
        val t = TerminalEmulator(10, 3)
        t.append("main")
        t.append("\u001B[?1049h\u001B[Hfull")
        assertTrue(t.isAlternateScreen)
        assertEquals("full", t.lines()[0])
        t.append("\u001B[?1049l")
        assertFalse(t.isAlternateScreen)
        assertEquals("main", t.lines()[0])
        assertEquals(4, t.cursorX)
    }

    @Test
    fun scrollRegionKeepsLinesOutsideIt() {
        val t = TerminalEmulator(10, 4)
        t.append("top\r\na\r\nb\r\nbottom\u001B[2;3r\u001B[3;1H\nc")
        assertEquals(listOf("top", "b", "c", "bottom"), t.lines())
        assertTrue(t.drainScrollback().isEmpty())
    }

    @Test
    fun answersQueries() {
        val replies = mutableListOf<String>()
        val t = TerminalEmulator(10, 4)
        t.onResponse = { replies += it }
        t.append("ab\u001B[6n\u001B[c\u001B]11;?\u0007")
        assertEquals("\u001B[1;3R", replies[0])
        assertEquals("\u001B[?1;2c", replies[1])
        assertTrue(replies[2].startsWith("\u001B]11;rgb:"))
    }

    @Test
    fun shrinkingKeepsCursorRowOnScreen() {
        val t = TerminalEmulator(10, 5)
        t.append("1\r\n2\r\n3\r\n4")
        t.resize(10, 2)
        assertEquals(listOf("3", "4"), t.lines())
        assertEquals(listOf("1", "2"), t.drainScrollback().map { it.text() })
        assertEquals(1, t.cursorY)
    }

    /**
     * Replays a raw capture and writes the resulting scrollback + screen to a file, to compare
     * with a reference terminal (e.g. tmux capture-pane). Runs only when REPLAY_IN is set:
     * REPLAY_IN=raw REPLAY_OUT=txt REPLAY_COLS=100 REPLAY_ROWS=30
     */
    @Test
    fun replayCapture() {
        val input = System.getenv("REPLAY_IN")
        assumeTrue(!input.isNullOrEmpty())
        val t = TerminalEmulator(System.getenv("REPLAY_COLS").toInt(), System.getenv("REPLAY_ROWS").toInt())
        t.append(File(input!!).readText(Charsets.UTF_8))
        val out = StringBuilder()
        out.append("=== scrollback\n")
        t.drainScrollback().forEach { out.append(it.text()).append('\n') }
        out.append("=== screen (cursor ${t.cursorY},${t.cursorX} visible=${t.cursorVisible} alt=${t.isAlternateScreen})\n")
        t.screen.forEach { out.append(it.text()).append('\n') }
        File(System.getenv("REPLAY_OUT")).writeText(out.toString())
    }
}
