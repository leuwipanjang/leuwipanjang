package id.biz.id.leuwipanjang.utils

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import id.biz.id.leuwipanjang.R

/**
 * The terminal typeface: DejaVu Sans Mono (every glyph one cell wide, including box drawing
 * and block elements), falling back to bundled Noto Sans Symbols 2 / Symbols for the
 * symbols CLIs use that it lacks (⏵ ⏺ ⎿ Braille spinners, ...), then to the system fonts.
 * The bundled fallbacks need Android 10; older versions use DejaVu with system fallback.
 */
object TerminalFonts {

    @Volatile
    private var cached: Typeface? = null

    fun typeface(context: Context): Typeface =
        cached ?: create(context.applicationContext).also { cached = it }

    private fun create(context: Context): Typeface {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val res = context.resources
                val mono = FontFamily.Builder(Font.Builder(res, R.font.terminal_mono_regular).setWeight(400).build())
                    .addFont(Font.Builder(res, R.font.terminal_mono_bold).setWeight(700).build())
                    .build()
                val symbols2 = FontFamily.Builder(Font.Builder(res, R.font.terminal_symbols2).build()).build()
                val symbols = FontFamily.Builder(Font.Builder(res, R.font.terminal_symbols).build()).build()
                return Typeface.CustomFallbackBuilder(mono)
                    .addCustomFallback(symbols2)
                    .addCustomFallback(symbols)
                    .setSystemFallback("monospace")
                    .build()
            } catch (e: Exception) {
                Log.w("LeuwiPanjang", "Bundled terminal fonts unavailable", e)
            }
        }
        return ResourcesCompat.getFont(context, R.font.terminal_mono) ?: Typeface.MONOSPACE
    }
}
