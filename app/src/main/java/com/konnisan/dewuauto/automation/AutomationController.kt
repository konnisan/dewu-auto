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
        private const val MAX_RECENT_RESULTS = 4
        private val TASK_CAPACITY_PATTERN = Regex("(?:(?:已)?报名[：:]?\\s*)?\\d+\\s*/\\s*\\d+\\s*人")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val licenseManager = LicenseManager(service)
    private val runtime = AutomationRuntime()
    private val singleEnrollmentGate = SingleEnrollmentGate()
    private val visitedTaskSignatures = LinkedHashSet<String>()

    private data class EnrollmentCandidate(
        val task: TaskCard,
        val result: PreviewTaskResult,
        val registerNode: AccessibilityNodeInfo,
    )

    private var config = AutomationConfig()
    private var running = false
    private var sortMenuOpened = false
    private var categoryStep = 0
    private var brandEntryStep = 0
    private var wrongMoreRecoveryCount = 0
    private var brandShellRecoveryCount = 0
    private var stateEnteredAt = 0L
    private var notBeforeAt = 0L
    private var lastPokeAt = 0L
    private var lastHomeDiagnosticAt = 0L
    private var currentTaskSignature: String? = null
    private var returnBackAttempts = 0
    private var contentSwipeTarget = 0
    private var contentSwipeCount = 0
    private var contentStayUntil = 0L
    private var pendingSortTarget = ""
    private var pendingSortWasInitial = true
    private var postconditionAttempts = 0
    private var specSelectionAttempts = 0
    private var formSpecTarget = ""

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
        singleEnrollmentGate.reset(runtime.runId)
        runtime.state = AutomationState.VERIFYING_LICENSE
        runtime.listScrollCount = 0
        runtime.sortPhase = SortPhase.RECENT
        runtime.roundCount = 0
        runtime.homeBrowsedCount = 0
        runtime.scannedCount = 0
        runtime.eligibleCount = 0
        runtime.excludedCount = 0
        runtime.parseFailedCount = 0
        runtime.enrollmentSuccessCount = 0
        runtime.enrollmentFailedCount = 0
        runtime.currentTaskSignature = null
        runtime.currentTaskTitle = null
        runtime.detailCheckSummary = "未检查"
        runtime.formCheckSummary = "未检查"
        runtime.postconditionRetryCount = 0
        runtime.lastNodeText = ""
        runtime.lastNodeBounds = ""
        runtime.operatorTokenExpiresAt = 0L
        runtime.finalConfirmationUsed = false
        runtime.requiresCreatorEnrollment = false
        runtime.recentResults = emptyList()
        runtime.actionCount = 0
        runtime.lastActionAt = 0L
        runtime.lastMessage = "验证卡密"
        visitedTaskSignatures.clear()
        sortMenuOpened = false
        categoryStep = 0
        brandEntryStep = 0
        wrongMoreRecoveryCount = 0
        brandShellRecoveryCount = 0
        notBeforeAt = 0L
        lastPokeAt = 0L
        lastHomeDiagnosticAt = 0L
        currentTaskSignature = null
        returnBackAttempts = 0
        contentSwipeTarget = 0
        contentSwipeCount = 0
        contentStayUntil = 0L
        pendingSortTarget = ""
        pendingSortWasInitial = true
        postconditionAttempts = 0
        specSelectionAttempts = 0
        formSpecTarget = ""
        running = true
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

    fun snapshot(): AutomationRuntime = runtime.copy(recentResults = runtime.recentResults.toList())

    fun authorizeFinalConfirmation(): Boolean {
        if (!running || runtime.state != AutomationState.AWAITING_OPERATOR_CONFIRMATION) return false
        val signature = currentTaskSignature ?: return false
        val now = SystemClock.elapsedRealtime()
        val granted = singleEnrollmentGate.grant(runtime.runId, signature, now)
        if (!granted) return false
        runtime.operatorTokenExpiresAt = singleEnrollmentGate.expiresAt()
        log("OPERATOR_TOKEN_GRANTED task=$signature expiresAt=${runtime.operatorTokenExpiresAt}")
        enterState(AutomationState.CONFIRMING_IRREVERSIBLE_NOTICE, "操作员已授权一次最终确认，返回得物")
        DewuLauncher.launch(service)
        notBeforeAt = now + 900L
        poke()
        return true
    }

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
            AutomationState.APPLYING_INITIAL_SORT,
            AutomationState.APPLYING_SECONDARY_SORT -> handleSort(root)
            AutomationState.VERIFYING_SORT_SELECTION -> handleVerifySortSelection(root)
            AutomationState.APPLYING_CATEGORY -> handleCategory(root)
            AutomationState.VERIFYING_CATEGORY_SELECTION -> handleVerifyCategorySelection(root)
            AutomationState.SCANNING_TASKS -> handleScanTasks(root)
            AutomationState.SCROLLING_TASKS -> handleScrollTasks()
            AutomationState.OPENING_TASK_DETAIL -> handleOpeningTaskDetail(root)
            AutomationState.VALIDATING_TASK_DETAIL -> handleValidatingTaskDetail(root)
            AutomationState.OPENING_ENROLLMENT_FORM -> handleOpeningEnrollmentForm(root)
            AutomationState.FILLING_ENROLLMENT_FORM -> handleFillingEnrollmentForm(root)
            AutomationState.SUBMITTING_ENROLLMENT_FORM -> handleSubmittingEnrollmentForm(root)
            AutomationState.CONFIRMING_IRREVERSIBLE_NOTICE -> handleIrreversibleNotice(root)
            AutomationState.AWAITING_OPERATOR_CONFIRMATION -> handleAwaitingOperatorConfirmation(root)
            AutomationState.VERIFYING_ENROLLMENT_RESULT -> handleEnrollmentResult(root)
            AutomationState.RETURNING_TO_TASK_LIST -> handleReturnToTaskList(root)
            AutomationState.RETURNING_HOME -> handleReturningHome(root)
            AutomationState.BROWSING_HOME -> handleBrowsingHome(root)
            AutomationState.BROWSING_CONTENT -> handleBrowsingContent(root)
            AutomationState.RETURNING_FROM_CONTENT -> handleReturningFromContent(root)
            AutomationState.RESTING_BETWEEN_ROUNDS -> handleRestingBetweenRounds(root)
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
        enterState(AutomationState.OPENING_BRAND_COOPERATION, "进入创作中心")
    }

    private fun handleOpenBrand(root: AccessibilityNodeInfo?) {
        if (isBrandPage(root)) {
            enterState(AutomationState.WAITING_BRAND_PAGE, "已进入品牌合作页面")
            return
        }
        if (!isDewuRoot(root)) return

        if (brandEntryStep == 0) {
            // 5.89 等旧版达人号会在“我”页直接暴露“商单”入口，没有可访问的
            // “创作中心/查看更多”节点。这里只进入任务列表，不触碰任务报名按钮。
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

            val creationCenter = NodeUtils.findFirstByTexts(root, DewuSelectors.CREATION_CENTER)
            if (NodeUtils.clickNode(creationCenter)) {
                brandEntryStep = 1
                touchAction("点击创作中心")
                stateEnteredAt = SystemClock.elapsedRealtime()
                return
            }
            brandEntryStep = 1
        }

        if (clickTaskPreviewMore(root)) {
            enterState(AutomationState.WAITING_BRAND_PAGE, "等待品牌合作页面")
            return
        }

        if (elapsedInState() > PAGE_TIMEOUT_MS) fail("未找到创作中心的玩转收益入口")
    }

    private fun handleWaitingBrandPage(root: AccessibilityNodeInfo?) {
        if (!isDewuRoot(root)) return
        if (NodeUtils.hasAnyText(root, DewuSelectors.WRONG_MORE_PAGE_MARKERS)) {
            if (wrongMoreRecoveryCount >= 1) {
                fail("玩转收益入口连续进入错误页面，已安全停止")
                return
            }
            wrongMoreRecoveryCount++
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            touchAction("误入其它查看更多页面，安全返回")
            brandEntryStep = 1
            enterState(AutomationState.OPENING_BRAND_COOPERATION, "重新定位玩转收益任务预览")
            return
        }
        if (!isBrandPage(root)) {
            clickTaskPreviewMore(root)
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
        runtime.sortPhase = SortPhase.RECENT
        runtime.listScrollCount = 0
        sortMenuOpened = false
        postconditionAttempts = 0
        syncPostconditionAttempts()
        enterState(AutomationState.APPLYING_INITIAL_SORT, "首轮排序：${DewuSelectors.SORT_RECENT}")
    }

    private fun handleSort(root: AccessibilityNodeInfo?) {
        if (root == null || !isBrandPage(root)) return
        val initial = runtime.state == AutomationState.APPLYING_INITIAL_SORT
        val targetSort = if (initial) DewuSelectors.SORT_RECENT else config.sortMode
        pendingSortTarget = targetSort
        pendingSortWasInitial = initial
        if (!initial && targetSort == DewuSelectors.SORT_RECENT) {
            runtime.sortPhase = SortPhase.CONFIGURED
            runtime.listScrollCount = 0
            enterState(AutomationState.SCANNING_TASKS, "继续按最近发布扫描")
            return
        }
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
            pendingSortWasInitial = initial
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
            if (pendingSortWasInitial) {
                runtime.sortPhase = SortPhase.RECENT
                categoryStep = 0
                enterState(AutomationState.APPLYING_CATEGORY, "排序已生效，设置产品类目：${config.productCategory}")
            } else {
                runtime.sortPhase = SortPhase.CONFIGURED
                enterState(AutomationState.SCANNING_TASKS, "排序已生效，按${pendingSortTarget}扫描")
            }
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
        val state = if (pendingSortWasInitial || runtime.sortPhase == SortPhase.RECENT) {
            AutomationState.APPLYING_INITIAL_SORT
        } else {
            AutomationState.APPLYING_SECONDARY_SORT
        }
        enterState(state, "$reason，重试 $postconditionAttempts/3")
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
                val categoryNode = findVisibleNodeByTexts(root, listOf(config.productCategory))
                if (tapWebNode(categoryNode, "选择类目：${config.productCategory}")) {
                    categoryStep = 2
                    stateEnteredAt = SystemClock.elapsedRealtime()
                } else if (elapsedInState() > 3_000L) {
                    retryCategoryOrFail("筛选面板中未找到类目：${config.productCategory}")
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
        if (root != null && isBrandPage(root) && !isFilterPanelOpen(root) && hasVisibleTaskList(root)) {
            postconditionAttempts = 0
            syncPostconditionAttempts()
            runtime.listScrollCount = 0
            enterState(AutomationState.SCANNING_TASKS, "类目 ${config.productCategory} 已生效，扫描真实任务")
            return
        }
        if (elapsedInState() > 10_000L) retryCategoryOrFail("未检测到类目筛选后的任务列表")
    }

    private fun retryCategoryOrFail(reason: String) {
        postconditionAttempts++
        syncPostconditionAttempts()
        categoryStep = 0
        if (postconditionAttempts >= 3) {
            fail("$reason，连续 3 次无页面变化")
            return
        }
        enterState(AutomationState.APPLYING_CATEGORY, "$reason，重试 $postconditionAttempts/3")
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
        }

        runtime.lastMessage = if (newResults.isEmpty()) {
            "当前页没有新的任务卡片"
        } else {
            "本页解析 ${newResults.size} 项，符合 ${newResults.count { it.eligible }} 项"
        }
        enterState(AutomationState.SCROLLING_TASKS, runtime.lastMessage)
    }

    private fun scanVisibleTasks(root: AccessibilityNodeInfo): Pair<List<PreviewTaskResult>, EnrollmentCandidate?> {
        val registerNodes = NodeUtils.findAllByTexts(root, DewuSelectors.REGISTER_BUTTONS)
            .filter { nodeLabel(it) == "报名" && isSafeTaskActionNode(it) }
        val capacityNodes = NodeUtils.findAll(root) { node ->
            val label = nodeLabel(node)
            TASK_CAPACITY_PATTERN.containsMatchIn(label)
        }
        val cardRoots = buildList {
            registerNodes.mapNotNullTo(this) {
                findTaskCardRoot(it) ?: NodeUtils.nearestClickableAncestor(it, maxLevels = 6)
            }
            capacityNodes.mapNotNullTo(this) { findTaskCardRoot(it) }
        }
        val results = mutableListOf<PreviewTaskResult>()
        var candidate: EnrollmentCandidate? = null

        for (cardRoot in cardRoots) {
            val rawText = NodeUtils.collectText(cardRoot, maxNodes = 100)
            val rawSignature = rawText.replace(Regex("\\s+"), " ").take(1_200).hashCode().toString()
            if (rawText.isBlank() || !visitedTaskSignatures.add(rawSignature)) continue

            val task = TaskCardParser.parse(rawText)
            if (task == null) {
                runtime.parseFailedCount++
                continue
            }

            val eligibility = TaskEligibilityEvaluator.evaluate(task, config)
            val result = PreviewTaskResult(
                signature = task.signature,
                title = task.title,
                rewardText = task.rewardAmount?.let(::formatReward) ?: "奖励未识别",
                capacityText = if (task.registeredCount != null && task.capacity != null) {
                    "${task.registeredCount}/${task.capacity}人"
                } else {
                    "名额未识别"
                },
                deadlineText = task.deadlineText ?: "截止时间未识别",
                eligible = eligibility.eligible,
                reason = eligibility.reason,
            )
            results += result
            runtime.scannedCount++
            if (result.eligible) runtime.eligibleCount++ else runtime.excludedCount++

            if (eligibility.eligible && candidate == null) {
                val registerNode = registerNodes.firstOrNull { node ->
                    val nodeRoot = findTaskCardRoot(node)
                    nodeRoot != null && NodeUtils.collectText(nodeRoot, 100) == rawText
                }
                if (registerNode != null) candidate = EnrollmentCandidate(task, result, registerNode)
            }
        }

        if (results.isNotEmpty()) {
            runtime.recentResults = (results.asReversed() + runtime.recentResults)
                .distinctBy(PreviewTaskResult::signature)
                .take(MAX_RECENT_RESULTS)
        }
        return results to candidate
    }

    private fun findTaskCardRoot(seed: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = seed
        repeat(7) {
            val candidate = current ?: return null
            val cardText = NodeUtils.collectText(candidate, maxNodes = 100)
            val compactText = cardText.replace(Regex("\\s*\\|\\s*"), "")
            val hasCapacity = TASK_CAPACITY_PATTERN.containsMatchIn(compactText)
            val hasReward = cardText.contains("现金奖励") || cardText.contains("¥") || cardText.contains("￥")
            if (hasCapacity && hasReward) return candidate
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

        if (runtime.sortPhase == SortPhase.RECENT) {
            sortMenuOpened = false
            runtime.listScrollCount = 0
            enterState(AutomationState.APPLYING_SECONDARY_SORT, "切换排序：${config.sortMode}")
        } else {
            returnBackAttempts = 0
            enterState(AutomationState.RETURNING_HOME, "本轮商单完成，返回首页")
        }
    }

    private fun handleOpeningTaskDetail(root: AccessibilityNodeInfo?) {
        if (isTaskDetail(root)) {
            enterState(AutomationState.VALIDATING_TASK_DETAIL, "校验任务详情与达人要求")
            return
        }
        if (elapsedInState() > PAGE_TIMEOUT_MS) {
            if (isBrandPage(root) || isBrandShell(root)) {
                runtime.enrollmentFailedCount++
                updateCurrentResult("点击后仍停留在商单列表", "详情打开失败")
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
        val rawText = NodeUtils.collectText(root, maxNodes = 400)
        val detail = TaskDetailParser.parse(rawText)
        if (detail == null) {
            abandonCurrentTask("任务详情解析失败", "详情解析失败")
            return
        }
        val eligibility = TaskEligibilityEvaluator.evaluateDetail(detail, config)
        if (!eligibility.eligible) {
            runtime.detailCheckSummary = eligibility.reason
            runtime.excludedCount++
            updateCurrentResult(eligibility.reason, "详情排除", eligible = false)
            returnBackAttempts = 0
            performBack("详情命中排除词，返回商单")
            enterState(AutomationState.RETURNING_TO_TASK_LIST, eligibility.reason)
            return
        }

        runtime.detailCheckSummary = "通过：列表与详情未命中排除词"

        val button = findVisibleNodeByTexts(root, listOf(DewuSelectors.IMMEDIATE_REGISTER))
        if (activateEnrollmentNode(
                button,
                expectedText = DewuSelectors.IMMEDIATE_REGISTER,
                allowedStates = setOf(AutomationState.VALIDATING_TASK_DETAIL),
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
        if (config.singleEnrollmentTestMode && runtime.operatorTokenExpiresAt == 0L) {
            singleEnrollmentGate.invalidate()
            runtime.finalConfirmationUsed = false
            enterState(
                AutomationState.AWAITING_OPERATOR_CONFIRMATION,
                "前置验证通过，等待操作员允许本次最终确认",
            )
            return
        }

        val signature = currentTaskSignature
        if (signature == null) {
            fail("最终确认缺少当前任务签名")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val authorized = if (config.singleEnrollmentTestMode) {
            singleEnrollmentGate.consume(runtime.runId, signature, now)
        } else {
            singleEnrollmentGate.grant(runtime.runId, signature, now) &&
                singleEnrollmentGate.consume(runtime.runId, signature, now)
        }
        runtime.operatorTokenExpiresAt = 0L
        runtime.finalConfirmationUsed = singleEnrollmentGate.finalConfirmationUsed
        if (!authorized) {
            fail("最终确认令牌无效、已过期或已消费")
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
            enterState(AutomationState.VERIFYING_ENROLLMENT_RESULT, "等待报名结果")
        } else {
            fail("最终确认锁已消费，但确认按钮不可点击；为防止重复操作已停止")
        }
    }

    private fun handleAwaitingOperatorConfirmation(root: AccessibilityNodeInfo?) {
        if (isDewuRoot(root)) {
            val rawText = NodeUtils.collectText(root, maxNodes = 400)
            if (!EnrollmentFormHandler.isIrreversibleNotice(rawText) && elapsedInState() > 3_000L) {
                fail("等待操作员期间不可取消弹窗已消失")
            }
        }
    }

    private fun handleEnrollmentResult(root: AccessibilityNodeInfo?) {
        val rawText = NodeUtils.collectText(root, maxNodes = 400)
        if (EnrollmentFormHandler.isEnrollmentSuccess(rawText)) {
            runtime.enrollmentSuccessCount++
            updateCurrentResult("报名成功，待品牌方确认", "已报名")
            if (runtime.enrollmentSuccessCount >= config.targetEnrollmentCount) {
                finish("已完成 ${runtime.enrollmentSuccessCount}/${config.targetEnrollmentCount} 个报名任务")
                return
            }
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
            enterState(AutomationState.SCROLLING_TASKS, "已返回商单，继续查找")
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

    private fun handleReturningHome(root: AccessibilityNodeInfo?) {
        if (isHome(root)) {
            runtime.homeBrowsedCount = 0
            if (config.homeBrowseCount == 0) beginNextRound() else enterState(AutomationState.BROWSING_HOME, "浏览首页作品")
            return
        }
        if (elapsedInState() > 1_200L) {
            performBack("返回得物首页")
            stateEnteredAt = SystemClock.elapsedRealtime()
            returnBackAttempts++
            if (returnBackAttempts > 6) fail("无法返回得物首页")
        }
    }

    private fun handleBrowsingHome(root: AccessibilityNodeInfo?) {
        if (!isHome(root)) return
        val screen = ScreenInfo.from(service)
        val column = if (kotlin.random.Random.nextBoolean()) 0.27f else 0.73f
        val row = if (kotlin.random.Random.nextBoolean()) 0.43f else 0.64f
        if (performTap(screen.widthPx * column, screen.heightPx * row, "随机打开首页作品")) {
            contentSwipeTarget = 0
            contentSwipeCount = 0
            contentStayUntil = 0L
            notBeforeAt = SystemClock.elapsedRealtime() + 1_000L
            enterState(AutomationState.BROWSING_CONTENT, "识别作品类型")
        }
    }

    private fun handleBrowsingContent(root: AccessibilityNodeInfo?) {
        if (!isDewuRoot(root)) return
        val now = SystemClock.elapsedRealtime()
        val isImage = NodeUtils.hasAnyText(root, DewuSelectors.IMAGE_MARKERS)
        if (isImage) {
            if (contentSwipeTarget == 0) {
                contentSwipeTarget = randomBetween(config.imageSwipeMin, config.imageSwipeMax)
            }
            if (contentSwipeCount < contentSwipeTarget) {
                contentSwipeCount++
                performContentHorizontalSwipe(left = kotlin.random.Random.nextBoolean(), label = "浏览图文 ${contentSwipeCount}/$contentSwipeTarget")
                notBeforeAt = now + randomBetween(1, 3) * 1_000L
                return
            }
        } else {
            if (contentStayUntil == 0L) {
                contentStayUntil = now + randomBetween(config.videoStayMinSeconds, config.videoStayMaxSeconds) * 1_000L
                runtime.lastMessage = "视频/未知作品随机停留"
            }
            if (now < contentStayUntil) return
        }
        performBack("作品浏览完成，返回首页")
        enterState(AutomationState.RETURNING_FROM_CONTENT, "等待返回首页")
    }

    private fun handleReturningFromContent(root: AccessibilityNodeInfo?) {
        if (isHome(root)) {
            runtime.homeBrowsedCount++
            if (runtime.homeBrowsedCount >= config.homeBrowseCount) beginNextRound()
            else enterState(AutomationState.BROWSING_HOME, "继续浏览首页作品")
            return
        }
        if (elapsedInState() > 4_000L) {
            performBack("继续返回首页")
            stateEnteredAt = SystemClock.elapsedRealtime()
        }
    }

    private fun handleRestingBetweenRounds(root: AccessibilityNodeInfo?) {
        if (isHome(root)) {
            enterState(AutomationState.OPENING_PROFILE, "休息结束，进入下一轮商单")
        } else {
            enterState(AutomationState.WAITING_HOME, "休息结束，等待得物首页")
        }
    }

    private fun beginNextRound() {
        runtime.roundCount++
        runtime.listScrollCount = 0
        runtime.sortPhase = SortPhase.RECENT
        visitedTaskSignatures.clear()
        val restMinutes = randomBetween(config.restMinMinutes, config.restMaxMinutes)
        if (restMinutes > 0) {
            notBeforeAt = SystemClock.elapsedRealtime() + restMinutes * 60_000L
            enterState(AutomationState.RESTING_BETWEEN_ROUNDS, "休息 ${restMinutes} 分钟后开始第 ${runtime.roundCount + 1} 轮")
        } else {
            enterState(AutomationState.OPENING_PROFILE, "开始第 ${runtime.roundCount + 1} 轮商单")
        }
    }

    private fun abandonCurrentTask(reason: String, status: String) {
        runtime.enrollmentFailedCount++
        updateCurrentResult(reason, status)
        returnBackAttempts = 0
        performBack("$reason，返回商单")
        enterState(AutomationState.RETURNING_TO_TASK_LIST, reason)
    }

    private fun updateCurrentResult(reason: String, status: String, eligible: Boolean? = null) {
        val signature = currentTaskSignature ?: return
        runtime.recentResults = runtime.recentResults.map { result ->
            if (result.signature == signature) {
                result.copy(
                    eligible = eligible ?: result.eligible,
                    reason = reason,
                    enrollmentStatus = status,
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
                    NodeUtils.hasAnyText(root, DewuSelectors.PROFILE_TAB)) ||
                (NodeUtils.hasAnyText(root, DewuSelectors.PROFILE_MARKERS) &&
                    NodeUtils.hasAnyText(root, DewuSelectors.MORE))
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
        NodeUtils.findAllByTexts(root, listOf("报名", "订阅提醒")).any { node ->
            nodeLabel(node) in listOf("报名", "订阅提醒") && isVisible(node)
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
        return bounds.centerY() in
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

    private fun clickTaskPreviewMore(root: AccessibilityNodeInfo?): Boolean {
        val exactCandidates = NodeUtils.findAllByTexts(root, DewuSelectors.MORE)
            .filter { nodeLabel(it) in DewuSelectors.MORE }
        val contextualCandidate = exactCandidates.firstOrNull { node ->
            val context = NodeUtils.ancestorText(node, levels = 7)
            val hasTaskPreview =
                context.contains("现金奖励") &&
                    context.contains("已报名") &&
                    context.contains("报名")
            hasTaskPreview
        }
        // React Native 的无障碍树偶尔会为同一个文字节点暴露重复引用；此处只接受
        // 文案完全等于“查看更多”的可见节点，再由错误页面检测负责安全回退。
        val candidate = sequenceOf(contextualCandidate)
            .filterNotNull()
            .plus(exactCandidates.asSequence())
            .distinct()
            .firstOrNull { node ->
                val rect = NodeUtils.bounds(node)
                rect != null && rect.width() > 0 && rect.height() > 0
            }
            ?: return false

        val bounds = NodeUtils.bounds(candidate) ?: return false
        val tapped = performTap(
            x = bounds.centerX().toFloat(),
            y = bounds.centerY().toFloat(),
            label = "点击玩转收益任务预览查看更多",
        )
        if (tapped) notBeforeAt = SystemClock.elapsedRealtime() + 1_500L
        return tapped
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

    private fun performContentHorizontalSwipe(left: Boolean, label: String): Boolean {
        val screen = ScreenInfo.from(service)
        val y = screen.heightPx * 0.55f
        val startX = screen.widthPx * if (left) 0.82f else 0.18f
        val endX = screen.widthPx * if (left) 0.18f else 0.82f
        return performSwipe(startX, y, endX, y, 480L, label)
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
                "retry=$postconditionAttempts lock=${singleEnrollmentGate.finalConfirmationUsed} | $message",
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
        val seconds = randomBetween(config.refreshMinSeconds, config.refreshMaxSeconds)
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
        singleEnrollmentGate.invalidate()
        runtime.operatorTokenExpiresAt = 0L
        if (resetState) runtime.state = AutomationState.IDLE
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }
}
