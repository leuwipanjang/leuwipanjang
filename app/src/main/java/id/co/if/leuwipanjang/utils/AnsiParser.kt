package id.co.if.leuwipanjang.utils

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import id.co.if.leuwipanjang.data.model.TerminalTheme
import java.util.regex.Pattern

class AnsiParser(private var theme: TerminalTheme) {

    private val ansiPattern = Pattern.compile("\u001B\\[([0-9;]*)m")

    fun setTheme(newTheme: TerminalTheme) {
        this.theme = newTheme
    }

    fun parse(rawText: String): CharSequence {
        // Normalize line endings
        val text = rawText.replace("\r\n", "\n").replace("\r", "\n")
        val builder = SpannableStringBuilder()

        var currentFgColor = theme.foregroundColor
        var currentBgColor: Int? = null
        var isBold = false

        val matcher = ansiPattern.matcher(text)
        var lastEnd = 0

        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()

            // Append preceding text with current styles
            if (start > lastEnd) {
                val chunk = text.substring(lastEnd, start)
                appendStyled(builder, chunk, currentFgColor, currentBgColor, isBold)
            }

            // Parse escape code parameters
            val codes = matcher.group(1)?.split(";") ?: emptyList()
            if (codes.isEmpty() || codes[0].isEmpty() || codes[0] == "0") {
                // Reset
                currentFgColor = theme.foregroundColor
                currentBgColor = null
                isBold = false
            } else {
                for (c in codes) {
                    val code = c.toIntOrNull() ?: continue
                    when (code) {
                        0 -> {
                            currentFgColor = theme.foregroundColor
                            currentBgColor = null
                            isBold = false
                        }
                        1 -> isBold = true
                        22 -> isBold = false
                        in 30..37 -> currentFgColor = getStandardColor(code - 30)
                        39 -> currentFgColor = theme.foregroundColor
                        in 40..47 -> currentBgColor = getStandardColor(code - 40)
                        49 -> currentBgColor = null
                        in 90..97 -> currentFgColor = getBrightColor(code - 90)
                        in 100..107 -> currentBgColor = getBrightColor(code - 100)
                    }
                }
            }
            lastEnd = end
        }

        // Append remaining text
        if (lastEnd < text.length) {
            val chunk = text.substring(lastEnd)
            appendStyled(builder, chunk, currentFgColor, currentBgColor, isBold)
        }

        return builder
    }

    private fun appendStyled(
        builder: SpannableStringBuilder,
        text: String,
        fgColor: Int,
        bgColor: Int?,
        bold: Boolean
    ) {
        val start = builder.length
        builder.append(text)
        val end = builder.length

        builder.setSpan(ForegroundColorSpan(fgColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        if (bgColor != null) {
            builder.setSpan(BackgroundColorSpan(bgColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        if (bold) {
            builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        return getStandardColor(index)
    }
}
