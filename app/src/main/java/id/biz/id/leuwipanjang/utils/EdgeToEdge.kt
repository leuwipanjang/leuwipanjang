package id.biz.id.leuwipanjang.utils

import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Android 15+ (targetSdk 35+) always draws apps edge-to-edge, and on targetSdk 36 the
 * opt-out is gone. This enables edge-to-edge on every API level (dark bars, light icons)
 * and pads [root] so content stays clear of the status bar, navigation bar, display
 * cutout and — because adjustResize no longer resizes edge-to-edge windows — the IME.
 *
 * The insets are consumed here so children (e.g. BottomNavigationView) don't pad twice.
 * Set [padForIme] to false for windows that rely on adjustPan instead.
 * [onInsets] lets callers observe the raw insets first (e.g. to track IME visibility).
 */
fun ComponentActivity.setupEdgeToEdge(
    root: View,
    padForIme: Boolean = true,
    onInsets: ((WindowInsetsCompat) -> Unit)? = null
) {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
    )

    val baseLeft = root.paddingLeft
    val baseTop = root.paddingTop
    val baseRight = root.paddingRight
    val baseBottom = root.paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        onInsets?.invoke(insets)
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        view.setPadding(
            baseLeft + bars.left,
            baseTop + bars.top,
            baseRight + bars.right,
            baseBottom + if (padForIme) maxOf(bars.bottom, ime.bottom) else bars.bottom
        )
        WindowInsetsCompat.CONSUMED
    }
}
