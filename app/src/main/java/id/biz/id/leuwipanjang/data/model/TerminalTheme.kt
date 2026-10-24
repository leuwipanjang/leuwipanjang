package id.biz.id.leuwipanjang.data.model

import android.graphics.Color

data class TerminalTheme(
    val id: String,
    val name: String,
    val backgroundColor: Int,
    val foregroundColor: Int,
    val cursorColor: Int,
    val selectionColor: Int,
    val black: Int,
    val red: Int,
    val green: Int,
    val yellow: Int,
    val blue: Int,
    val magenta: Int,
    val cyan: Int,
    val white: Int
) {
    companion object {
        val TERMIUS_DARK = TerminalTheme(
            id = "termius_dark",
            name = "Termius Dark (Default)",
            backgroundColor = Color.parseColor("#0F141C"),
            foregroundColor = Color.parseColor("#E6EDF3"),
            cursorColor = Color.parseColor("#38BDF8"),
            selectionColor = Color.parseColor("#3338BDF8"),
            black = Color.parseColor("#484F58"),
            red = Color.parseColor("#FF7B72"),
            green = Color.parseColor("#3FB950"),
            yellow = Color.parseColor("#D29922"),
            blue = Color.parseColor("#58A6FF"),
            magenta = Color.parseColor("#BC8CFF"),
            cyan = Color.parseColor("#39C5CF"),
            white = Color.parseColor("#F0F6FC")
        )

        val DRACULA = TerminalTheme(
            id = "dracula",
            name = "Dracula Cyber",
            backgroundColor = Color.parseColor("#282A36"),
            foregroundColor = Color.parseColor("#F8F8F2"),
            cursorColor = Color.parseColor("#FF79C6"),
            selectionColor = Color.parseColor("#44475A"),
            black = Color.parseColor("#6272A4"),
            red = Color.parseColor("#FF5555"),
            green = Color.parseColor("#50FA7B"),
            yellow = Color.parseColor("#F1FA8C"),
            blue = Color.parseColor("#BD93F9"),
            magenta = Color.parseColor("#FF79C6"),
            cyan = Color.parseColor("#8BE9FD"),
            white = Color.parseColor("#FFFFFF")
        )

        val MATRIX = TerminalTheme(
            id = "matrix",
            name = "Matrix Hacker Green",
            backgroundColor = Color.parseColor("#000B00"),
            foregroundColor = Color.parseColor("#00FF66"),
            cursorColor = Color.parseColor("#00FF66"),
            selectionColor = Color.parseColor("#2200FF66"),
            black = Color.parseColor("#1B3320"),
            red = Color.parseColor("#FF4444"),
            green = Color.parseColor("#00FF66"),
            yellow = Color.parseColor("#CCFF00"),
            blue = Color.parseColor("#00CCFF"),
            magenta = Color.parseColor("#FF00CC"),
            cyan = Color.parseColor("#00FFAA"),
            white = Color.parseColor("#E0FFE8")
        )

        val SOLARIZED_DARK = TerminalTheme(
            id = "solarized",
            name = "Solarized Dark",
            backgroundColor = Color.parseColor("#002B36"),
            foregroundColor = Color.parseColor("#839496"),
            cursorColor = Color.parseColor("#268BD2"),
            selectionColor = Color.parseColor("#073642"),
            black = Color.parseColor("#586E75"),
            red = Color.parseColor("#DC322F"),
            green = Color.parseColor("#859900"),
            yellow = Color.parseColor("#B58900"),
            blue = Color.parseColor("#268BD2"),
            magenta = Color.parseColor("#D33682"),
            cyan = Color.parseColor("#2AA198"),
            white = Color.parseColor("#FDF6E3")
        )

        val ALL_THEMES = listOf(TERMIUS_DARK, DRACULA, MATRIX, SOLARIZED_DARK)

        fun findById(id: String?): TerminalTheme {
            return ALL_THEMES.find { it.id == id } ?: TERMIUS_DARK
        }
    }
}
