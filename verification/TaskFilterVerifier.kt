import com.konnisan.dewuauto.automation.TaskCardParser
import com.konnisan.dewuauto.automation.TaskEligibilityEvaluator
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

    val wrongSizeResult = TaskEligibilityEvaluator.evaluate(
        sampleTask,
        AutomationConfig(excludedWords = emptyList(), sizeSpec = "42码"),
    )
    check(!wrongSizeResult.eligible && wrongSizeResult.reason == "样品规格不匹配")

    check(
        TaskEligibilityEvaluator.splitTerms("内定##复投##直接报名") ==
            listOf("内定", "复投", "直接报名"),
    )

    println("TASK_FILTER_OK parser=2 full=1 excluded=1 accepted=1 sizeMismatch=1 splitTerms=1")
}
