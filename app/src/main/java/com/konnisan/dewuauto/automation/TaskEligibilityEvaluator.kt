package com.konnisan.dewuauto.automation

import com.konnisan.dewuauto.config.AutomationConfig

object TaskEligibilityEvaluator {
    private val stoppedMarkers = listOf("已截止", "报名结束", "不可报名")

    fun evaluate(task: TaskCard, config: AutomationConfig): TaskEligibility {
        if (
            task.capacity != null &&
            task.registeredCount != null &&
            task.capacity > 0 &&
            task.registeredCount >= task.capacity
        ) {
            return TaskEligibility(false, "名额已满")
        }

        if (stoppedMarkers.any { task.rawText.contains(it, ignoreCase = true) }) {
            return TaskEligibility(false, "任务已截止")
        }

        val excludedWord = config.excludedWords.firstOrNull {
            task.rawText.contains(it, ignoreCase = true)
        }
        if (excludedWord != null) {
            return TaskEligibility(false, "命中排除词：$excludedWord")
        }

        val reward = task.rewardAmount
        if (reward == null && (config.minPrice > 0.0 || config.maxPrice < 9_999_999.0)) {
            return TaskEligibility(false, "未识别到奖励金额")
        }
        if (reward != null && reward !in config.minPrice..config.maxPrice) {
            return TaskEligibility(false, "奖励不在范围内")
        }

        val specs = splitTerms(config.sizeSpec)
        if (specs.isNotEmpty() && specs.none { task.rawText.contains(it, ignoreCase = true) }) {
            return TaskEligibility(false, "样品规格不匹配")
        }

        return TaskEligibility(true, "符合筛选条件")
    }

    internal fun splitTerms(value: String): List<String> = value
        .split(Regex("(?:##|[,，、;；\\s]+)"))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
}
