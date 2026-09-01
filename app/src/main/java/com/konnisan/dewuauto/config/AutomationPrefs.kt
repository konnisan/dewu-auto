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
            .putInt("homeBrowseCount", c.homeBrowseCount)
            .putInt("restMinMinutes", c.restMinMinutes)
            .putInt("restMaxMinutes", c.restMaxMinutes)
            .putInt("imageSwipeMin", c.imageSwipeMin)
            .putInt("imageSwipeMax", c.imageSwipeMax)
            .putInt("videoStayMinSeconds", c.videoStayMinSeconds)
            .putInt("videoStayMaxSeconds", c.videoStayMaxSeconds)
            .putString("minPrice", c.minPrice.toString())
            .putString("maxPrice", c.maxPrice.toString())
            .putString("excludedWords", c.excludedWords.joinToString(","))
            .putString("sizeSpec", c.sizeSpec)
            .putInt("refreshMinSeconds", c.refreshMinSeconds)
            .putInt("refreshMaxSeconds", c.refreshMaxSeconds)
            .apply()
    }

    fun load(): AutomationConfig = AutomationConfig(
        cardKey = prefs.getString("cardKey", "").orEmpty(),
        productCategory = prefs.getString("productCategory", "服装") ?: "服装",
        sortMode = prefs.getString("sortMode", "最近发布")
            ?.takeUnless { it == "默认排序" }
            ?: "最近发布",
        targetEnrollmentCount = prefs.getInt("targetEnrollmentCount", 1),
        maxListScrolls = prefs.getInt("maxListScrolls", 5),
        homeBrowseCount = prefs.getInt("homeBrowseCount", 1),
        restMinMinutes = prefs.getInt("restMinMinutes", 0),
        restMaxMinutes = prefs.getInt("restMaxMinutes", 0),
        imageSwipeMin = prefs.getInt("imageSwipeMin", 1),
        imageSwipeMax = prefs.getInt("imageSwipeMax", 8),
        videoStayMinSeconds = prefs.getInt("videoStayMinSeconds", 5),
        videoStayMaxSeconds = prefs.getInt("videoStayMaxSeconds", 10),
        minPrice = prefs.getString("minPrice", "21")?.toDoubleOrNull() ?: 21.0,
        maxPrice = prefs.getString("maxPrice", "9999999")?.toDoubleOrNull() ?: 9_999_999.0,
        excludedWords = prefs.getString(
            "excludedWords",
            "内定,复投,直接报名,订阅提醒,定制,露脸,真人出镜,出镜,拍视频,视频拍摄,口播,直播",
        )
            .orEmpty().split(Regex("(?:##|[,，、;；\\s]+)"))
            .map { it.trim() }
            .filter { it.isNotEmpty() },
        sizeSpec = prefs.getString("sizeSpec", "").orEmpty(),
        refreshMinSeconds = prefs.getInt("refreshMinSeconds", 2),
        refreshMaxSeconds = prefs.getInt("refreshMaxSeconds", 10),
    ).normalized()
}
