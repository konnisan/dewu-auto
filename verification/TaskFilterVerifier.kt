import com.konnisan.dewuauto.automation.TaskCardParser
import com.konnisan.dewuauto.automation.TaskEligibilityEvaluator
import com.konnisan.dewuauto.automation.TaskDetailParser
import com.konnisan.dewuauto.automation.EnrollmentFormHandler
import com.konnisan.dewuauto.config.AutomationConfig

fun main() {
    val visibleTask = requireNotNull(
        TaskCardParser.parse(
            "YORKZOOM垂感双褶设计休闲裤151, 已报名：16/20人, 秒杀剩15小时, 现金奖励, ¥100, 报名",
        ),
    )
    check(visibleTask.title == "YORKZOOM垂感双褶设计休闲裤151")
    check(visibleTask.rewardAmount == 100.0)
    check(visibleTask.registeredCount == 16)
    check(visibleTask.capacity == 20)
    check(visibleTask.deadlineText == "秒杀剩15小时")

    val currentBrandCard = requireNotNull(
        TaskCardParser.parse("户外运动鞋体验任务, 报名：9/40人, 秒杀剩13小时, 现金奖励, ¥100"),
    )
    check(currentBrandCard.registeredCount == 9)
    check(currentBrandCard.capacity == 40)

    val splitWebViewCard = requireNotNull(
        TaskCardParser.parse(
            "投稿 | 冲锋衣 | 报名 | ： | 38 | /40人 | 6 | 天 | 后截止 | 现金奖励¥20 | 报名",
        ),
    )
    check(splitWebViewCard.title == "冲锋衣")
    check(splitWebViewCard.rewardAmount == 20.0)
    check(splitWebViewCard.registeredCount == 38)
    check(splitWebViewCard.capacity == 40)
    check(splitWebViewCard.deadlineText == "6天后截止")

    val fullTask = requireNotNull(
        TaskCardParser.parse("球鞋开箱体验任务 | 已报名：80/80人 | 6天后截止 | 现金奖励 | ¥100 | 报名"),
    )
    val fullResult = TaskEligibilityEvaluator.evaluate(fullTask, AutomationConfig())
    check(!fullResult.eligible && fullResult.reason == "名额已满")

    val sampleTask = requireNotNull(
        TaskCardParser.parse("男士冲锋衣 L码 复投任务 | 已报名：8/80人 | 6天后截止 | 现金奖励 | ¥100 | 报名"),
    )
    val excludedResult = TaskEligibilityEvaluator.evaluate(sampleTask, AutomationConfig())
    check(!excludedResult.eligible && excludedResult.reason.contains("复投"))

    val acceptedResult = TaskEligibilityEvaluator.evaluate(
        sampleTask,
        AutomationConfig(
            excludedWords = emptyList(),
            minPrice = 80.0,
            maxPrice = 120.0,
            sizeSpec = "L码，42码",
        ),
    )
    check(acceptedResult.eligible)

    val sizeNotRequiredOnCard = TaskEligibilityEvaluator.evaluate(
        sampleTask,
        AutomationConfig(excludedWords = emptyList(), sizeSpec = "42码"),
    )
    check(sizeNotRequiredOnCard.eligible)

    val blockedDetail = requireNotNull(
        TaskDetailParser.parse("任务详情 | 配件体验任务 | 达人要求 | 需要真人露脸拍视频 | 发布要求"),
    )
    val blockedDetailResult = TaskEligibilityEvaluator.evaluateDetail(blockedDetail, AutomationConfig())
    check(!blockedDetailResult.eligible && blockedDetailResult.reason.contains("露脸"))

    val acceptedDetail = requireNotNull(
        TaskDetailParser.parse("任务详情 | 配件体验任务 | 达人要求 | 无特殊要求 | 图文发布"),
    )
    check(TaskEligibilityEvaluator.evaluateDetail(acceptedDetail, AutomationConfig()).eligible)

    val enrollmentForm = "确认报名信息 | 收货地址 | 张三 13800000000 某地址 | 样品规格 | 均码 | 确认报名"
    check(EnrollmentFormHandler.isEnrollmentForm(enrollmentForm))
    check(EnrollmentFormHandler.hasSelectedAddress(enrollmentForm))
    check(EnrollmentFormHandler.hasConfiguredSpec(enrollmentForm, "均码，L码"))
    check(!EnrollmentFormHandler.hasSelectedAddress("确认报名信息 | 收货地址 | 请选择收货地址"))
    check(EnrollmentFormHandler.isIrreversibleNotice("报名须知 | 任务报名后无法取消 | 取消 | 确认"))
    check(EnrollmentFormHandler.isEnrollmentSuccess("待确认 | 已报名，待品牌方确认"))

    check(
        TaskEligibilityEvaluator.splitTerms("内定##复投##直接报名") ==
            listOf("内定", "复投", "直接报名"),
    )

    println("TASK_FILTER_OK parser=3 webViewSplit=1 listFilters=4 detailFilters=2 formRules=6 splitTerms=1")
}
