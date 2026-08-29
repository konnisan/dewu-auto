package com.konnisan.dewuauto.accessibility

import android.content.Context
import android.provider.Settings
import android.text.TextUtils

object AccessibilityStatus {
    fun isEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${DewuAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()

        return enabled.split(':').any { TextUtils.equals(it, expected) }
    }
}
