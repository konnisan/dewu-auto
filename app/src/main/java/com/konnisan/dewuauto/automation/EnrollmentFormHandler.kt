package com.konnisan.dewuauto.automation

object EnrollmentFormHandler {
    private val missingAddressMarkers = listOf("请选择收货地址", "添加收货地址", "暂无收货地址")
    private val placeholderSpecMarkers = listOf("请选择样品规格", "填写样品规格", "请输入样品规格")

    fun isEnrollmentForm(rawText: String): Boolean =
        rawText.contains("确认报名信息", ignoreCase = true) ||
            (rawText.contains("收货地址", ignoreCase = true) &&
                rawText.contains("样品规格", ignoreCase = true))

    fun hasSelectedAddress(rawText: String): Boolean =
        rawText.contains("收货地址") && missingAddressMarkers.none { rawText.contains(it, ignoreCase = true) }

    fun hasConfiguredSpec(rawText: String, sizeSpec: String): Boolean {
        val terms = TaskEligibilityEvaluator.splitTerms(sizeSpec)
        if (terms.isEmpty()) return false
        if (placeholderSpecMarkers.any { rawText.contains(it, ignoreCase = true) }) return false
        return terms.any { rawText.contains(it, ignoreCase = true) }
    }

    fun isIrreversibleNotice(rawText: String): Boolean =
        DewuSelectors.IRREVERSIBLE_NOTICE_MARKERS.all { rawText.contains(it, ignoreCase = true) }

    fun isEnrollmentSuccess(rawText: String): Boolean =
        DewuSelectors.ENROLLMENT_SUCCESS_MARKERS.any { rawText.contains(it, ignoreCase = true) }

    fun isConfirmedSpecBounds(
        screenWidth: Int,
        screenHeight: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0 || right <= left || bottom <= top) return false
        val centerY = top + (bottom - top) / 2
        return left >= (screenWidth * 0.25f).toInt() &&
            right >= (screenWidth * 0.85f).toInt() &&
            centerY in (screenHeight * 0.12f).toInt()..(screenHeight * 0.42f).toInt()
    }
}
