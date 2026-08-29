package com.konnisan.dewuauto.automation

data class TaskCard(
    val signature: String,
    val title: String,
    val rewardAmount: Double?,
    val registeredCount: Int?,
    val capacity: Int?,
    val deadlineText: String?,
    val rawText: String,
)

data class TaskEligibility(
    val eligible: Boolean,
    val reason: String,
)

data class PreviewTaskResult(
    val signature: String,
    val title: String,
    val rewardText: String,
    val capacityText: String,
    val deadlineText: String,
    val eligible: Boolean,
    val reason: String,
)
