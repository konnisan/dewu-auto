package com.konnisan.dewuauto.automation

object DewuSelectors {
    const val PACKAGE_NAME = "com.shizhuang.duapp"

    // 不同得物版本的首页不一定暴露“首页”文本。
    // isHome() 还会同时要求“我/我的”入口存在，因此这里允许多个稳定首页特征。
    val HOME_MARKERS = listOf("首页", "购买", "探索", "推荐", "关注")
    val PROFILE_TAB = listOf("我", "我的")

    // 达人号固定走“我/我的 → 商单/品牌合作”快捷入口。
    val CREATION_CENTER = listOf("创作中心")
    val BRAND_CONTEXT = listOf("品牌合作", "商单")
    val BRAND_PAGE_MARKERS = listOf("综合排序", "奖励类型", "产品类目", "商单", "品牌合作")
    val TASK_DETAIL_MARKERS = listOf("任务详情", "合作详情", "拍摄要求")
    val DETAIL_SECTION_BOUNDARIES = listOf(
        "任务商品", "发布时间", "合作方式", "达人要求", "发布要求", "任务要求",
        "合作详情", "拍摄要求", "文案要求", "其他要求", "引力商业内容指南", "品牌合作课堂",
    )
    val SORT_ENTRY = listOf("综合排序", "最近发布", "即将截止", "默认排序")
    val SORT_OPTIONS = listOf("综合排序", "最近发布", "即将截止", "默认排序")

    const val REWARD_TYPE = "奖励类型"
    const val PRODUCT_CATEGORY = "产品类目"
    const val FILTER_ENTRY = "筛选"

    const val LIST_REGISTER = "报名"
    const val SUBSCRIBE_REMINDER = "订阅提醒"
    val LIST_TASK_ACTIONS = listOf(LIST_REGISTER, SUBSCRIBE_REMINDER)
    val REGISTER_BUTTONS = listOf("立即报名", LIST_REGISTER)
    const val IMMEDIATE_REGISTER = "立即报名"
    val ENROLLMENT_FORM_MARKERS = listOf("确认报名信息", "收货地址", "样品规格")
    const val CONFIRM_ENROLLMENT = "确认报名"
    val IRREVERSIBLE_NOTICE_MARKERS = listOf("报名须知", "报名后无法取消")
    const val IRREVERSIBLE_CANCEL = "取消"
    const val IRREVERSIBLE_CONFIRM = "确认"
    val ENROLLMENT_SUCCESS_MARKERS = listOf(
        "已报名，待品牌方确认",
        "品牌方确认报名信息中",
        "已报名,待品牌方确认",
    )
    val APPLY_TO_JOIN = listOf("申请入驻")
    val FILTER_CONFIRM = listOf("确定")

    val SECURITY_MARKERS = listOf("安全验证", "验证码", "人机验证", "滑块验证", "风险验证")
    val PRODUCT_CATEGORIES = listOf(
        "服装", "美妆个护", "3C数码", "配件", "箱包", "手表", "鞋", "家居", "食品饮料", "其他"
    )
}
