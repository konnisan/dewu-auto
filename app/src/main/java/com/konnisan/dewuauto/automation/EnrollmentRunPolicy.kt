package com.konnisan.dewuauto.automation

enum class FinalNoticeDecision {
    REHEARSE_CANCEL,
    CONFIRM,
}

object EnrollmentRunPolicy {
    fun finalNoticeDecision(finalConfirmationEnabled: Boolean): FinalNoticeDecision =
        if (finalConfirmationEnabled) FinalNoticeDecision.CONFIRM else FinalNoticeDecision.REHEARSE_CANCEL

    fun completedCount(
        finalConfirmationEnabled: Boolean,
        rehearsalCompletedCount: Int,
        enrollmentSuccessCount: Int,
    ): Int = if (finalConfirmationEnabled) enrollmentSuccessCount else rehearsalCompletedCount

    fun targetReached(
        finalConfirmationEnabled: Boolean,
        rehearsalCompletedCount: Int,
        enrollmentSuccessCount: Int,
        targetTaskCount: Int,
    ): Boolean = completedCount(
        finalConfirmationEnabled,
        rehearsalCompletedCount,
        enrollmentSuccessCount,
    ) >= targetTaskCount.coerceAtLeast(1)
}
