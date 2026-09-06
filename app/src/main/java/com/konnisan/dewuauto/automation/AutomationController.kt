package com.konnisan.dewuauto.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.konnisan.dewuauto.accessibility.NodeUtils
import com.konnisan.dewuauto.config.AutomationConfig
import com.konnisan.dewuauto.license.LicenseManager
import com.konnisan.dewuauto.util.ScreenInfo
import java.util.UUID

class AutomationController(
    private val service: AccessibilityService,
) {
    companion object {
        private const val TAG = "DewuAuto"
        private const val BASE_TICK_MS = 700L
        private const val MAX_ACTIONS_PER_RUN = 2_000
        private const val HOME_DIAGNOSTIC_INTERVAL_MS = 5_000L
        private const val PAGE_TIMEOUT_MS = 20_000L
        private const val MAX_CATEGORY_PANEL_SWIPES = 3
        private const val MAX_CATEGORY_ATTEMPTS = 2
        private const val MAX_DETAIL_SCROLLS = 5
        private const val INTERNAL_REFRESH_MIN_SECONDS = 2
        private const val INTERNAL_REFRESH_MAX_SECONDS = 5
        private val TASK_CAPACITY_PATTERN = Regex("(?:(?:已)?报名[：:]?\\s*)?\\d+\\s*/\\s*\\d+\\s*人")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val licenseManager = LicenseManager(service)
    private val runtime = AutomationRuntime()
    private val finalConfirmationGuard = FinalConfirmationGuard()
    private val visitedTaskSignatures = LinkedHashSet<String>()
    private val processedTaskSignatures = LinkedHashSet<String>()

    private data class EnrollmentCandidate(
        val task: TaskCard,
        val result: PreviewTaskResult,
        val registerNode: AccessibilityNodeInfo,
    )

    private var config = AutomationConfig()
    private var running = false
    private var sortMenuOpened = false
    private var categoryStep = 0
    private var categoryPanelSwipeCount = 0
    private var brandEntryStep = 0
    private var brandShellRecoveryCount = 0
    private var stateEnteredAt = 0L
    private var notBeforeAt = 0L
    private var lastPokeAt = 0L
    private var lastHomeDiagnosticAt = 0L
    private var currentTaskSignature: String? = null
    private var returnBackAttempts = 0
    private var pendingSortTarget = ""
    private var postconditionAttempts = 0
    private var specSelectionAttempts = 0
    private var formSpecTarget = ""
    private var rehearsalNoticeObservedAt = 0L
    private var rehearsalCancelBackAttempted = false
    private var finishAfterReturnToTaskList = false
    private val accumulatedDetailSegments = LinkedHashSet<String>()
    private var detailScrollAttempts = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { tick() }
                .onFailure { fail("运行异常: ${it.message ?: it::class.java.simpleName}", it) }
            if (running) handler.postDelayed(this, BASE_TICK_MS)
        }
    }

    fun start(rawConfig: AutomationConfig) {
        stopInternal(resetState = false)
        config = rawConfig.normalized()
        runtime.runId = UUID.randomUUID().toString()
        finalConfirmationGuard.reset(runtime.runId)
        runtime.state = AutomationState.VERIFYING_LICENSE
        runtime.listScrollCount = 0
        runtime.scannedCount = 0
        runtime.eligibleCount = 0
        runtime.excludedCount = 0
        runtime.parseFailedCount = 0
        runtime.rehearsalCompletedCount = 0
        runtime.enrollmentSuccessCount = 0
        runtime.enrollmentFailedCount = 0
        runtime.currentTaskSignature = null
        runtime.currentTaskTitle = null
        runtime.detailCheckSummary = "未检查"
        runtime.formCheckSummary = "未检查"
        runtime.postconditionRetryCount = 0
        runtime.lastNodeText = ""
        runtime.lastNodeBounds = ""
        runtime.finalConfirmationClickCount = 0
        runtime.finalConfirmationEnabled = config.finalConfirmationEnabled
        runtime.targetTaskCount = config.targetEnrollmentCount
        runtime.requiresCreatorEnrollment = false
        runtime.taskResults = emptyList()
        runtime.actionCount = 0
        runtime.lastActionAt = 0L
        runtime.lastMessage = "验证卡密"
        visitedTaskSignatures.clear()
        processedTaskSignatures.clear()
        sortMenuOpened = false
        categoryStep = 0
        categoryPanelSwipeCount = 0
        brandEntryStep = 0
        brandShellRecoveryCount = 0
        notBeforeAt = 0L
        lastPokeAt = 0L
        lastHomeDiagnosticAt = 0L
        currentTaskSignature = null
        returnBackAttempts = 0
        pendingSortTarget = ""
        postconditionAttempts = 0
        specSelectionAttempts = 0
        formSpecTarget = ""
        rehearsalNoticeObservedAt = 0L
        rehearsalCancelBackAttempted = false
        finishAfterReturnToTaskList = false
        accumulatedDetailSegments.clear()
        detailScrollAttempts = 0
        running = true
        log(
            "CONFIG run=${runtime.runId.take(8)} category=${config.productCategory} " +
                "cashReward=${TaskEligibilityEvaluator.formatReward(config.minPrice)}-" +
                "${TaskEligibilityEvaluator.formatReward(config.maxPrice)} " +
                "mode=${if (config.finalConfirmationEnabled) "REAL" else "REHEARSAL"} " +
                "target=${config.targetEnrollmentCount}",
        )
        enterState(AutomationState.VERIFYING_LICENSE, "验证卡密")

        licenseManager.verify(config.cardKey) { result ->
            if (!running) return@verify
            result.onSuccess {
                licenseManager.startHeartbeat { reason ->
                    if (running) fail("卡密心跳失败: $reason")
                }
                enterState(AutomationState.WAITING_HOME, "等待得物页面")
                poke()
            }.onFailure { fail("卡密验证失败: ${it.message}") }
        }

        handler.post(ticker)
    }

    fun stop(reason: String = "用户停止") {
        runtime.lastMessage = reason
        stopInternal(resetState = true)
    }

    fun destroy() {
        stopInternal(resetState = false)
        licenseManager.shutdown()
    }

    fun poke() {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPokeAt < 250L) return
        lastPokeAt = now
        handler.post {
            if (running) runCatching { tick() }.onFailure { fail("事件触发异常: ${it.message}", it) }
        }
    }

    fun snapshot(): AutomationRuntime = runtime.copy(taskResults = runtime.taskResults.toList())

    private fun tick() {
        if (!running) return
        if (runtime.actionCount > MAX_ACTIONS_PER_RUN) {
            fail("动作次数超过安全上限 $MAX_ACTIONS_PER_RUN")
            return
        }
        if (SystemClock.elapsedRealtime() < notBeforeAt) return

        val root = service.rootInActiveWindow
        if (isDewuRoot(root) && NodeUtils.hasAnyText(root, DewuSelectors.SECURITY_MARKERS)) {
            pauseForSecurity("检测到验证码/安全验证，请手动处理后重新启动")
            return
        }

        when (runtime.state) {
            AutomationState.IDLE,
            AutomationState.VERIFYING_LICENSE,
            AutomationState.FINISHED,
            AutomationState.ERROR,
            AutomationState.PAUSED_FOR_SECURITY -> Unit

            AutomationState.WAITING_HOME -> handleWaitingHome(root)
            AutomationState.OPENING_PROFILE -> handleOpenProfile(root)
            AutomationState.WAITING_PROFILE -> handleWaitingProfile(root)
            AutomationState.OPENING_BRAND_COOPERATION -> handleOpenBrand(root)
            AutomationState.WAITING_BRAND_PAGE -> handleWaitingBrandPage(root)
            AutomationState.APPLYING_INITIAL_SORT -> handleSort(root)
            AutomationState.VERIFYING_SORT_SELECTION -> handleVerifySortSelection(root)
            AutomationState.APPLYING_CATEGORY -> handleCategory(root)
            AutomationState.VERIFYING_CATEGORY_SELECTION -> handleVerifyCategorySelection(root)
            AutomationState.SCANNING_TASKS -> handleScanTasks(root)
            AutomationState.SCROLLING_TASKS -> handleScrollTasks()
            AutomationState.OPENING_TASK_DETAIL -> handleOpeningTaskDetail(root)
            AutomationState.VALIDATING_TASK_DETAIL -> handleValidatingTaskDetail(root)
            AutomationState.COLLECTING_SHOOTING_REQUIREMENTS -> handleCollectingShootingRequirements(root)
            AutomationState.OPENING_ENROLLMENT_FORM -> handleOpeningEnrollmentForm(root)
            AutomationState.FILLING_ENROLLMENT_FORM -> handleFillingEnrollmentForm(root)
            AutomationState.SUBMITTING_ENROLLMENT_FORM -> handleSubmittingEnrollmentForm(root)
            AutomationState.CONFIRMING_IRREVERSIBLE_NOTICE -> handleIrreversibleNotice(root)
            AutomationState.VERIFYING_REHEARSAL_CANCELLATION -> handleVerifyRehearsalCancellation(root)
            AutomationState.VERIFYING_ENROLLMENT_RESULT -> handleEnrollmentResult(root)
            AutomationState.RETURNING_TO_TASK_LIST -> handleReturnToTaskList(root)
        }
    }

    private fun handleWaitingHome(root: AccessibilityNodeInfo?) {
        val rawText = NodeUtils.collectText(root, maxNodes = 400)
        when {
            EnrollmentFormHandler.isIrreversibleNotice(rawText) -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                touchAction("从报名须知安全返回")
                stateEnteredAt = SystemClock.elapsedRealtime()
            }
            EnrollmentFormHandler.isEnrollmentForm(rawText) -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                touchAction("从确认报名信息页安全返回")
                stateEnteredAt = SystemClock.elapsedRealtime()
            }
            isBrandPage(root) -> enterState(AutomationState.WAITING_BRAND_PAGE, "已在品牌合作页面")
            isTaskDetail(root) -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                touchAction("从任务详情安全返回")
                stateEnteredAt = SystemClock.elapsedRealtime()
            }
            isBrandShell(root) -> {
                if (elapsedInState() > 4_000L && brandShellRecoveryCount < 1) {
                    brandShellRecoveryCount++
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    touchAction("商单 WebView 节点未就绪，返回个人页重新进入")
                    stateEnteredAt = SystemClock.elapsedRealtime()
                } else if (elapsedInState() > PAGE_TIMEOUT_MS) {
                    fail("商单页面仅返回加载壳，重新进入后仍无法读取任务节点")
                }
            }
            isProfile(root) -> enterState(AutomationState.OPENING_BRAND_COOPERATION, "已在个人/创作中心")
            isHome(root) -> enterState(AutomationState.OPENING_PROFILE, "检测到首页，准备进入个人中心")
            else -> {
                val now = SystemClock.elapsedRealtime()
                if (elapsedInState() > 2_000L && now - lastHomeDiagnosticAt >= HOME_DIAGNOSTIC_INTERVAL_MS) {
                    lastHomeDiagnosticAt = now
                    val packageName = root?.packageName?.toString().orEmpty().ifBlank { "<none>" }
                    val visibleText = NodeUtils.dumpVisibleText(root, 180).take(1_200)
                    log("WAITING_HOME package=$packageName | visible=$visibleText")
                }
                if (elapsedInState() > 12_000L && !isDewuRoot(root)) {
                    DewuLauncher.launch(service)
                    touchAction("得物未在前台，尝试重新拉起")
                    stateEnteredAt = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    private fun handleOpenProfile(root: AccessibilityNodeInfo?) {
        if (isProfile(root)) {
            enterState(AutomationState.WAITING_PROFILE, "已进入个人中心")
            return
        }
        if (!isHome(root)) return
        if (clickText(root, DewuSelectors.PROFILE_TAB, "点击“我/我的”")) {
            enterState(AutomationState.WAITING_PROFILE, "等待个人中心")
        }
    }

    private fun handleWaitingProfile(root: AccessibilityNodeInfo?) {
        if (isBrandPage(root)) {
            enterState(AutomationState.WAITING_BRAND_PAGE, "已进入品牌合作页面")
            return
        }
        if (!isProfile(root)) {
            if (elapsedInState() > PAGE_TIMEOUT_MS) fail("未识别到个人中心")
            return
        }
        brandEntryStep = 0
        enterState(AutomationState.OPENING_BRAND_COOPERATION, "寻找达人号商单快捷入口")
    }

    private fun handleOpenBrand(root: AccessibilityNodeInfo?) {
        if (isBrandPage(root)) {
            enterState(AutomationState.WAITING_BRAND_PAGE, "已进入品牌合作页面")
            return
        }
        if (!isDewuRoot(root)) return

        if (brandEntryStep == 0) {
            // 达人号只走“我 → 商单”快捷入口；找不到时安全停止，不改走其它入口。
            val directBrandEntry = NodeUtils.findFirstByTexts(root, DewuSelectors.BRAND_CONTEXT)
            if (directBrandEntry != null) {
                if (NodeUtils.clickNode(directBrandEntry)) {
                    brandEntryStep = 1
                    touchAction("点击达人号商单快捷入口")
                    enterState(AutomationState.WAITING_BRAND_PAGE, "等待商单页面")
                } else if (elapsedInState() > PAGE_TIMEOUT_MS) {
                    fail("达人号商单快捷入口不可点击，已停止且不改走其它入口")
                }
                return
            }
        }

        if (elapsedInState() > PAGE_TIMEOUT_MS) fail("未找到达人号商单快捷入口，已安全停止")
    }

    private fun handleWaitingBrandPage(root: AccessibilityNodeInfo?) {
        if (!isDewuRoot(root)) return
        if (!isBrandPage(root)) {
            if (elapsedInState() > PAGE_TIMEOUT_MS) {
                runtime.requiresCreatorEnrollment = NodeUtils.hasAnyText(root, DewuSelectors.APPLY_TO_JOIN)
                val reason = if (runtime.requiresCreatorEnrollment) {
                    "当前账号仅显示申请入驻，已安全停止"
                } else {
                    "未识别到品牌合作页面"
                }
                finish(reason)
            }
            return
        }

        runtime.requiresCreatorEnrollment = NodeUtils.hasAnyText(root, DewuSelectors.APPLY_TO_JOIN)
        sortMenuOpened = false
        runtime.listScrollCount = 0
        postconditionAttempts = 0
        syncPostconditionAttempts()
        enterState(AutomationState.APPLYING_INITIAL_SORT, "设置本轮排序：${config.sortMode}")
    }

    private fun handleSort(root: AccessibilityNodeInfo?) {
        if (root == null || !isBrandPage(root)) return
        val targetSort = config.sortMode
        pendingSortTarget = targetSort
        if (!sortMenuOpened) {
            if (isSortMenuOpen(root)) {
                sortMenuOpened = true
                stateEnteredAt = SystemClock.elapsedRealtime()
                return
            }
            val current = findVisibleNodeByTexts(root, DewuSelectors.SORT_ENTRY)
            if (tapWebNode(current, "打开排序菜单", useRightEdge = true)) {
                sortMenuOpened = true
                stateEnteredAt = SystemClock.elapsedRealtime()
            }
            return
        }

        if (!isSortMenuOpen(root)) {
            if (elapsedInState() > 2_500L) retrySortOrFail("排序菜单未实际打开")
            return
        }
        val targetNode = findBottommostVisibleNodeByText(root, targetSort)
        if (tapWebNode(targetNode, "选择排序：$targetSort")) {
            pendingSortTarget = targetSort
            sortMenuOpened = false
            enterState(AutomationState.VERIFYING_SORT_SELECTION, "验证排序已切换为$targetSort")
        } else if (elapsedInState() > 2_500L) {
            retrySortOrFail("排序选项不可点击：$targetSort")
        }
    }

    private fun handleVerifySortSelection(root: AccessibilityNodeInfo?) {
        if (root != null && isBrandPage(root) && !isSortMenuOpen(root) && isFilterBarValue(root, pendingSortTarget)) {
            runtime.listScrollCount = 0
            postconditionAttempts = 0
            syncPostconditionAttempts()
            categoryStep = 0
            enterState(AutomationState.APPLYING_CATEGORY, "排序已生效，设置产品类目：${config.productCategory}")
            return
        }
        if (elapsedInState() > 8_000L) retrySortOrFail("未检测到排序后置条件：$pendingSortTarget")
    }

    private fun retrySortOrFail(reason: String) {
        postconditionAttempts++
        syncPostconditionAttempts()
        sortMenuOpened = false
        if (postconditionAttempts >= 3) {
            fail("$reason，连续 3 次无页面变化")
            return
        }
        enterState(AutomationState.APPLYING_INITIAL_SORT, "$reason，重试 $postconditionAttempts/3")
    }

    private fun handleCategory(root: AccessibilityNodeInfo?) {
        if (root == null || !isBrandPage(root)) return
        when (categoryStep) {
            0 -> {
                if (isFilterPanelOpen(root)) {
                    categoryStep = 1
                    stateEnteredAt = SystemClock.elapsedRealtime()
                    return
                }
                val filterNode = findVisibleNodeByTexts(root, listOf(DewuSelectors.FILTER_ENTRY))
                if (tapWebNode(filterNode, "打开筛选面板")) {
                    categoryStep = 1
                    categoryPanelSwipeCount = 0
                    notBeforeAt = SystemClock.elapsedRealtime() + 900L
                    stateEnteredAt = SystemClock.elapsedRealtime()
                    return
                }
                if (elapsedInState() > PAGE_TIMEOUT_MS) fail("未找到商单筛选入口")
            }

            1 -> {
                if (!isFilterPanelOpen(root)) {
                    if (elapsedInState() > 3_000L) retryCategoryOrFail("筛选面板未实际打开")
                    return
                }
                val categoryNode = findCategoryPanelOption(root, config.productCategory)
                if (tapWebNode(categoryNode, "选择类目：${config.productCategory}")) {
                    categoryStep = 2
                    stateEnteredAt = SystemClock.elapsedRealtime()
                } else if (elapsedInState() > 1_200L) {
                    if (categoryPanelSwipeCount < MAX_CATEGORY_PANEL_SWIPES &&
                        performCategoryPanelSwipe(root, "查找类目：${config.productCategory}")
                    ) {
                        categoryPanelSwipeCount++
                        stateEnteredAt = SystemClock.elapsedRealtime()
                        notBeforeAt = SystemClock.elapsedRealtime() + 700L
                    } else {
                        retryCategoryOrFail(
                            "筛选面板中未找到精确类目：${config.productCategory}，" +
                                "已滑动 $categoryPanelSwipeCount 次",
                        )
                    }
                }
            }

            else -> {
                if (!isFilterPanelOpen(root)) {
                    enterState(AutomationState.VERIFYING_CATEGORY_SELECTION, "验证产品类目筛选结果")
                    return
                }
                val confirmNode = findBottommostVisibleNodeByText(root, DewuSelectors.FILTER_CONFIRM.first())
                if (tapWebNode(confirmNode, "确认产品类目筛选")) {
                    enterState(AutomationState.VERIFYING_CATEGORY_SELECTION, "验证类目 ${config.productCategory} 已生效")
                } else if (elapsedInState() > 3_000L) {
                    retryCategoryOrFail("产品类目已选择，但未找到筛选确认按钮")
                }
            }
        }
    }

    private fun handleVerifyCategorySelection(root: AccessibilityNodeInfo?) {
        if (root != null && isBrandPage(root) && !isFilterPanelOpen(root) &&
            isSelectedCategoryInFilterBar(root, config.productCategory) && hasVisibleTaskList(root)
        ) {
            postconditionAttempts = 0
            syncPostconditionAttempts()
            runtime.listScrollCount = 0
            enterState(AutomationState.SCANNING_TASKS, "类目 ${config.productCategory} 已生效，扫描真实任务")
            return
        }
        if (elapsedInState() > 10_000L) {
            val actualCategory = currentCategoryInFilterBar(root) ?: "未识别"
            retryCategoryOrFail(
                "类目验真失败：配置 ${config.productCategory}，顶部实际 $actualCategory",
            )
        }
    }

    private fun retryCategoryOrFail(reason: String) {
        postconditionAttempts++
        syncPostconditionAttempts()
        categoryStep = 0
        categoryPanelSwipeCount = 0
        if (postconditionAttempts >= MAX_CATEGORY_ATTEMPTS) {
            fail("$reason，连续 $MAX_CATEGORY_ATTEMPTS 次未生效")
            return
        }
        enterState(
            AutomationState.APPLYING_CATEGORY,
            "$reason，重试 $postconditionAttempts/$MAX_CATEGORY_ATTEMPTS",
        )
    }

    private fun handleScanTasks(root: AccessibilityNodeInfo?) {
        if (root == null || !isBrandPage(root)) return
        runtime.requiresCreatorEnrollment = NodeUtils.hasAnyText(root, DewuSelectors.APPLY_TO_JOIN)

        val (newResults, candidate) = scanVisibleTasks(root)
        if (newResults.isEmpty() && runtime.scannedCount == 0 && runtime.requiresCreatorEnrollment) {
            finish("当前账号仅显示申请入驻，未发现可解析任务")
            return
        }

        if (candidate != null) {
            currentTaskSignature = candidate.result.signature
            runtime.currentTaskSignature = candidate.result.signature
            runtime.currentTaskTitle = candidate.task.title
            runtime.detailCheckSummary = "等待详情复筛"
            runtime.formCheckSummary = "等待报名信息校验"
            if (activateEnrollmentNode(
                    candidate.registerNode,
                    expectedText = "报名",
                    allowedStates = setOf(AutomationState.SCANNING_TASKS),
                    label = "打开任务：${candidate.task.title}",
                )
            ) {
                enterState(AutomationState.OPENING_TASK_DETAIL, "等待任务详情")
                return
            }
            updateCurrentResult("报名入口不可点击", "打开失败")
            runtime.enrollmentFailedCount++
            currentTaskSignature?.let(processedTaskSignatures::add)
            currentTaskSignature = null
            runtime.currentTaskSignature = null
            runtime.currentTaskTitle = null
        }

        runtime.lastMessage = if (newResults.isEmpty()) {
            "当前页没有新的任务卡片"
        } else {
            "本页解析 ${newResults.size} 项，符合 ${newResults.count { it.eligible }} 项"
        }
        enterState(AutomationState.SCROLLING_TASKS, runtime.lastMessage)
    }

    private fun scanVisibleTasks(root: AccessibilityNodeInfo): Pair<List<PreviewTaskResult>, EnrollmentCandidate?> {
        val actionTexts = DewuSelectors.LIST_TASK_ACTIONS
        val actionNodes = NodeUtils.findAllByTexts(root, actionTexts)
            .filter { nodeLabel(it) in actionTexts && isSafeTaskActionNode(it) }
        val results = mutableListOf<PreviewTaskResult>()
        var candidate: EnrollmentCandidate? = null

        for (actionNode in actionNodes) {
            val actionText = nodeLabel(actionNode)
            val cardRoot = findTaskCardRoot(actionNode, actionText)
            if (cardRoot == null) {
                runtime.parseFailedCount++
                log("TASK_SCAN rejected=card-boundary-not-unique action=$actionText button=${NodeUtils.bounds(actionNode)}")
                continue
            }
            val rawText = NodeUtils.collectText(cardRoot, maxNodes = 100)
            val cardBounds = NodeUtils.bounds(cardRoot)?.toShortString().orEmpty()
            val rawSignature = rawText.replace(Regex("\\s+"), " ").take(1_200).hashCode().toString()
            if (rawText.isBlank()) continue

            val task = TaskCardParser.parse(rawText)
            if (task == null) {
                if (visitedTaskSignatures.add("raw:$rawSignature")) {
                    runtime.parseFailedCount++
                }
                continue
            }
            if (task.signature in processedTaskSignatures || finalConfirmationGuard.hasAttempted(task.signature)) {
                log("TASK_SCAN skipped=already-processed title=${task.title.take(80)}")
                continue
            }

            val eligibility = if (TaskActionPolicy.isSubscriptionReminder(actionText)) {
                TaskEligibility(
                    eligible = false,
                    reason = "尚未上架（订阅提醒）",
                    matchedField = "任务状态",
                    matchedWord = DewuSelectors.SUBSCRIBE_REMINDER,
                )
            } else {
                TaskEligibilityEvaluator.evaluate(task, config)
            }
            val result = PreviewTaskResult(
                signature = task.signature,
                title = task.title,
                rewardText = task.rewardAmount?.let { "现金奖励 ${formatReward(it)}" } ?: "现金奖励未识别",
                capacityText = if (task.registeredCount != null && task.capacity != null) {
                    "${task.registeredCount}/${task.capacity}人"
                } else {
                    "名额未识别"
                },
                deadlineText = task.deadlineText ?: "截止时间未识别",
                eligible = eligibility.eligible,
                reason = eligibility.reason,
                enrollmentStatus = if (actionText == DewuSelectors.SUBSCRIBE_REMINDER) "未点击" else "未报名",
                matchedField = eligibility.matchedField,
                matchedWord = eligibility.matchedWord,
            )
            upsertTaskResult(result)
            if (visitedTaskSignatures.add("task:${task.signature}")) {
                results += result
                runtime.scannedCount++
                if (result.eligible) runtime.eligibleCount++ else runtime.excludedCount++
                log(
                    "TASK_SCAN title=${task.title.take(80)} reward=${result.rewardText} " +
                        "range=${TaskEligibilityEvaluator.formatReward(config.minPrice)}-" +
                        "${TaskEligibilityEvaluator.formatReward(config.maxPrice)} " +
                        "eligible=${eligibility.eligible} reason=${eligibility.reason} bounds=$cardBounds",
                )
            }

            if (TaskActionPolicy.isSubscriptionReminder(actionText)) {
                log("TASK_SCAN protected=subscribe-reminder title=${task.title.take(80)}")
            } else if (TaskActionPolicy.canOpenTask(actionText) && eligibility.eligible && candidate == null) {
                candidate = EnrollmentCandidate(task, result, actionNode)
            }
        }
        return results to candidate
    }

    private fun findTaskCardRoot(seed: AccessibilityNodeInfo, actionText: String): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = seed
        repeat(7) {
            val candidate = current ?: return null
            val cardText = NodeUtils.collectText(candidate, maxNodes = 100)
            val compactText = cardText.replace(Regex("\\s*\\|\\s*"), "")
            val hasCapacity = TASK_CAPACITY_PATTERN.containsMatchIn(compactText)
            val hasCashReward = cardText.contains("现金奖励") &&
                (cardText.contains("¥") || cardText.contains("￥"))
            val exactActionCount = NodeUtils.findAllByTexts(candidate, listOf(actionText))
                .count { nodeLabel(it) == actionText && isSafeTaskActionNode(it) }
            if (hasCapacity && hasCashReward && exactActionCount == 1) return candidate
            if (exactActionCount > 1) return null
            current = candidate.parent
        }
        return null
    }

    private fun handleScrollTasks() {
        if (runtime.listScrollCount < config.maxListScrolls) {
            runtime.listScrollCount++
            if (performVerticalSwipe(up = true, label = "任务列表下滑 ${runtime.listScrollCount}/${config.maxListScrolls}")) {
                delayByRefreshWindow()
                enterState(AutomationState.SCANNING_TASKS, "继续扫描任务")
            }
            return
        }

        finish(
            "本轮 ${config.sortMode} 已扫描当前页并下滑 ${runtime.listScrollCount} 次，" +
                "完成 ${EnrollmentRunPolicy.completedCount(config.finalConfirmationEnabled, runtime.rehearsalCompletedCount, runtime.enrollmentSuccessCount)}/" +
                "${config.targetEnrollmentCount} 个任务",
        )
    }

    private fun handleOpeningTaskDetail(root: AccessibilityNodeInfo?) {
        if (isTaskDetail(root)) {
            accumulatedDetailSegments.clear()
            detailScrollAttempts = 0
            enterState(AutomationState.VALIDATING_TASK_DETAIL, "先校验内容类型与达人要求")
            return
        }
        if (elapsedInState() > PAGE_TIMEOUT_MS) {
            if (isBrandPage(root) || isBrandShell(root)) {
                runtime.enrollmentFailedCount++
                updateCurrentResult("点击后仍停留在商单列表", "详情打开失败")
                currentTaskSignature?.let(processedTaskSignatures::add)
                currentTaskSignature = null
                runtime.currentTaskSignature = null
                runtime.currentTaskTitle = null
                enterState(AutomationState.SCROLLING_TASKS, "任务详情未打开，保持在商单列表继续查找")
            } else {
                fail("点击任务后进入未知页面；为避免错误返回或报名已停止")
            }
        }
    }

    private fun handleValidatingTaskDetail(root: AccessibilityNodeInfo?) {
        if (!isTaskDetail(root)) return
        collectCurrentDetailSegments(root)
        val detail = TaskDetailParser.parse(accumulatedDetailSegments.toList(), runtime.currentTaskTitle)
        if (detail == null) {
            if (elapsedInState() > PAGE_TIMEOUT_MS) {
                abandonCurrentTask("任务详情解析失败", "详情解析失败")
            }
            return
        }
        updateCurrentResult(
            reason = "正在检查内容类型与达人要求",
            status = "详情复筛中",
            contentTypeText = detail.contentType.displayName,
        )
        if (detail.contentType == TaskContentType.VIDEO_ONLY) {
            excludeCurrentTask(
                TaskEligibility(
                    false,
                    "内容类型为仅视频",
                    matchedField = "内容类型",
                    matchedWord = "仅视频",
                ),
                status = "仅视频",
                contentTypeText = detail.contentType.displayName,
            )
            return
        }
        if (detail.contentType == TaskContentType.UNKNOWN) {
            if (elapsedInState() > 3_000L) {
                excludeCurrentTask(
                    TaskEligibility(false, "内容类型未识别", matchedField = "内容类型"),
                    status = "内容类型未识别",
                    contentTypeText = detail.contentType.displayName,
                )
            }
            return
        }
        if (!detail.rawText.contains("达人要求")) {
            if (elapsedInState() > PAGE_TIMEOUT_MS) {
                excludeCurrentTask(
                    TaskEligibility(false, "达人要求未识别", matchedField = "达人要求"),
                    status = "详情排除",
                )
            }
            return
        }

        val eligibility = TaskEligibilityEvaluator.evaluateDetailOverview(detail, config)
        if (!eligibility.eligible) {
            excludeCurrentTask(
                eligibility,
                status = if (detail.contentType == TaskContentType.VIDEO_ONLY) "仅视频" else "达人要求排除",
                contentTypeText = detail.contentType.displayName,
            )
            return
        }

        runtime.detailCheckSummary = "内容类型 ${detail.contentType.displayName}；达人要求通过，继续检查拍摄要求"
        updateCurrentResult(
            reason = runtime.detailCheckSummary,
            status = "查找拍摄要求",
            contentTypeText = detail.contentType.displayName,
        )
        enterState(AutomationState.COLLECTING_SHOOTING_REQUIREMENTS, "达人要求通过，读取合作详情中的拍摄要求")
    }

    private fun handleCollectingShootingRequirements(root: AccessibilityNodeInfo?) {
        if (!isTaskDetail(root)) return
        collectCurrentDetailSegments(root)
        val detail = TaskDetailParser.parse(accumulatedDetailSegments.toList(), runtime.currentTaskTitle)
        if (detail == null) {
            if (elapsedInState() > PAGE_TIMEOUT_MS) {
                abandonCurrentTask("累计详情内容仍无法解析", "详情解析失败")
            }
            return
        }

        if (!detail.rawText.contains("拍摄要求")) {
            if (detailScrollAttempts >= MAX_DETAIL_SCROLLS || elapsedInState() > PAGE_TIMEOUT_MS) {
                excludeCurrentTask(
                    TaskEligibility(false, "合作详情中的拍摄要求未识别", matchedField = "拍摄要求"),
                    status = "拍摄要求未识别",
                    contentTypeText = detail.contentType.displayName,
                )
                return
            }
            if (performVerticalSwipe(up = true, label = "详情下滑查找拍摄要求 ${detailScrollAttempts + 1}/$MAX_DETAIL_SCROLLS")) {
                detailScrollAttempts++
                notBeforeAt = SystemClock.elapsedRealtime() + 900L
            }
            return
        }

        val eligibility = TaskEligibilityEvaluator.evaluateShootingRequirements(detail, config)
        if (!eligibility.eligible) {
            excludeCurrentTask(
                eligibility,
                status = "拍摄要求排除",
                contentTypeText = detail.contentType.displayName,
            )
            return
        }

        runtime.detailCheckSummary =
            "通过：${detail.contentType.displayName}；达人要求与拍摄要求均符合"
        updateCurrentResult(
            reason = runtime.detailCheckSummary,
            status = "详情通过",
            eligible = true,
            contentTypeText = detail.contentType.displayName,
            matchedField = null,
            matchedWord = null,
        )

        val button = findVisibleNodeByTexts(root, listOf(DewuSelectors.IMMEDIATE_REGISTER))
        if (activateEnrollmentNode(
                button,
                expectedText = DewuSelectors.IMMEDIATE_REGISTER,
                allowedStates = setOf(AutomationState.COLLECTING_SHOOTING_REQUIREMENTS),
                label = "详情复筛通过，进入报名信息",
            )
        ) {
            enterState(AutomationState.OPENING_ENROLLMENT_FORM, "等待确认报名信息")
        } else if (elapsedInState() > PAGE_TIMEOUT_MS) {
            abandonCurrentTask("详情页没有可用的立即报名按钮", "无报名入口")
        }
    }

    private fun handleOpeningEnrollmentForm(root: AccessibilityNodeInfo?) {
        val rawText = NodeUtils.collectText(root, maxNodes = 350)
        if (EnrollmentFormHandler.isEnrollmentForm(rawText)) {
            specSelectionAttempts = 0
            formSpecTarget = ""
            enterState(AutomationState.FILLING_ENROLLMENT_FORM, "校验地址并填写样品规格")
            return
        }
        if (elapsedInState() > PAGE_TIMEOUT_MS) {
            abandonCurrentTask("未进入确认报名信息页", "报名信息页打开失败")
        }
    }

    private fun handleFillingEnrollmentForm(root: AccessibilityNodeInfo?) {
        if (root == null) return
        val rawText = NodeUtils.collectText(root, maxNodes = 400)
        if (!EnrollmentFormHandler.isEnrollmentForm(rawText)) return
        if (!EnrollmentFormHandler.hasSelectedAddress(rawText)) {
            runtime.formCheckSummary = "失败：未检测到收货地址"
            abandonCurrentTask("未检测到已选择的收货地址", "缺少收货地址")
            return
        }

        val specs = TaskEligibilityEvaluator.splitTerms(config.sizeSpec)
        if (specs.isEmpty()) {
            runtime.formCheckSummary = "失败：未配置样品规格"
            abandonCurrentTask("未配置样品规格，为避免误选已跳过", "缺少样品规格")
            return
        }

        val targetSpec = specs.first()
        formSpecTarget = targetSpec
        val selectedSpecNode = findConfirmedSpecNode(root, targetSpec)
        if (selectedSpecNode != null && hasUsableConfirmEnrollmentButton(root)) {
            runtime.formCheckSummary = "通过：地址已存在，规格已选择 $targetSpec"
            recordNodeAction(selectedSpecNode, "验证样品规格已显示在当前选择区：$targetSpec")
            enterState(AutomationState.SUBMITTING_ENROLLMENT_FORM, "报名信息校验通过，准备确认报名")
            return
        }

        val editable = NodeUtils.findAll(root) { it.isEditable && it.isEnabled }.firstOrNull(::isVisible)
        if (editable != null && editable.text?.toString()?.trim() == targetSpec) {
            runtime.formCheckSummary = "通过：地址已存在，规格已填写 $targetSpec"
            recordNodeAction(editable, "验证样品规格输入框：$targetSpec")
            enterState(AutomationState.SUBMITTING_ENROLLMENT_FORM, "报名信息校验通过，准备确认报名")
            return
        }
        if (editable != null && specSelectionAttempts < 3 && NodeUtils.setText(editable, targetSpec)) {
            specSelectionAttempts++
            recordNodeAction(editable, "填写样品规格：$targetSpec")
            touchAction("填写样品规格：$targetSpec")
            notBeforeAt = SystemClock.elapsedRealtime() + 800L
            return
        }

        val specNode = findSelectableSpecNode(root, targetSpec)
        if (specNode != null) {
            if (specSelectionAttempts >= 3) {
                runtime.formCheckSummary = "失败：规格 $targetSpec 未显示在当前选择区"
                abandonCurrentTask("规格选择连续 3 次未生效", "规格填写失败")
                return
            }
            if (activateFormControlNode(specNode, "选择样品规格：${nodeLabel(specNode)}")) {
                specSelectionAttempts++
                notBeforeAt = SystemClock.elapsedRealtime() + 800L
            }
            return
        }

        if (specSelectionAttempts >= 3 || editable == null) {
            runtime.formCheckSummary = "失败：没有可验证的规格控件"
            abandonCurrentTask("没有找到可填写的样品规格控件", "规格填写失败")
            return
        }
    }

    private fun handleSubmittingEnrollmentForm(root: AccessibilityNodeInfo?) {
        val rawText = NodeUtils.collectText(root, maxNodes = 400)
        if (!EnrollmentFormHandler.isEnrollmentForm(rawText)) return
        val button = findVisibleNodeByTexts(root, listOf(DewuSelectors.CONFIRM_ENROLLMENT))
        if (activateEnrollmentNode(
                button,
                expectedText = DewuSelectors.CONFIRM_ENROLLMENT,
                allowedStates = setOf(AutomationState.SUBMITTING_ENROLLMENT_FORM),
                label = "提交报名信息",
            )
        ) {
            rehearsalNoticeObservedAt = 0L
            rehearsalCancelBackAttempted = false
            enterState(AutomationState.CONFIRMING_IRREVERSIBLE_NOTICE, "等待报名须知确认")
        } else if (elapsedInState() > PAGE_TIMEOUT_MS) {
            abandonCurrentTask("确认报名按钮不可用", "报名提交失败")
        }
    }

    private fun handleIrreversibleNotice(root: AccessibilityNodeInfo?) {
        val rawText = NodeUtils.collectText(root, maxNodes = 400)
        if (!EnrollmentFormHandler.isIrreversibleNotice(rawText)) {
            if (elapsedInState() > PAGE_TIMEOUT_MS) {
                abandonCurrentTask("未识别到标准“报名后无法取消”确认弹窗", "报名须知异常")
            }
            return
        }

        val signature = currentTaskSignature
        if (signature == null) {
            fail("最终确认缺少当前任务签名")
            return
        }

        when (EnrollmentRunPolicy.finalNoticeDecision(config.finalConfirmationEnabled)) {
            FinalNoticeDecision.REHEARSE_CANCEL -> {
                val now = SystemClock.elapsedRealtime()
                if (rehearsalNoticeObservedAt == 0L) {
                    rehearsalNoticeObservedAt = now
                    runtime.lastMessage = "演练已到最终确认，停留 2 秒后取消"
                    log("REHEARSAL_NOTICE_REACHED task=$signature finalConfirmClicks=${runtime.finalConfirmationClickCount}")
                    return
                }
                if (now - rehearsalNoticeObservedAt < 2_000L) return

                val cancel = findBottommostVisibleNodeByText(root, DewuSelectors.IRREVERSIBLE_CANCEL)
                if (tapWebNode(cancel, "演练模式取消最终报名")) {
                    enterState(
                        AutomationState.VERIFYING_REHEARSAL_CANCELLATION,
                        "验证最终报名弹窗已取消",
                    )
                } else if (elapsedInState() > PAGE_TIMEOUT_MS) {
                    fail("演练模式无法安全点击最终弹窗的取消按钮")
                }
            }

            FinalNoticeDecision.CONFIRM -> {
                if (!finalConfirmationGuard.tryAcquire(runtime.runId, signature)) {
                    fail("当前任务已执行过最终确认，禁止重复点击")
                    return
                }
                val confirm = findBottommostVisibleNodeByText(root, DewuSelectors.IRREVERSIBLE_CONFIRM)
                if (activateEnrollmentNode(
                        confirm,
                        expectedText = DewuSelectors.IRREVERSIBLE_CONFIRM,
                        allowedStates = setOf(AutomationState.CONFIRMING_IRREVERSIBLE_NOTICE),
                        label = "确认报名须知（报名后无法取消）",
                    )
                ) {
                    runtime.finalConfirmationClickCount++
                    processedTaskSignatures += signature
                    enterState(AutomationState.VERIFYING_ENROLLMENT_RESULT, "等待报名结果")
                } else {
                    fail("当前任务最终确认锁已占用，但确认按钮不可点击；为防止重复操作已停止")
                }
            }
        }
    }

    private fun handleVerifyRehearsalCancellation(root: AccessibilityNodeInfo?) {
        val rawText = NodeUtils.collectText(root, maxNodes = 400)
        if (!EnrollmentFormHandler.isIrreversibleNotice(rawText)) {
            val signature = currentTaskSignature
            if (signature == null) {
                fail("演练取消后缺少当前任务签名")
                return
            }
            if (processedTaskSignatures.add(signature)) {
                runtime.rehearsalCompletedCount++
            }
            updateCurrentResult("演练通过，未报名", "已演练，未报名")
            finishAfterReturnToTaskList = EnrollmentRunPolicy.targetReached(
                finalConfirmationEnabled = false,
                rehearsalCompletedCount = runtime.rehearsalCompletedCount,
                enrollmentSuccessCount = runtime.enrollmentSuccessCount,
                targetTaskCount = config.targetEnrollmentCount,
            )
            returnBackAttempts = 0
            performBack("演练完成，返回商单继续")
            enterState(
                AutomationState.RETURNING_TO_TASK_LIST,
                "演练 ${runtime.rehearsalCompletedCount}/${config.targetEnrollmentCount}，返回商单列表",
            )
            return
        }
        if (elapsedInState() > PAGE_TIMEOUT_MS) {
            fail("演练取消后最终报名弹窗仍未消失")
        } else if (elapsedInState() > 3_000L && !rehearsalCancelBackAttempted) {
            rehearsalCancelBackAttempted = true
            performBack("取消按钮未关闭弹窗，执行一次安全返回")
        }
    }

    private fun handleEnrollmentResult(root: AccessibilityNodeInfo?) {
        val rawText = NodeUtils.collectText(root, maxNodes = 400)
        if (EnrollmentFormHandler.isEnrollmentSuccess(rawText)) {
            runtime.enrollmentSuccessCount++
            currentTaskSignature?.let(processedTaskSignatures::add)
            updateCurrentResult("报名成功，待品牌方确认", "已报名")
            finishAfterReturnToTaskList = EnrollmentRunPolicy.targetReached(
                finalConfirmationEnabled = true,
                rehearsalCompletedCount = runtime.rehearsalCompletedCount,
                enrollmentSuccessCount = runtime.enrollmentSuccessCount,
                targetTaskCount = config.targetEnrollmentCount,
            )
            returnBackAttempts = 0
            performBack("报名成功，返回商单继续")
            enterState(AutomationState.RETURNING_TO_TASK_LIST, "报名成功，返回商单列表")
            return
        }
        if (elapsedInState() > PAGE_TIMEOUT_MS) {
            fail("已确认不可取消报名，但未识别到明确成功结果；为防止重复报名已停止")
        }
    }

    private fun handleReturnToTaskList(root: AccessibilityNodeInfo?) {
        if (isBrandPage(root) || isBrandShell(root)) {
            currentTaskSignature = null
            runtime.currentTaskSignature = null
            runtime.currentTaskTitle = null
            returnBackAttempts = 0
            if (finishAfterReturnToTaskList) {
                finishAfterReturnToTaskList = false
                val completed = EnrollmentRunPolicy.completedCount(
                    config.finalConfirmationEnabled,
                    runtime.rehearsalCompletedCount,
                    runtime.enrollmentSuccessCount,
                )
                val label = if (config.finalConfirmationEnabled) "真实报名" else "演练任务"
                finish("已完成 $completed/${config.targetEnrollmentCount} 个$label")
                return
            }
            enterState(AutomationState.SCANNING_TASKS, "已返回商单，继续处理当前页其它初筛通过任务")
            return
        }
        if (elapsedInState() > 1_200L && returnBackAttempts < 4) {
            returnBackAttempts++
            performBack("返回商单列表 ${returnBackAttempts}/4")
            stateEnteredAt = SystemClock.elapsedRealtime()
        } else if (returnBackAttempts >= 4 && elapsedInState() > PAGE_TIMEOUT_MS) {
            fail("连续返回后仍未识别到商单列表")
        }
    }

    private fun abandonCurrentTask(reason: String, status: String) {
        runtime.enrollmentFailedCount++
        updateCurrentResult(reason, status)
        currentTaskSignature?.let(processedTaskSignatures::add)
        returnBackAttempts = 0
        performBack("$reason，返回商单")
        enterState(AutomationState.RETURNING_TO_TASK_LIST, reason)
    }

    private fun excludeCurrentTask(
        eligibility: TaskEligibility,
        status: String,
        contentTypeText: String? = null,
    ) {
        runtime.detailCheckSummary = eligibility.reason
        runtime.eligibleCount = (runtime.eligibleCount - 1).coerceAtLeast(0)
        runtime.excludedCount++
        updateCurrentResult(
            reason = eligibility.reason,
            status = status,
            eligible = false,
            contentTypeText = contentTypeText,
            matchedField = eligibility.matchedField,
            matchedWord = eligibility.matchedWord,
        )
        currentTaskSignature?.let(processedTaskSignatures::add)
        returnBackAttempts = 0
        performBack("${eligibility.reason}，返回商单")
        enterState(AutomationState.RETURNING_TO_TASK_LIST, eligibility.reason)
    }

    private fun collectCurrentDetailSegments(root: AccessibilityNodeInfo?) {
        NodeUtils.collectTextValues(root, maxNodes = 500)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach(accumulatedDetailSegments::add)
    }

    private fun upsertTaskResult(result: PreviewTaskResult) {
        runtime.taskResults = TaskResultLedger.upsert(runtime.taskResults, result)
    }

    private fun updateCurrentResult(
        reason: String,
        status: String,
        eligible: Boolean? = null,
        contentTypeText: String? = null,
        matchedField: String? = null,
        matchedWord: String? = null,
    ) {
        val signature = currentTaskSignature ?: return
        runtime.taskResults = runtime.taskResults.map { result ->
            if (result.signature == signature) {
                result.copy(
                    eligible = eligible ?: result.eligible,
                    reason = reason,
                    enrollmentStatus = status,
                    contentTypeText = contentTypeText ?: result.contentTypeText,
                    matchedField = matchedField ?: result.matchedField,
                    matchedWord = matchedWord ?: result.matchedWord,
                )
            } else {
                result
            }
        }
    }

    private fun performBack(label: String): Boolean {
        if (!canAct()) return false
        val performed = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        if (performed) touchAction(label)
        return performed
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String =
        node.text?.toString()?.trim().orEmpty().ifBlank {
            node.contentDescription?.toString()?.trim().orEmpty()
        }

    private fun formatReward(amount: Double): String =
        if (amount % 1.0 == 0.0) "¥${amount.toInt()}" else "¥$amount"

    private fun isDewuRoot(root: AccessibilityNodeInfo?): Boolean =
        root?.packageName?.toString() == DewuSelectors.PACKAGE_NAME

    private fun isHome(root: AccessibilityNodeInfo?): Boolean =
        isDewuRoot(root) &&
            NodeUtils.hasAnyText(root, DewuSelectors.HOME_MARKERS) &&
            NodeUtils.hasAnyText(root, DewuSelectors.PROFILE_TAB)

    private fun isProfile(root: AccessibilityNodeInfo?): Boolean =
        isDewuRoot(root) && (
            NodeUtils.hasAnyText(root, DewuSelectors.CREATION_CENTER) ||
                (NodeUtils.hasAnyText(root, DewuSelectors.BRAND_CONTEXT) &&
                    NodeUtils.hasAnyText(root, DewuSelectors.PROFILE_TAB))
            )

    private fun isTaskDetail(root: AccessibilityNodeInfo?): Boolean =
        isDewuRoot(root) && NodeUtils.hasAnyText(root, DewuSelectors.TASK_DETAIL_MARKERS)

    private fun isBrandPage(root: AccessibilityNodeInfo?): Boolean =
        isDewuRoot(root) &&
            NodeUtils.hasAnyText(root, DewuSelectors.SORT_ENTRY) &&
            (
                NodeUtils.hasAnyText(root, DewuSelectors.BRAND_PAGE_MARKERS) ||
                    (NodeUtils.hasAnyText(root, DewuSelectors.REGISTER_BUTTONS) &&
                        NodeUtils.hasAnyText(root, listOf("现金奖励")))
                )

    private fun isBrandShell(root: AccessibilityNodeInfo?): Boolean =
        isDewuRoot(root) &&
            NodeUtils.hasAnyText(root, listOf("品牌合作")) &&
            !isTaskDetail(root) &&
            !EnrollmentFormHandler.isEnrollmentForm(NodeUtils.collectText(root, maxNodes = 220))

    private fun isSortMenuOpen(root: AccessibilityNodeInfo?): Boolean =
        listOf("综合排序", "最近发布", "即将截止").all { option ->
            NodeUtils.findAllByTexts(root, listOf(option)).any { node ->
                nodeLabel(node) == option && isVisible(node) &&
                    (NodeUtils.bounds(node)?.centerY() ?: 0) > ScreenInfo.from(service).heightPx * 0.10f
            }
        }

    private fun isFilterBarValue(root: AccessibilityNodeInfo?, value: String): Boolean {
        val screen = ScreenInfo.from(service)
        return NodeUtils.findAllByTexts(root, listOf(value)).any { node ->
            val bounds = NodeUtils.bounds(node)
            nodeLabel(node) == value && isVisible(node) && bounds != null &&
                bounds.centerY() in (screen.heightPx * 0.05f).toInt()..(screen.heightPx * 0.22f).toInt()
        }
    }

    private fun isSelectedCategoryInFilterBar(
        root: AccessibilityNodeInfo?,
        target: String,
    ): Boolean {
        val screen = ScreenInfo.from(service)
        return NodeUtils.findAllByTexts(root, listOf(target)).any { node ->
            val bounds = NodeUtils.bounds(node)
            bounds != null && isVisible(node) && CategorySelectionRules.isHeaderValue(
                label = nodeLabel(node),
                target = target,
                centerY = bounds.centerY(),
                screenHeight = screen.heightPx,
            )
        }
    }

    private fun currentCategoryInFilterBar(root: AccessibilityNodeInfo?): String? {
        val screen = ScreenInfo.from(service)
        return DewuSelectors.PRODUCT_CATEGORIES.firstOrNull { category ->
            NodeUtils.findAllByTexts(root, listOf(category)).any { node ->
                val bounds = NodeUtils.bounds(node)
                bounds != null && isVisible(node) && CategorySelectionRules.isHeaderValue(
                    label = nodeLabel(node),
                    target = category,
                    centerY = bounds.centerY(),
                    screenHeight = screen.heightPx,
                )
            }
        }
    }

    private fun findCategoryPanelOption(
        root: AccessibilityNodeInfo?,
        target: String,
    ): AccessibilityNodeInfo? {
        val screen = ScreenInfo.from(service)
        val confirmTop = findBottommostVisibleNodeByText(root, DewuSelectors.FILTER_CONFIRM.first())
            ?.let(NodeUtils::bounds)
            ?.top
            ?: return null
        return NodeUtils.findAllByTexts(root, listOf(target))
            .filter { node ->
                val bounds = NodeUtils.bounds(node)
                bounds != null && isVisible(node) && CategorySelectionRules.isPanelOption(
                    label = nodeLabel(node),
                    target = target,
                    centerY = bounds.centerY(),
                    screenHeight = screen.heightPx,
                    confirmTop = confirmTop,
                )
            }
            .minByOrNull { NodeUtils.bounds(it)?.centerY() ?: Int.MAX_VALUE }
    }

    private fun isFilterPanelOpen(root: AccessibilityNodeInfo?): Boolean =
        NodeUtils.hasAnyText(root, listOf("任务类型")) &&
            NodeUtils.hasAnyText(root, listOf("产品类目")) &&
            findBottommostVisibleNodeByText(root, "确定") != null &&
            DewuSelectors.PRODUCT_CATEGORIES.count { category ->
                NodeUtils.findAllByTexts(root, listOf(category)).any { node ->
                    nodeLabel(node) == category && isVisible(node)
                }
            } >= 3

    private fun hasVisibleTaskList(root: AccessibilityNodeInfo?): Boolean =
        NodeUtils.findAllByTexts(root, DewuSelectors.LIST_TASK_ACTIONS).any { node ->
            nodeLabel(node) in DewuSelectors.LIST_TASK_ACTIONS && isVisible(node)
        } || NodeUtils.findAllByTexts(root, listOf("现金奖励")).any(::isVisible)

    private fun clickText(root: AccessibilityNodeInfo?, texts: Collection<String>, label: String): Boolean {
        if (texts.any { it in DewuSelectors.REGISTER_BUTTONS || it in DewuSelectors.APPLY_TO_JOIN }) {
            log("安全拦截：拒绝点击 $texts")
            return false
        }
        val node = findVisibleNodeByTexts(root, texts)
        return activateNode(node, label)
    }

    private fun findVisibleNodeByTexts(
        root: AccessibilityNodeInfo?,
        texts: Collection<String>,
    ): AccessibilityNodeInfo? {
        val expected = texts.map(String::trim).filter(String::isNotEmpty)
        return NodeUtils.findAllByTexts(root, expected).firstOrNull { node ->
            val label = nodeLabel(node)
            expected.any { it == label } && isVisible(node)
        } ?: NodeUtils.findFirstByTexts(root, expected)
    }

    private fun findBottommostVisibleNodeByText(
        root: AccessibilityNodeInfo?,
        text: String,
    ): AccessibilityNodeInfo? = NodeUtils.findAllByTexts(root, listOf(text))
        .filter { nodeLabel(it) == text && isVisible(it) }
        .maxByOrNull { NodeUtils.bounds(it)?.centerY() ?: Int.MIN_VALUE }

    private fun isVisible(node: AccessibilityNodeInfo): Boolean {
        val bounds = NodeUtils.bounds(node) ?: return false
        val screen = ScreenInfo.from(service)
        return bounds.width() > 0 && bounds.height() > 0 &&
            bounds.centerX() in 0..screen.widthPx &&
            bounds.centerY() in (screen.heightPx * 0.03f).toInt()..(screen.heightPx * 0.98f).toInt() &&
            node.isVisibleToUser
    }

    private fun isSafeTaskActionNode(node: AccessibilityNodeInfo): Boolean {
        if (!isVisible(node)) return false
        val bounds = NodeUtils.bounds(node) ?: return false
        val screen = ScreenInfo.from(service)
        return bounds.centerX() >= (screen.widthPx * 0.62f).toInt() && bounds.centerY() in
            (screen.heightPx * 0.16f).toInt()..(screen.heightPx * 0.90f).toInt()
    }

    private fun activateNode(node: AccessibilityNodeInfo?, label: String): Boolean {
        val candidate = node ?: return false
        val text = nodeLabel(candidate)
        if (text in DewuSelectors.REGISTER_BUTTONS || text in DewuSelectors.APPLY_TO_JOIN) {
            log("安全拦截：拒绝激活 $text")
            return false
        }
        if (NodeUtils.clickNode(candidate)) {
            touchAction(label)
            return true
        }
        val bounds = NodeUtils.bounds(candidate) ?: return false
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        return performTap(bounds.centerX().toFloat(), bounds.centerY().toFloat(), label)
    }

    private fun activateEnrollmentNode(
        node: AccessibilityNodeInfo?,
        expectedText: String,
        allowedStates: Set<AutomationState>,
        label: String,
    ): Boolean {
        val candidate = node ?: return false
        val trustedBottomAction = isTrustedBottomEnrollmentAction(candidate, expectedText)
        if (runtime.state !in allowedStates || nodeLabel(candidate) != expectedText ||
            (!isVisible(candidate) && !trustedBottomAction)
        ) {
            log("报名安全拦截：state=${runtime.state}, expected=$expectedText, actual=${nodeLabel(candidate)}")
            return false
        }
        val bounds = NodeUtils.bounds(candidate) ?: return false
        recordNodeAction(candidate, label)
        return performTap(bounds.centerX().toFloat(), bounds.centerY().toFloat(), label)
    }

    private fun isTrustedBottomEnrollmentAction(
        node: AccessibilityNodeInfo,
        expectedText: String,
    ): Boolean {
        if (expectedText != DewuSelectors.CONFIRM_ENROLLMENT || !node.isEnabled) return false
        val bounds = NodeUtils.bounds(node) ?: return false
        val screen = ScreenInfo.from(service)
        return bounds.width() >= (screen.widthPx * 0.80f).toInt() &&
            bounds.centerY() >= (screen.heightPx * 0.88f).toInt() &&
            bounds.centerY() <= (screen.heightPx * 1.02f).toInt()
    }

    private fun tapWebNode(
        node: AccessibilityNodeInfo?,
        label: String,
        useRightEdge: Boolean = false,
    ): Boolean {
        val candidate = node ?: return false
        if (!isVisible(candidate)) return false
        val bounds = NodeUtils.bounds(candidate) ?: return false
        recordNodeAction(candidate, label)
        val x = if (useRightEdge) {
            bounds.right - (bounds.width() * 0.12f).coerceAtLeast(12f)
        } else {
            bounds.centerX().toFloat()
        }
        return performTap(x, bounds.centerY().toFloat(), label)
    }

    private fun activateFormControlNode(node: AccessibilityNodeInfo?, label: String): Boolean {
        val candidate = node ?: return false
        if (!canAct() || !isVisible(candidate)) return false
        recordNodeAction(candidate, label)
        if (NodeUtils.clickNode(candidate)) {
            touchAction(label)
            return true
        }
        val bounds = NodeUtils.bounds(candidate) ?: return false
        return performTap(bounds.centerX().toFloat(), bounds.centerY().toFloat(), label)
    }

    private fun isNodeOrAncestorSelected(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        repeat(4) {
            val candidate = current ?: return false
            if (candidate.isSelected || candidate.isChecked) return true
            current = candidate.parent
        }
        return false
    }

    /**
     * 得物的报名表单是 WebView：历史规格标签不会回传 selected/checked，点击后当前规格
     * 会单独显示在“样品规格”行右侧。右侧当前值 + 可用“确认报名”按钮共同作为后置条件，
     * 避免把左侧的历史记录标签误判为已选择。
     */
    private fun findConfirmedSpecNode(
        root: AccessibilityNodeInfo?,
        targetSpec: String,
    ): AccessibilityNodeInfo? {
        val screen = ScreenInfo.from(service)
        return NodeUtils.findAllByTexts(root, listOf(targetSpec))
            .filter { node ->
                val bounds = NodeUtils.bounds(node)
                nodeLabel(node) == targetSpec && isVisible(node) && bounds != null &&
                    EnrollmentFormHandler.isConfirmedSpecBounds(
                        screen.widthPx,
                        screen.heightPx,
                        bounds.left,
                        bounds.top,
                        bounds.right,
                        bounds.bottom,
                    )
            }
            .minByOrNull { NodeUtils.bounds(it)?.centerY() ?: Int.MAX_VALUE }
    }

    private fun findSelectableSpecNode(
        root: AccessibilityNodeInfo?,
        targetSpec: String,
    ): AccessibilityNodeInfo? {
        val screen = ScreenInfo.from(service)
        return NodeUtils.findAllByTexts(root, listOf(targetSpec))
            .filter { node ->
                val bounds = NodeUtils.bounds(node)
                nodeLabel(node) == targetSpec && isVisible(node) && bounds != null &&
                    (bounds.left < (screen.widthPx * 0.25f).toInt() ||
                        bounds.right < (screen.widthPx * 0.85f).toInt())
            }
            .minByOrNull { NodeUtils.bounds(it)?.centerY() ?: Int.MAX_VALUE }
    }

    private fun hasUsableConfirmEnrollmentButton(root: AccessibilityNodeInfo?): Boolean =
        NodeUtils.findAllByTexts(root, listOf(DewuSelectors.CONFIRM_ENROLLMENT)).any { node ->
            nodeLabel(node) == DewuSelectors.CONFIRM_ENROLLMENT && isVisible(node) && node.isEnabled
        }

    private fun recordNodeAction(node: AccessibilityNodeInfo, label: String) {
        val bounds = NodeUtils.bounds(node)
        runtime.lastNodeText = nodeLabel(node)
        runtime.lastNodeBounds = bounds?.toShortString().orEmpty()
        log("NODE_ACTION label=$label text=${runtime.lastNodeText} bounds=${runtime.lastNodeBounds}")
    }

    private fun performVerticalSwipe(up: Boolean, label: String): Boolean {
        val screen = ScreenInfo.from(service)
        val x = screen.widthPx * 0.5f
        val startY = screen.heightPx * if (up) 0.76f else 0.30f
        val endY = screen.heightPx * if (up) 0.30f else 0.76f
        return performSwipe(x, startY, x, endY, 520L, label)
    }

    private fun performHorizontalSwipe(left: Boolean, label: String): Boolean {
        val screen = ScreenInfo.from(service)
        val y = screen.heightPx * 0.20f
        val startX = screen.widthPx * if (left) 0.80f else 0.20f
        val endX = screen.widthPx * if (left) 0.20f else 0.80f
        return performSwipe(startX, y, endX, y, 420L, label)
    }

    private fun performCategoryPanelSwipe(root: AccessibilityNodeInfo?, label: String): Boolean {
        val screen = ScreenInfo.from(service)
        val categoryLabel = NodeUtils.findAllByTexts(root, listOf(DewuSelectors.PRODUCT_CATEGORY))
            .filter { nodeLabel(it) == DewuSelectors.PRODUCT_CATEGORY && isVisible(it) }
            .maxByOrNull { NodeUtils.bounds(it)?.centerY() ?: Int.MIN_VALUE }
        val labelBounds = NodeUtils.bounds(categoryLabel) ?: return false
        val confirmTop = findBottommostVisibleNodeByText(root, DewuSelectors.FILTER_CONFIRM.first())
            ?.let(NodeUtils::bounds)
            ?.top
            ?: return false
        val y = (labelBounds.bottom + screen.heightPx * 0.055f)
            .coerceAtMost(confirmTop - screen.heightPx * 0.03f)
        return performSwipe(
            startX = screen.widthPx * 0.82f,
            startY = y,
            endX = screen.widthPx * 0.18f,
            endY = y,
            durationMs = 420L,
            label = "$label，横滑 ${categoryPanelSwipeCount + 1}/$MAX_CATEGORY_PANEL_SWIPES",
        )
    }

    private fun performSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        label: String,
    ): Boolean {
        if (!canAct()) return false
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        val dispatched = service.dispatchGesture(gesture, null, null)
        if (dispatched) touchAction(label)
        return dispatched
    }

    private fun performTap(x: Float, y: Float, label: String): Boolean {
        if (!canAct()) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        val dispatched = service.dispatchGesture(gesture, null, null)
        if (dispatched) touchAction(label)
        return dispatched
    }

    private fun enterState(state: AutomationState, message: String) {
        runtime.state = state
        runtime.lastMessage = message
        stateEnteredAt = SystemClock.elapsedRealtime()
        log(
            "STATE=$state run=${runtime.runId.take(8)} task=${currentTaskSignature ?: "none"} " +
                "retry=$postconditionAttempts finalConfirmClicks=${runtime.finalConfirmationClickCount} | $message",
        )
    }

    private fun elapsedInState(): Long = SystemClock.elapsedRealtime() - stateEnteredAt

    private fun canAct(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return now - runtime.lastActionAt >= 350L
    }

    private fun touchAction(message: String) {
        runtime.lastActionAt = SystemClock.elapsedRealtime()
        runtime.actionCount++
        runtime.lastMessage = message
        log("ACTION run=${runtime.runId.take(8)} state=${runtime.state} | $message")
    }

    private fun syncPostconditionAttempts() {
        runtime.postconditionRetryCount = postconditionAttempts
    }

    private fun delayByRefreshWindow() {
        val seconds = randomBetween(INTERNAL_REFRESH_MIN_SECONDS, INTERNAL_REFRESH_MAX_SECONDS)
        notBeforeAt = SystemClock.elapsedRealtime() + seconds * 1_000L
        log("等待页面刷新 ${seconds}s")
    }

    private fun randomBetween(min: Int, max: Int): Int {
        val low = minOf(min, max)
        val high = maxOf(min, max)
        if (low == high) return low
        return kotlin.random.Random.nextInt(low, high + 1)
    }

    private fun pauseForSecurity(message: String) {
        enterState(AutomationState.PAUSED_FOR_SECURITY, message)
        stopInternal(resetState = false)
        runtime.state = AutomationState.PAUSED_FOR_SECURITY
        runtime.lastMessage = message
    }

    private fun finish(message: String) {
        enterState(AutomationState.FINISHED, message)
        stopInternal(resetState = false)
        runtime.state = AutomationState.FINISHED
        runtime.lastMessage = message
    }

    private fun fail(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
        enterState(AutomationState.ERROR, message)
        stopInternal(resetState = false)
        runtime.state = AutomationState.ERROR
        runtime.lastMessage = message
    }

    private fun stopInternal(resetState: Boolean) {
        running = false
        handler.removeCallbacks(ticker)
        licenseManager.stopHeartbeat()
        if (resetState) runtime.state = AutomationState.IDLE
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }
}
