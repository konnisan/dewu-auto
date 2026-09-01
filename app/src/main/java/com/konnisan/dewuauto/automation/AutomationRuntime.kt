package com.konnisan.dewuauto.automation

data class AutomationRuntime(
    var runId: String = "",
    var state: AutomationState = AutomationState.IDLE,
    var listScrollCount: Int = 0,
    var sortPhase: SortPhase = SortPhase.RECENT,
    var roundCount: Int = 0,
    var homeBrowsedCount: Int = 0,
    var scannedCount: Int = 0,
    var eligibleCount: Int = 0,
    var excludedCount: Int = 0,
    var parseFailedCount: Int = 0,
    var enrollmentSuccessCount: Int = 0,
    var enrollmentFailedCount: Int = 0,
    var currentTaskSignature: String? = null,
    var currentTaskTitle: String? = null,
    var detailCheckSummary: String = "未检查",
    var formCheckSummary: String = "未检查",
    var postconditionRetryCount: Int = 0,
    var lastNodeText: String = "",
    var lastNodeBounds: String = "",
    var operatorTokenExpiresAt: Long = 0L,
    var finalConfirmationUsed: Boolean = false,
    var requiresCreatorEnrollment: Boolean = false,
    var recentResults: List<PreviewTaskResult> = emptyList(),
    var lastActionAt: Long = 0L,
    var actionCount: Int = 0,
    var lastMessage: String = "未启动",
)

enum class SortPhase {
    RECENT,
    CONFIGURED,
}
