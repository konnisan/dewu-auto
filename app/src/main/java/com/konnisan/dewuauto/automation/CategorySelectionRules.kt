package com.konnisan.dewuauto.automation

object CategorySelectionRules {
    private const val HEADER_MIN_FRACTION = 0.05f
    private const val HEADER_MAX_FRACTION = 0.22f
    private const val PANEL_MIN_FRACTION = 0.22f

    fun isHeaderValue(
        label: String,
        target: String,
        centerY: Int,
        screenHeight: Int,
    ): Boolean {
        if (!isExactCategory(label, target) || screenHeight <= 0) return false
        return centerY in
            (screenHeight * HEADER_MIN_FRACTION).toInt()..(screenHeight * HEADER_MAX_FRACTION).toInt()
    }

    fun isPanelOption(
        label: String,
        target: String,
        centerY: Int,
        screenHeight: Int,
        confirmTop: Int,
    ): Boolean {
        if (!isExactCategory(label, target) || screenHeight <= 0 || confirmTop <= 0) return false
        return centerY > (screenHeight * PANEL_MIN_FRACTION).toInt() && centerY < confirmTop
    }

    fun isExactCategory(label: String, target: String): Boolean =
        label.trim() == target.trim() && target.trim() in DewuSelectors.PRODUCT_CATEGORIES
}
