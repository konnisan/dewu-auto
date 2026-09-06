package com.konnisan.dewuauto.config

import android.content.Context

class AutomationPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("automation_config", Context.MODE_PRIVATE)

    fun save(config: AutomationConfig) {
        val c = config.normalized()
        prefs.edit()
            .putString("cardKey", c.cardKey)
            .putString("productCategory", c.productCategory)
            .putString("sortMode", c.sortMode)
            .putInt("targetEnrollmentCount", c.targetEnrollmentCount)
            .putInt("maxListScrolls", c.maxListScrolls)
            .putString("minPrice", c.minPrice.toString())
            .putString("maxPrice", c.maxPrice.toString())
            .putString("excludedWords", c.excludedWords.joinToString(","))
            .putString("sizeSpec", c.sizeSpec)
            .apply()
    }

    fun load(): AutomationConfig = AutomationConfig(
        cardKey = prefs.getString("cardKey", "").orEmpty(),
        productCategory = prefs.getString("productCategory", "服装") ?: "服装",
        sortMode = prefs.getString("sortMode", "最近发布")
            ?.takeUnless { it == "默认排序" }
            ?: "最近发布",
        targetEnrollmentCount = prefs.getInt("targetEnrollmentCount", 1),
        finalConfirmationEnabled = false,
        maxListScrolls = prefs.getInt("maxListScrolls", 5),
        minPrice = prefs.getString("minPrice", "21")?.toDoubleOrNull() ?: 21.0,
        maxPrice = prefs.getString("maxPrice", "9999999")?.toDoubleOrNull() ?: 9_999_999.0,
        excludedWords = prefs.getString(
            "excludedWords",
            "",
        )
            .orEmpty().split(Regex("(?:##|[,，、;；\\s]+)"))
            .map { it.trim() }
            .filter { it.isNotEmpty() },
        sizeSpec = prefs.getString("sizeSpec", "").orEmpty(),
    ).normalized()
}
