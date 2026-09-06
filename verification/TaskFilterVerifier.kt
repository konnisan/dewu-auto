import com.konnisan.dewuauto.automation.TaskCardParser
import com.konnisan.dewuauto.automation.TaskEligibilityEvaluator
import com.konnisan.dewuauto.automation.TaskDetailParser
import com.konnisan.dewuauto.automation.TaskContentType
import com.konnisan.dewuauto.automation.PreviewTaskResult
import com.konnisan.dewuauto.automation.TaskResultLedger
import com.konnisan.dewuauto.automation.TaskActionPolicy
import com.konnisan.dewuauto.automation.EnrollmentFormHandler
import com.konnisan.dewuauto.automation.EnrollmentRunPolicy
import com.konnisan.dewuauto.automation.FinalConfirmationGuard
import com.konnisan.dewuauto.automation.FinalNoticeDecision
import com.konnisan.dewuauto.automation.CategorySelectionRules
import com.konnisan.dewuauto.automation.DewuSelectors
import com.konnisan.dewuauto.config.AutomationConfig

fun main() {
    DewuSelectors.PRODUCT_CATEGORIES.forEach { category ->
        check(CategorySelectionRules.isExactCategory(category, category))
        check(CategorySelectionRules.isHeaderValue(category, category, centerY = 150, screenHeight = 1_000))
        check(
            CategorySelectionRules.isPanelOption(
                category,
                category,
                centerY = 400,
                screenHeight = 1_000,
                confirmTop = 900,
            ),
        )
    }
    check(!CategorySelectionRules.isExactCategory("鞋类", "鞋"))
    check(!CategorySelectionRules.isPanelOption("鞋", "鞋", 150, 1_000, 900))
    check(!CategorySelectionRules.isHeaderValue("鞋", "鞋", 400, 1_000))

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

    val priceAndRewardCard = requireNotNull(
        TaskCardParser.parse(
            "鞋类试穿 | 商品售价 ¥20 | 报名：20/100人 | 6天后截止 | 现金奖励 | ¥30 | 报名",
        ),
    )
    check(priceAndRewardCard.rewardAmount == 30.0)
    check(priceAndRewardCard.registeredCount == 20)

    val ambiguousRewardCard = requireNotNull(
        TaskCardParser.parse(
            "异常合并卡片 | 报名：1/100人 | 现金奖励 ¥20 | 现金奖励 ¥30 | 报名",
        ),
    )
    check(ambiguousRewardCard.rewardAmount == null)

    val rewardRange = AutomationConfig(minPrice = 30.0, maxPrice = 99_999.0)
    fun evaluateReward(value: String) = TaskEligibilityEvaluator.evaluate(
        requireNotNull(
            TaskCardParser.parse(
                "奖励边界 $value | 报名：1/100人 | 6天后截止 | 现金奖励 ¥$value | 报名",
            ),
        ),
        rewardRange,
    )
    check(!evaluateReward("20").eligible)
    check(!evaluateReward("29.99").eligible)
    check(evaluateReward("30").eligible)
    check(evaluateReward("30.00").eligible)
    check(evaluateReward("99999").eligible)
    check(!evaluateReward("99999.01").eligible)
    check(evaluateReward("20").reason == "现金奖励 ¥20，不在 ¥30-¥99999，跳过")
    check(evaluateReward("30").reason == "现金奖励 ¥30，位于 ¥30-¥99999，通过")
    val missingRewardResult = TaskEligibilityEvaluator.evaluate(
        requireNotNull(TaskCardParser.parse("无金额任务 | 报名：1/100人 | 6天后截止 | 报名")),
        rewardRange,
    )
    check(!missingRewardResult.eligible)
    check(missingRewardResult.reason == "未识别到现金奖励，设置范围 ¥30-¥99999，跳过")

    val adjacentLow = requireNotNull(
        TaskCardParser.parse("低奖励任务 | 报名：1/100人 | 现金奖励 ¥20 | 报名"),
    )
    val adjacentBoundary = requireNotNull(
        TaskCardParser.parse("边界奖励任务 | 报名：1/100人 | 现金奖励 ¥30 | 报名"),
    )
    check(!TaskEligibilityEvaluator.evaluate(adjacentLow, rewardRange).eligible)
    check(TaskEligibilityEvaluator.evaluate(adjacentBoundary, rewardRange).eligible)

    val fullTask = requireNotNull(
        TaskCardParser.parse("球鞋开箱体验任务 | 已报名：80/80人 | 6天后截止 | 现金奖励 | ¥100 | 报名"),
    )
    val fullResult = TaskEligibilityEvaluator.evaluate(fullTask, AutomationConfig())
    check(!fullResult.eligible && fullResult.reason == "名额已满")

    val sampleTask = requireNotNull(
        TaskCardParser.parse("需要露脸的男士冲锋衣 | 已报名：8/80人 | 6天后截止 | 现金奖励 | ¥100 | 报名"),
    )
    val excludedResult = TaskEligibilityEvaluator.evaluate(
        sampleTask,
        AutomationConfig(excludedWords = listOf("露脸")),
    )
    check(!excludedResult.eligible && excludedResult.reason == "商品名字命中屏蔽词：露脸")

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

    val parsedDetail = requireNotNull(
        TaskDetailParser.parse(
            "任务详情 | 配件体验任务 | 仅图文 | 任务商品 | 石榴石三圈手串 | 商品描述 | " +
                "发布时间 | 2026-09-01 至 2026-09-15 | 合作方式 | 现金收益，拍摄后商品需寄回 | " +
                "达人要求 | 选中后加v | 发布要求 | 页面示例文字包含露脸 | " +
                "合作详情 | 拍摄要求 | 鞋子需完整展示 | 文案要求 | 原创文案",
            fallbackProductName = "列表备用标题",
        ),
    )
    check(parsedDetail.productName == "石榴石三圈手串")
    check(parsedDetail.cooperationMethod == "现金收益，拍摄后商品需寄回")
    check(parsedDetail.creatorRequirements == "选中后加v")
    check(parsedDetail.shootingRequirements == "鞋子需完整展示")
    check(parsedDetail.contentType == TaskContentType.IMAGE_ONLY)

    check(
        TaskDetailParser.parseContentType(listOf("任务详情", "仅视频")) ==
            TaskContentType.VIDEO_ONLY,
    )
    check(
        TaskDetailParser.parseContentType(listOf("任务详情", "图文/视频")) ==
            TaskContentType.IMAGE_OR_VIDEO,
    )
    check(
        TaskDetailParser.parseContentType(listOf("任务详情", "未知类型")) ==
            TaskContentType.UNKNOWN,
    )

    val productBlocked = TaskEligibilityEvaluator.evaluateDetail(
        parsedDetail.copy(productName = "需要露脸的手串"),
        AutomationConfig(excludedWords = listOf("露脸")),
    )
    check(!productBlocked.eligible && productBlocked.reason == "商品名字命中屏蔽词：露脸")

    val cooperationBlocked = TaskEligibilityEvaluator.evaluateDetail(
        parsedDetail.copy(cooperationMethod = "需要露脸展示产品"),
        AutomationConfig(excludedWords = listOf("露脸")),
    )
    check(!cooperationBlocked.eligible && cooperationBlocked.reason == "合作方式命中屏蔽词：露脸")

    val requirementsBlocked = TaskEligibilityEvaluator.evaluateDetail(
        parsedDetail.copy(creatorRequirements = "达人必须真人露脸"),
        AutomationConfig(excludedWords = listOf("露脸")),
    )
    check(!requirementsBlocked.eligible && requirementsBlocked.reason == "达人要求 明确要求露脸")
    check(requirementsBlocked.matchedField == "达人要求")
    check(requirementsBlocked.matchedWord == "露脸")

    val slashAlternativeAccepted = TaskEligibilityEvaluator.evaluateDetail(
        parsedDetail.copy(creatorRequirements = "照片包括露脸/上身照"),
        AutomationConfig(excludedWords = listOf("露脸")),
    )
    check(slashAlternativeAccepted.eligible)

    val orAlternativeAccepted = TaskEligibilityEvaluator.evaluateDetail(
        parsedDetail.copy(creatorRequirements = "露脸或上身照，任选其一"),
        AutomationConfig(excludedWords = listOf("露脸")),
    )
    check(orAlternativeAccepted.eligible)

    val noFaceUpperBodyAccepted = TaskEligibilityEvaluator.evaluateDetail(
        parsedDetail.copy(creatorRequirements = "支持上身不露脸"),
        AutomationConfig(excludedWords = listOf("露脸")),
    )
    check(noFaceUpperBodyAccepted.eligible)

    val halfBodyAlternativeAccepted = TaskEligibilityEvaluator.evaluateDetail(
        parsedDetail.copy(creatorRequirements = "露脸或上半身照，任选其一"),
        AutomationConfig(excludedWords = listOf("露脸")),
    )
    check(halfBodyAlternativeAccepted.eligible)

    val halfBodyBothRequiredBlocked = TaskEligibilityEvaluator.evaluateDetail(
        parsedDetail.copy(creatorRequirements = "需要露脸和上半身照"),
        AutomationConfig(excludedWords = listOf("露脸")),
    )
    check(!halfBodyBothRequiredBlocked.eligible)

    val bothRequiredBlocked = TaskEligibilityEvaluator.evaluateDetail(
        parsedDetail.copy(creatorRequirements = "需要露脸和上身照"),
        AutomationConfig(excludedWords = listOf("露脸")),
    )
    check(!bothRequiredBlocked.eligible && bothRequiredBlocked.reason == "达人要求 明确要求露脸")

    val shootingRequiredFaceBlocked = TaskEligibilityEvaluator.evaluateDetail(
        parsedDetail.copy(shootingRequirements = "拍摄时必须露脸"),
        AutomationConfig(excludedWords = listOf("露脸")),
    )
    check(!shootingRequiredFaceBlocked.eligible && shootingRequiredFaceBlocked.reason == "拍摄要求 明确要求露脸")

    val realDeviceFacePhotoBlocked = TaskEligibilityEvaluator.evaluateDetail(
        parsedDetail.copy(shootingRequirements = "照片包括露脸全身照、上脚照"),
        AutomationConfig(excludedWords = listOf("露脸")),
    )
    check(!realDeviceFacePhotoBlocked.eligible)

    val shootingAlternativeAccepted = TaskEligibilityEvaluator.evaluateDetail(
        parsedDetail.copy(shootingRequirements = "可以露脸或者上身照"),
        AutomationConfig(excludedWords = listOf("露脸")),
    )
    check(shootingAlternativeAccepted.eligible)

    val shootingOrdinaryWordBlocked = TaskEligibilityEvaluator.evaluateDetail(
        parsedDetail.copy(shootingRequirements = "必须拍摄口播"),
        AutomationConfig(excludedWords = listOf("口播")),
    )
    check(!shootingOrdinaryWordBlocked.eligible)
    check(shootingOrdinaryWordBlocked.reason == "拍摄要求命中屏蔽词：口播")

    val videoOnlyBlocked = TaskEligibilityEvaluator.evaluateDetailOverview(
        parsedDetail.copy(contentType = TaskContentType.VIDEO_ONLY),
        AutomationConfig(),
    )
    check(!videoOnlyBlocked.eligible && videoOnlyBlocked.reason == "内容类型为仅视频")

    val unknownTypeBlocked = TaskEligibilityEvaluator.evaluateDetailOverview(
        parsedDetail.copy(contentType = TaskContentType.UNKNOWN),
        AutomationConfig(),
    )
    check(!unknownTypeBlocked.eligible && unknownTypeBlocked.reason == "内容类型未识别")

    val creatorShortCircuitsShooting = TaskEligibilityEvaluator.evaluateDetail(
        parsedDetail.copy(
            creatorRequirements = "必须露脸",
            shootingRequirements = "拍摄要求命中另一个词",
        ),
        AutomationConfig(excludedWords = listOf("另一个词")),
    )
    check(creatorShortCircuitsShooting.reason == "达人要求 明确要求露脸")

    val outsideFieldsAccepted = TaskEligibilityEvaluator.evaluateDetail(
        parsedDetail,
        AutomationConfig(excludedWords = listOf("露脸")),
    )
    check(outsideFieldsAccepted.eligible)
    check(TaskEligibilityEvaluator.evaluateDetail(parsedDetail, AutomationConfig()).eligible)

    val enrollmentForm = "确认报名信息 | 收货地址 | 张三 13800000000 某地址 | 样品规格 | 均码 | 确认报名"
    check(EnrollmentFormHandler.isEnrollmentForm(enrollmentForm))
    check(EnrollmentFormHandler.hasSelectedAddress(enrollmentForm))
    check(EnrollmentFormHandler.hasConfiguredSpec(enrollmentForm, "均码，L码"))
    check(!EnrollmentFormHandler.hasSelectedAddress("确认报名信息 | 收货地址 | 请选择收货地址"))
    check(EnrollmentFormHandler.isIrreversibleNotice("报名须知 | 任务报名后无法取消 | 取消 | 确认"))
    check(EnrollmentFormHandler.isEnrollmentSuccess("待确认 | 已报名，待品牌方确认"))
    check(EnrollmentFormHandler.isConfirmedSpecBounds(1080, 2259, 345, 513, 1023, 582))
    check(!EnrollmentFormHandler.isConfirmedSpecBounds(1080, 2259, 57, 705, 168, 771))
    check(!EnrollmentFormHandler.isConfirmedSpecBounds(1080, 2259, 345, 2184, 1023, 2226))

    check(!AutomationConfig().finalConfirmationEnabled)
    check(AutomationConfig(targetEnrollmentCount = 5).normalized().targetEnrollmentCount == 5)
    check(
        AutomationConfig(targetEnrollmentCount = 20, finalConfirmationEnabled = true)
            .normalized().targetEnrollmentCount == 20,
    )
    check(
        EnrollmentRunPolicy.finalNoticeDecision(finalConfirmationEnabled = false) ==
            FinalNoticeDecision.REHEARSE_CANCEL,
    )
    check(
        EnrollmentRunPolicy.finalNoticeDecision(finalConfirmationEnabled = true) ==
            FinalNoticeDecision.CONFIRM,
    )
    check(EnrollmentRunPolicy.completedCount(false, 2, 0) == 2)
    check(EnrollmentRunPolicy.completedCount(true, 0, 2) == 2)
    check(EnrollmentRunPolicy.targetReached(false, 2, 0, 2))
    check(!EnrollmentRunPolicy.targetReached(false, 1, 9, 2))
    check(EnrollmentRunPolicy.targetReached(true, 9, 2, 2))
    check(!EnrollmentRunPolicy.targetReached(true, 9, 1, 2))

    val guard = FinalConfirmationGuard()
    guard.reset("run-1")
    check(!guard.tryAcquire("wrong-run", "task-a"))
    check(guard.tryAcquire("run-1", "task-a"))
    check(!guard.tryAcquire("run-1", "task-a"))
    check(guard.tryAcquire("run-1", "task-b"))
    check(guard.hasAttempted("task-a"))
    check(guard.hasAttempted("task-b"))
    check(guard.attemptCount() == 2)
    guard.reset("run-2")
    check(!guard.hasAttempted("task-a"))
    check(guard.tryAcquire("run-2", "task-a"))

    fun result(signature: String, status: String = "未报名") = PreviewTaskResult(
        signature = signature,
        title = "任务 $signature",
        rewardText = "现金奖励 ¥30",
        capacityText = "1/10人",
        deadlineText = "1天后截止",
        eligible = true,
        reason = "通过",
        enrollmentStatus = status,
    )
    var ledger = emptyList<PreviewTaskResult>()
    ledger = TaskResultLedger.upsert(ledger, result("a"))
    ledger = TaskResultLedger.upsert(ledger, result("b"))
    ledger = TaskResultLedger.upsert(ledger, result("c"))
    check(ledger.map(PreviewTaskResult::signature) == listOf("a", "b", "c"))
    ledger = TaskResultLedger.upsert(ledger, result("b", status = "已演练，未报名"))
    check(ledger.size == 3)
    check(ledger[1].enrollmentStatus == "已演练，未报名")
    check(TaskActionPolicy.canOpenTask(DewuSelectors.LIST_REGISTER))
    check(!TaskActionPolicy.canOpenTask("立即报名"))
    check(!TaskActionPolicy.canOpenTask("订阅提醒"))
    check(TaskActionPolicy.isSubscriptionReminder("订阅提醒"))

    check(
        TaskEligibilityEvaluator.splitTerms("内定##复投##直接报名") ==
            listOf("内定", "复投", "直接报名"),
    )

    println(
        "TASK_FILTER_OK categories=10 categoryGeometry=33 parser=5 rewardBoundaries=9 " +
        "webViewSplit=1 listFilters=4 detailFilters=22 formRules=6 runPolicy=10 " +
            "multiTaskGuard=9 resultLedger=5 actionPolicy=4 specGeometry=3 splitTerms=1",
    )
}
