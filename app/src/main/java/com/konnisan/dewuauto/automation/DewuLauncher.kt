package com.konnisan.dewuauto.automation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log

object DewuLauncher {
    private const val TAG = "DewuAuto-Launcher"

    fun launch(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(DewuSelectors.PACKAGE_NAME)
            ?: return false

        intent.addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
        )
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return runCatching {
            context.startActivity(intent)
            true
        }.onFailure { Log.e(TAG, "Failed to launch Dewu", it) }.getOrDefault(false)
    }
}
