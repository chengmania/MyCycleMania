package com.kc3smw.cyclemania

import android.view.View
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Pads [view] for the system bars/cutout on edge-to-edge devices (targetSdk 35+ enforces
 * edge-to-edge regardless of decorFitsSystemWindows), preserving any padding already set
 * in the layout so it isn't clobbered on relayout.
 */
fun Window.applyBarInsets(view: View, top: Boolean = true, bottom: Boolean = true) {
    WindowCompat.setDecorFitsSystemWindows(this, false)
    val basePaddingTop = view.paddingTop
    val basePaddingBottom = view.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.updatePadding(
            top = if (top) basePaddingTop + bars.top else basePaddingTop,
            bottom = if (bottom) basePaddingBottom + bars.bottom else basePaddingBottom
        )
        insets
    }
}
