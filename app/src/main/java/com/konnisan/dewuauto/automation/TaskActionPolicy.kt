package com.konnisan.dewuauto.automation

object TaskActionPolicy {
    fun canOpenTask(actionText: String): Boolean = actionText.trim() == DewuSelectors.LIST_REGISTER

    fun isSubscriptionReminder(actionText: String): Boolean =
        actionText.trim() == DewuSelectors.SUBSCRIBE_REMINDER
}
