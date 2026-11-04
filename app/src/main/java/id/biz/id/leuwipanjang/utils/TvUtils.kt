package id.biz.id.leuwipanjang.utils

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.View
import com.google.android.material.card.MaterialCardView

/** True when running on Android TV / Google TV (D-pad remote, no touchscreen). */
fun Context.isTv(): Boolean {
    val uiMode = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

/**
 * Thickens the card outline while it has D-pad focus (the outline colour itself comes
 * from the @color/card_stroke state list). Never triggers in touch mode.
 */
fun MaterialCardView.highlightOnFocus() {
    val density = resources.displayMetrics.density
    val normal = (1 * density).toInt() // matches Widget.LeuwiPanjang.Card strokeWidth
    val focused = (3 * density).toInt()
    setOnFocusChangeListener { _, hasFocus ->
        strokeWidth = if (hasFocus) focused else normal
    }
}

/**
 * Links views left/right for D-pad navigation, e.g. a focusable list card and the action
 * buttons inside it, which the default focus search cannot reach from the card itself.
 */
fun focusChain(vararg views: View) {
    views.forEachIndexed { i, view ->
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val target = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> views.getOrNull(i + 1)
                KeyEvent.KEYCODE_DPAD_LEFT -> views.getOrNull(i - 1)
                else -> null
            }
            target?.requestFocus() ?: false
        }
    }
}
