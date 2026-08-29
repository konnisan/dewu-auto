package com.konnisan.dewuauto.config

data class AutomationConfig(
    val cardKey: String = "",
    val productCategory: String = "服装",
    val sortMode: String = "默认排序",
    val maxListScrolls: Int = 5,
    val homeBrowseCount: Int = 1,
    val restMinMinutes: Int = 5,
    val restMaxMinutes: Int = 10,
    val imageSwipeMin: Int = 1,
    val imageSwipeMax: Int = 8,
    val targetRegistrationCount: Int = 40,
    val minPrice: Double = 21.0,
    val maxPrice: Double = 9_999_999.0,
    val excludedWords: List<String> = listOf("内定", "复投", "直接报名", "订阅提醒", "定制"),
    val sizeSpec: String = "",
    val refreshMinSeconds: Int = 2,
    val refreshMaxSeconds: Int = 10,
    val autoConfirmRegistration: Boolean = false,
) {
    fun normalized(): AutomationConfig {
        val minRest = restMinMinutes.coerceAtLeast(0)
        val maxRest = restMaxMinutes.coerceAtLeast(minRest)
        val minSwipe = imageSwipeMin.coerceAtLeast(0)
        val maxSwipe = imageSwipeMax.coerceAtLeast(minSwipe)
        val minRefresh = refreshMinSeconds.coerceAtLeast(1)
        val maxRefresh = refreshMaxSeconds.coerceAtLeast(minRefresh)
        val lowPrice = minOf(minPrice, maxPrice)
        val highPrice = maxOf(minPrice, maxPrice)
        return copy(
            maxListScrolls = maxListScrolls.coerceIn(1, 50),
            homeBrowseCount = homeBrowseCount.coerceIn(0, 20),
            restMinMinutes = minRest.coerceAtMost(120),
            restMaxMinutes = maxRest.coerceAtMost(120),
            imageSwipeMin = minSwipe.coerceAtMost(20),
            imageSwipeMax = maxSwipe.coerceAtMost(20),
            targetRegistrationCount = targetRegistrationCount.coerceIn(1, 200),
            minPrice = lowPrice,
            maxPrice = highPrice,
            refreshMinSeconds = minRefresh.coerceAtMost(60),
            refreshMaxSeconds = maxRefresh.coerceAtMost(60),
            excludedWords = excludedWords.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        )
    }
}
