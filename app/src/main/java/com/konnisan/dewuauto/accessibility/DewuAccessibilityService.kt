package com.konnisan.dewuauto.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.konnisan.dewuauto.automation.AutomationController
import com.konnisan.dewuauto.automation.AutomationRuntime
import com.konnisan.dewuauto.config.AutomationConfig

class DewuAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "DewuAuto-Service"

        @Volatile
        var instance: DewuAccessibilityService? = null
            private set
    }

    private lateinit var controller: AutomationController

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        controller = AutomationController(this)
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::controller.isInitialized) return
        controller.poke()
    }

    override fun onInterrupt() {
        if (::controller.isInitialized) controller.stop("无障碍服务被中断")
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.destroy()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun startAutomation(config: AutomationConfig) {
        if (::controller.isInitialized) controller.start(config)
    }

    fun stopAutomation() {
        if (::controller.isInitialized) controller.stop()
    }

    fun snapshot(): AutomationRuntime? =
        if (::controller.isInitialized) controller.snapshot() else null
}
