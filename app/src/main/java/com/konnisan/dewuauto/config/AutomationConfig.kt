package com.konnisan.dewuauto.config

data class AutomationConfig(
    val cardKey: String = "",
    val productCategory: String = "服装",
    val sortMode: String = "最近发布",
    val targetEnrollmentCount: Int = 1,
    val finalConfirmationEnabled: Boolean = false,
    val maxListScrolls: Int = 5,
    val minPrice: Double = 21.0,
    val maxPrice: Double = 9_999_999.0,
    val excludedWords: List<String> = emptyList(),
    val sizeSpec: String = "",
) {
    fun normalized(): AutomationConfig {
        val lowPrice = minOf(minPrice, maxPrice)
        val highPrice = maxOf(minPrice, maxPrice)
        return copy(
            targetEnrollmentCount = targetEnrollmentCount.coerceIn(1, 20),
            maxListScrolls = maxListScrolls.coerceIn(1, 50),
            minPrice = lowPrice,
            maxPrice = highPrice,
            excludedWords = excludedWords.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        )
    }
}
