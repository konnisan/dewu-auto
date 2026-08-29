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
import kotlin.math.abs

class AutomationController(
    private val service: AccessibilityService,
) {
    companion object {
        private const val TAG = "DewuAuto"
        private const val BASE_TICK_MS = 700L
        private const val MAX_ACTIONS_PER_RUN = 500
        private const val HOME_DIAGNOSTIC_INTERVAL_MS = 5_000L
        private const val PAGE_TIMEOUT_MS = 20_000L
        private const val BRAND_DIAGNOSTIC_INTERVAL_MS = 3_000L
        private const val MAX_RECENT_RESULTS = 4
        private val TASK_CAPACITY_PATTERN = Regex("(?:(?:已)?报名[：:]?\\s*)?\\d+\\s*/\\s*\\d+\\s*人")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val licenseManager = LicenseManager(service)
    private val runtime = AutomationRuntime()
    private val visitedTaskSignatures = LinkedHashSet<String>()

    private var config = AutomationConfig()
    private var running = false
    private var sortMenuOpened = false
    private var categoryStep = 0
    private var brandEntryStep = 0
    private var wrongMoreRecoveryCount = 0
    private var stateEnteredAt = 0L
    private var notBeforeAt = 0L
    private var lastPokeAt = 0L
    private var lastHomeDiagnosticAt = 0L
    private var lastBrandDiagnosticAt = 0L

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
        runtime.state = AutomationState.VERIFYING_LICENSE
        runtime.listScrollCount = 0
        runtime.scannedCount = 0
        runtime.eligibleCount = 0
        runtime.excludedCount = 0
        runtime.parseFailedCount = 0
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
        notBeforeAt = 0L
        lastPokeAt = 0L
        lastHomeDiagnosticAt = 0L
        lastBrandDiagnosticAt = 0L
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
            AutomationState.APPLYING_CATEGORY -> handleCategory(root)
            AutomationState.SCANNING_TASKS -> handleScanTasks(root)
            AutomationState.SCROLLING_TASKS -> handleScrollTasks()
        }
    }

    private fun handleWaitingHome(root: AccessibilityNodeInfo?) {
        when {
            isBrandPage(root) -> enterState(AutomationState.WAITING_BRAND_PAGE, "已在品牌合作页面")
            isTaskDetail(root) -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                touchAction("从任务详情安全返回")
                stateEnteredAt = SystemClock.elapsedRealtime()
            }
            isProfile(root) -> {
                brandEntryStep = if (NodeUtils.hasAnyText(root, DewuSelectors.BRAND_CONTEXT)) 1 else 0
                enterState(AutomationState.OPENING_BRAND_COOPERATION, "已在个人/创作中心")
            }
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
            if (elapsedInState() > PAGE_TIMEOUT_MS) {
                diagnoseBrandNavigation(root, "WAITING_PROFILE_TIMEOUT")
                fail("未识别到个人中心")
            }
            return
        }

        brandEntryStep = if (NodeUtils.hasAnyText(root, DewuSelectors.BRAND_CONTEXT)) 1 else 0
        lastBrandDiagnosticAt = 0L
        enterState(AutomationState.OPENING_BRAND_COOPERATION, "进入创作中心并定位品牌合作")
    }

    private fun handleOpenBrand(root: AccessibilityNodeInfo?) {
        if (isBrandPage(root)) {
            enterState(AutomationState.WAITING_BRAND_PAGE, "已进入品牌合作页面")
            return
        }
        if (!isDewuRoot(root)) return

        // 第一步：在“我的”页进入创作中心。
        // 如果当前已经能看到“品牌合作/商单”，说明已经位于创作中心，不重复点击标题。
        if (brandEntryStep == 0) {
            if (NodeUtils.hasAnyText(root, DewuSelectors.BRAND_CONTEXT)) {
                brandEntryStep = 1
                stateEnteredAt = SystemClock.elapsedRealtime()
            } else {
                val creationCenter = NodeUtils.findFirstByTexts(root, DewuSelectors.CREATION_CENTER)
                if (NodeUtils.clickNode(creationCenter)) {
                    brandEntryStep = 1
                    touchAction("点击创作中心")
                    notBeforeAt = SystemClock.elapsedRealtime() + 1_000L
                    stateEnteredAt = SystemClock.elapsedRealtime()
                    return
                }
            }
        }

        // 第二步：只点击“品牌合作”区域对应的“查看更多”。
        // 页面上可能同时有多个查看更多，绝不使用“第一个查看更多”作为兜底。
        if (brandEntryStep >= 1 && clickBrandCooperationMore(root)) {
            brandEntryStep = 2
            enterState(AutomationState.WAITING_BRAND_PAGE, "等待品牌合作任务列表")
            return
        }

        diagnoseBrandNavigationPeriodically(root, "OPENING_BRAND")
        if (elapsedInState() > PAGE_TIMEOUT_MS) {
            diagnoseBrandNavigation(root, "OPENING_BRAND_TIMEOUT")
            fail("未找到创作中心内“品牌合作”的查看更多")
        }
    }

    private fun handleWaitingBrandPage(root: AccessibilityNodeInfo?) {
        if (!isDewuRoot(root)) return

        if (isBrandPage(root)) {
            runtime.requiresCreatorEnrollment = NodeUtils.hasAnyText(root, DewuSelectors.APPLY_TO_JOIN)
            wrongMoreRecoveryCount = 0
            sortMenuOpened = false
            enterState(AutomationState.APPLYING_INITIAL_SORT, "设置排序：${config.sortMode}")
            return
        }

        if (NodeUtils.hasAnyText(root, DewuSelectors.WRONG_MORE_PAGE_MARKERS)) {
            if (wrongMoreRecoveryCount >= 1) {
                diagnoseBrandNavigation(root, "WRONG_MORE_REPEAT")
                fail("连续进入了非品牌合作页面，已安全停止")
                return
            }
            wrongMoreRecoveryCount++
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            touchAction("误入其它查看更多页面，安全返回")
            brandEntryStep = 1
            notBeforeAt = SystemClock.elapsedRealtime() + 1_000L
            enterState(AutomationState.OPENING_BRAND_COOPERATION, "返回创作中心，重新定位品牌合作")
            return
        }

        // 如果点击没有触发页面跳转，只允许重新尝试品牌合作上下文中的按钮。
        // 不再像旧实现一样反复点击任意“查看更多”。
        if (elapsedInState() > 2_000L && NodeUtils.hasAnyText(root, DewuSelectors.BRAND_CONTEXT)) {
            if (clickBrandCooperationMore(root)) {
                stateEnteredAt = SystemClock.elapsedRealtime()
                return
            }
        }

        diagnoseBrandNavigationPeriodically(root, "WAITING_BRAND_PAGE")
        if (elapsedInState() > PAGE_TIMEOUT_MS) {
            runtime.requiresCreatorEnrollment = NodeUtils.hasAnyText(root, DewuSelectors.APPLY_TO_JOIN)
            diagnoseBrandNavigation(root, "WAITING_BRAND_TIMEOUT")
            val reason = if (runtime.requiresCreatorEnrollment) {
                "当前账号仅显示申请入驻，已安全停止"
            } else {
                "点击品牌合作查看更多后，未识别到品牌合作任务列表"
            }
            finish(reason)
        }
    }

    private fun handleSort(root: AccessibilityNodeInfo?) {
        if (root == null || !isBrandPage(root)) return
        if (!sortMenuOpened) {
            val current = NodeUtils.findFirstByTexts(root, DewuSelectors.SORT_ENTRY)
            if (NodeUtils.clickNode(current)) {
                sortMenuOpened = true
                touchAction("打开排序菜单")
            }
            return
        }

        val targetNode = NodeUtils.findFirstByTexts(root, listOf(config.sortMode))
        if (NodeUtils.clickNode(targetNode)) {
            touchAction("选择排序：${config.sortMode}")
            delayByRefreshWindow()
            sortMenuOpened = false
            runtime.listScrollCount = 0
            categoryStep = 0
            enterState(AutomationState.APPLYING_CATEGORY, "设置产品类目：${config.productCategory}")
        } else if (elapsedInState() > 5_000L) {
            sortMenuOpened = false
            stateEnteredAt = SystemClock.elapsedRealtime()
        }
    }

    private fun handleCategory(root: AccessibilityNodeInfo?) {
        if (root == null || !isBrandPage(root)) return
        when (categoryStep) {
            0 -> {
                if (clickText(root, listOf(DewuSelectors.PRODUCT_CATEGORY), "打开产品类目")) {
                    categoryStep = 1
                    return
                }
                performHorizontalSwipe(left = true, label = "筛选栏左滑")
            }

            1 -> {
                val categoryNode = NodeUtils.findFirstByTexts(root, listOf(config.productCategory))
                if (NodeUtils.clickNode(categoryNode)) {
                    touchAction("选择类目：${config.productCategory}")
                    categoryStep = 2
                    stateEnteredAt = SystemClock.elapsedRealtime()
                }
            }

            else -> {
                val confirmNode = NodeUtils.findFirstByTexts(root, DewuSelectors.FILTER_CONFIRM)
                if (NodeUtils.clickNode(confirmNode)) {
                    touchAction("确认产品类目筛选")
                    delayByRefreshWindow()
                    runtime.listScrollCount = 0
                    enterState(AutomationState.SCANNING_TASKS, "扫描当前页任务")
                } else if (elapsedInState() > 3_000L) {
                    runtime.listScrollCount = 0
                    enterState(AutomationState.SCANNING_TASKS, "产品类目已选择，扫描当前页")
                }
            }
        }
    }

    private fun handleScanTasks(root: AccessibilityNodeInfo?) {
        if (root == null || !isBrandPage(root)) return
        runtime.requiresCreatorEnrollment = NodeUtils.hasAnyText(root, DewuSelectors.APPLY_TO_JOIN)

        val newResults = scanVisibleTasks(root)
        if (newResults.isEmpty() && runtime.scannedCount == 0 && runtime.requiresCreatorEnrollment) {
            finish("当前账号仅显示申请入驻，未发现可解析任务")
            return
        }

        runtime.lastMessage = if (newResults.isEmpty()) {
            "当前页没有新的任务卡片"
        } else {
            "本页解析 ${newResults.size} 项，符合 ${newResults.count { it.eligible }} 项"
        }
        enterState(AutomationState.SCROLLING_TASKS, runtime.lastMessage)
    }

    private fun scanVisibleTasks(root: AccessibilityNodeInfo): List<PreviewTaskResult> {
        // 这里读取“报名/立即报名”文字只是为了定位任务卡片，绝不会点击这些节点。
        val registerNodes = NodeUtils.findAllByTexts(root, DewuSelectors.REGISTER_BUTTONS)
            .filter { nodeLabel(it) in DewuSelectors.REGISTER_BUTTONS }
        val capacityNodes = NodeUtils.findAll(root) { node ->
            val label = nodeLabel(node)
            TASK_CAPACITY_PATTERN.containsMatchIn(label)
        }
        val cardRoots = buildList {
            registerNodes.mapNotNullTo(this) { NodeUtils.nearestClickableAncestor(it, maxLevels = 6) }
            capacityNodes.mapNotNullTo(this) { findTaskCardRoot(it) }
        }
        val results = mutableListOf<PreviewTaskResult>()

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
        }

        if (results.isNotEmpty()) {
            runtime.recentResults = (results.asReversed() + runtime.recentResults)
                .distinctBy(PreviewTaskResult::signature)
                .take(MAX_RECENT_RESULTS)
        }
        return results
    }

    private fun findTaskCardRoot(seed: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = seed
        repeat(7) {
            val candidate = current ?: return null
            val cardText = NodeUtils.collectText(candidate, maxNodes = 100)
            val hasCapacity = TASK_CAPACITY_PATTERN.containsMatchIn(cardText)
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

        finish(
            "预演完成：扫描 ${runtime.scannedCount}，符合 ${runtime.eligibleCount}，" +
                "排除 ${runtime.excludedCount}，解析失败 ${runtime.parseFailedCount}",
        )
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
                NodeUtils.hasAnyText(root, DewuSelectors.BRAND_CONTEXT) ||
                (NodeUtils.hasAnyText(root, DewuSelectors.PROFILE_MARKERS) &&
                    NodeUtils.hasAnyText(root, DewuSelectors.MORE))
            )

    private fun isTaskDetail(root: AccessibilityNodeInfo?): Boolean =
        isDewuRoot(root) && NodeUtils.hasAnyText(root, DewuSelectors.TASK_DETAIL_MARKERS)

    private fun isBrandPage(root: AccessibilityNodeInfo?): Boolean =
        isDewuRoot(root) &&
            NodeUtils.hasAnyText(root, DewuSelectors.SORT_ENTRY) &&
            NodeUtils.hasAnyText(root, DewuSelectors.BRAND_PAGE_MARKERS)

    private fun clickText(root: AccessibilityNodeInfo?, texts: Collection<String>, label: String): Boolean {
        if (texts.any { it in DewuSelectors.REGISTER_BUTTONS || it in DewuSelectors.APPLY_TO_JOIN }) {
            log("安全拦截：拒绝点击 $texts")
            return false
        }
        val node = NodeUtils.findFirstByTexts(root, texts)
        if (!NodeUtils.clickNode(node)) return false
        touchAction(label)
        return true
    }

    /**
     * 创作中心可能同时存在多个“查看更多”。
     * 优先规则：
     * 1. “查看更多”的祖先容器文本里明确包含“品牌合作/商单”；
     * 2. React Native 把标题和按钮拆成兄弟节点时，按屏幕坐标选择距离“品牌合作”最近的查看更多；
     * 3. 只有页面同时存在品牌合作，并且全页只有一个查看更多时，才允许唯一按钮兜底。
     *
     * 永远不会因为找不到品牌上下文而点击页面第一个查看更多。
     */
    private fun clickBrandCooperationMore(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false

        val brandNodes = NodeUtils.findAllByTexts(root, DewuSelectors.BRAND_CONTEXT)
            .filter { node ->
                val label = nodeLabel(node)
                DewuSelectors.BRAND_CONTEXT.any { context ->
                    label.equals(context, ignoreCase = true) || label.contains(context, ignoreCase = true)
                }
            }
        val moreNodes = NodeUtils.findAllByTexts(root, DewuSelectors.MORE)
            .filter { nodeLabel(it) in DewuSelectors.MORE }
            .filter { node ->
                val rect = NodeUtils.bounds(node)
                rect != null && rect.width() > 0 && rect.height() > 0
            }

        if (brandNodes.isEmpty() || moreNodes.isEmpty()) return false

        val ancestorCandidate = moreNodes.firstOrNull { node ->
            val context = NodeUtils.ancestorText(node, levels = 8)
            DewuSelectors.BRAND_CONTEXT.any { context.contains(it, ignoreCase = true) }
        }

        val geometryCandidate = if (ancestorCandidate == null) {
            findNearestBrandMoreByGeometry(brandNodes, moreNodes)
        } else {
            null
        }

        val uniqueCandidate = if (ancestorCandidate == null && geometryCandidate == null && moreNodes.size == 1) {
            moreNodes.first()
        } else {
            null
        }

        val candidate = ancestorCandidate ?: geometryCandidate ?: uniqueCandidate ?: return false
        val bounds = NodeUtils.bounds(candidate) ?: return false

        val method = when (candidate) {
            ancestorCandidate -> "祖先上下文"
            geometryCandidate -> "屏幕邻近"
            else -> "唯一按钮"
        }
        log(
            "BRAND_MORE matched=$method bounds=${bounds.left},${bounds.top},${bounds.right},${bounds.bottom} " +
                "brandCount=${brandNodes.size} moreCount=${moreNodes.size}",
        )

        if (NodeUtils.clickNode(candidate)) {
            touchAction("点击品牌合作查看更多($method)")
            notBeforeAt = SystemClock.elapsedRealtime() + 1_500L
            return true
        }

        val tapped = performTap(
            x = bounds.centerX().toFloat(),
            y = bounds.centerY().toFloat(),
            label = "点击品牌合作查看更多($method/坐标)",
        )
        if (tapped) notBeforeAt = SystemClock.elapsedRealtime() + 1_500L
        return tapped
    }

    private fun findNearestBrandMoreByGeometry(
        brandNodes: List<AccessibilityNodeInfo>,
        moreNodes: List<AccessibilityNodeInfo>,
    ): AccessibilityNodeInfo? {
        val screen = ScreenInfo.from(service)
        val maxVerticalDistance = (screen.heightPx * 0.32f).toInt().coerceAtLeast(240)

        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = Int.MAX_VALUE

        for (brandNode in brandNodes) {
            val brandRect = NodeUtils.bounds(brandNode) ?: continue
            if (brandRect.width() <= 0 || brandRect.height() <= 0) continue

            for (moreNode in moreNodes) {
                val moreRect = NodeUtils.bounds(moreNode) ?: continue
                val verticalDistance = abs(moreRect.centerY() - brandRect.centerY())
                if (verticalDistance > maxVerticalDistance) continue

                // 品牌合作标题通常在卡片左侧/上方，查看更多通常在右侧或同一模块下方。
                // 对明显位于品牌标题左侧、且离得较远的按钮增加惩罚，避免跨模块误匹配。
                val leftPenalty = if (moreRect.centerX() + moreRect.width() < brandRect.centerX()) 500 else 0
                val abovePenalty = if (moreRect.centerY() < brandRect.top - 120) 350 else 0
                val horizontalDistance = abs(moreRect.centerX() - brandRect.centerX()) / 4
                val score = verticalDistance + horizontalDistance + leftPenalty + abovePenalty

                if (score < bestScore) {
                    bestScore = score
                    bestNode = moreNode
                }
            }
        }

        return bestNode
    }

    private fun diagnoseBrandNavigationPeriodically(root: AccessibilityNodeInfo?, stage: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBrandDiagnosticAt < BRAND_DIAGNOSTIC_INTERVAL_MS) return
        lastBrandDiagnosticAt = now
        diagnoseBrandNavigation(root, stage)
    }

    private fun diagnoseBrandNavigation(root: AccessibilityNodeInfo?, stage: String) {
        val packageName = root?.packageName?.toString().orEmpty().ifBlank { "<none>" }
        val brandNodes = NodeUtils.findAllByTexts(root, DewuSelectors.BRAND_CONTEXT)
        val moreNodes = NodeUtils.findAllByTexts(root, DewuSelectors.MORE)
        val brandBounds = brandNodes.take(6).joinToString(";") { node ->
            NodeUtils.bounds(node)?.let { r -> "${nodeLabel(node)}@${r.left},${r.top},${r.right},${r.bottom}" }
                ?: "${nodeLabel(node)}@?"
        }
        val moreBounds = moreNodes.take(10).joinToString(";") { node ->
            NodeUtils.bounds(node)?.let { r -> "${nodeLabel(node)}@${r.left},${r.top},${r.right},${r.bottom}" }
                ?: "${nodeLabel(node)}@?"
        }
        val visible = NodeUtils.dumpVisibleText(root, 220).take(1_600)
        log(
            "BRAND_NAV stage=$stage package=$packageName step=$brandEntryStep " +
                "brand=[$brandBounds] more=[$moreBounds] visible=$visible",
        )
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
        log("STATE=$state | $message")
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
        log(message)
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
        if (resetState) runtime.state = AutomationState.IDLE
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }
}
