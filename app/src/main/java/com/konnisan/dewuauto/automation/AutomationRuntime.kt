package com.konnisan.dewuauto.automation

data class AutomationRuntime(
    var state: AutomationState = AutomationState.IDLE,
    var listScrollCount: Int = 0,
    var scannedCount: Int = 0,
    var eligibleCount: Int = 0,
    var excludedCount: Int = 0,
    var parseFailedCount: Int = 0,
    var requiresCreatorEnrollment: Boolean = false,
    var recentResults: List<PreviewTaskResult> = emptyList(),
    var lastActionAt: Long = 0L,
    var actionCount: Int = 0,
    var lastMessage: String = "未启动",
)
