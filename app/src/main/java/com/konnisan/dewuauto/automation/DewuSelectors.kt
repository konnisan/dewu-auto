package com.konnisan.dewuauto.automation

object DewuSelectors {
    const val PACKAGE_NAME = "com.shizhuang.duapp"

    // 不同得物版本的首页不一定暴露“首页”文本。
    // isHome() 还会同时要求“我/我的”入口存在，因此这里允许多个稳定首页特征。
    val HOME_MARKERS = listOf("首页", "购买", "探索", "推荐", "关注")
    val PROFILE_TAB = listOf("我", "我的")

    // 当前得物路径：我的 → 创作中心 → 品牌合作区域 → 查看更多。
    // 同时保留旧版“商单/品牌合作”直接入口，兼容不同得物版本。
    val PROFILE_MARKERS = listOf("个人中心", "我的", "创作中心", "商单", "品牌合作")
    val CREATION_CENTER = listOf("创作中心")
    val BRAND_ENTRY = listOf("品牌合作", "商单", "创作中心")
    val BRAND_CONTEXT = listOf("品牌合作", "商单")
    val BRAND_PAGE_MARKERS = listOf("综合排序", "奖励类型", "产品类目", "商单", "品牌合作")
    val TASK_DETAIL_MARKERS = listOf("任务详情", "合作详情", "拍摄要求")
    val WRONG_MORE_PAGE_MARKERS = listOf("好内容,得免单", "获取机会", "报名机会")
    val MORE = listOf("查看更多")

    val SORT_ENTRY = listOf("综合排序", "最近发布", "即将截止", "默认排序")
    const val SORT_RECENT = "最近发布"
    val SORT_OPTIONS = listOf("综合排序", "最近发布", "即将截止", "默认排序")

    const val REWARD_TYPE = "奖励类型"
    const val PRODUCT_CATEGORY = "产品类目"

    val REGISTER_BUTTONS = listOf("立即报名", "报名")
    val APPLY_TO_JOIN = listOf("申请入驻")
    val FILTER_CONFIRM = listOf("确定")

    val SECURITY_MARKERS = listOf("安全验证", "验证码", "人机验证", "滑块验证", "风险验证")
    val VIDEO_MARKERS = listOf("视频", "播放", "暂停")
    val IMAGE_MARKERS = listOf("图文", "图片")

    val PRODUCT_CATEGORIES = listOf(
        "服装", "美妆个护", "3C数码", "配件", "箱包", "手表", "鞋", "家居", "食品饮料", "其他"
    )
}
