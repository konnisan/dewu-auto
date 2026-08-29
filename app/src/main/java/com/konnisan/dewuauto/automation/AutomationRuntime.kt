package com.konnisan.dewuauto.automation

data class AutomationRuntime(
    var state: AutomationState = AutomationState.IDLE,
    var registrationCount: Int = 0,
    var listScrollCount: Int = 0,
    var userSortApplied: Boolean = false,
    var browsedCount: Int = 0,
    var lastActionAt: Long = 0L,
    var actionCount: Int = 0,
    var lastMessage: String = "未启动",
)
