package com.konnisan.dewuauto.util

import android.content.Context

data class ScreenInfo(val widthPx: Int, val heightPx: Int) {
    companion object {
        fun from(context: Context): ScreenInfo {
            val dm = context.resources.displayMetrics
            return ScreenInfo(dm.widthPixels, dm.heightPixels)
        }
    }
}
