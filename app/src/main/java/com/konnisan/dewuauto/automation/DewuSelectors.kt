package com.konnisan.dewuauto.automation

object DewuSelectors {
    const val PACKAGE_NAME = "com.shizhuang.duapp"

    val HOME_MARKERS = listOf("首页")
    val PROFILE_TAB = listOf("我", "我的")
    val PROFILE_MARKERS = listOf("个人中心", "我的", "商单", "品牌合作")
    val BRAND_ENTRY = listOf("商单", "品牌合作")
    val BRAND_PAGE_MARKERS = listOf("综合排序", "奖励类型", "产品类目", "商单", "品牌合作")
    val MORE = listOf("查看更多")

    val SORT_ENTRY = listOf("综合排序", "最近发布", "即将截止", "默认排序")
    const val SORT_RECENT = "最近发布"
    val SORT_OPTIONS = listOf("综合排序", "最近发布", "即将截止", "默认排序")

    const val REWARD_TYPE = "奖励类型"
    const val PRODUCT_CATEGORY = "产品类目"

    val REGISTER_BUTTONS = listOf("立即报名", "报名")
    val CONFIRM_REGISTER_BUTTONS = listOf("确认报名", "提交报名", "确认提交")
    val REGISTER_SUCCESS = listOf("报名成功", "已报名", "报名完成")
    val REGISTER_FAILED = listOf("报名失败", "名额已满", "已截止", "不可报名")

    val SECURITY_MARKERS = listOf("安全验证", "验证码", "人机验证", "滑块验证", "风险验证")
    val VIDEO_MARKERS = listOf("视频", "播放", "暂停")
    val IMAGE_MARKERS = listOf("图文", "图片")

    val PRODUCT_CATEGORIES = listOf(
        "服装", "美妆个护", "3C数码", "配件", "箱包", "手表", "鞋", "家居", "食品饮料", "其他"
    )
}
