package com.konnisan.dewuauto.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.konnisan.dewuauto.accessibility.NodeUtils
import com.konnisan.dewuauto.config.AutomationConfig
import com.konnisan.dewuauto.license.LicenseManager
import com.konnisan.dewuauto.util.ScreenInfo
import kotlin.math.roundToInt
import kotlin.random.Random

class AutomationController(
    private val service: AccessibilityService,
) {
    companion object {
        private const val TAG = "DewuAuto"
        private const val BASE_TICK_MS = 700L
        private const val MAX_ACTIONS_PER_RUN = 2_500
        private const val REGISTER_RESULT_TIMEOUT_MS = 15_000L
        private const val MAX_BACK_TO_HOME = 8
        private const val HOME_DIAGNOSTIC_INTERVAL_MS = 5_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val licenseManager = LicenseManager(service)
    private val runtime = AutomationRuntime()
    private val visitedTaskSignatures = LinkedHashSet<String>()

    private var config = AutomationConfig()
    private var running = false
    private var sortMenuOpened = false
    private var categoryStep = 0
    private var stateEnteredAt = 0L
    private var returnBackCount = 0
    private var remainingContentSwipes = 0
    private var contentDwellUntil = 0L
    private var reentryAfterBrowse = false
    private var roundRestUntil = 0L
    private var notBeforeAt = 0L
    private var lastPokeAt = 0L
    private var lastHomeDiagnosticAt = 0L
    private var registrationPrimaryClicked = false

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
        runtime.state = AutomationState.PRECHECK
        runtime.registrationCount = 0
        runtime.listScrollCount = 0
        runtime.userSortApplied = false
        runtime.browsedCount = 0
        runtime.actionCount = 0
        runtime.lastActionAt = 0L
        runtime.lastMessage = "开始预检查"
        visitedTaskSignatures.clear()
        sortMenuOpened = false
        categoryStep = 0
        returnBackCount = 0
        remainingContentSwipes = 0
        contentDwellUntil = 0L
        reentryAfterBrowse = false
        roundRestUntil = 0L
        notBeforeAt = 0L
        lastPokeAt = 0L
        lastHomeDiagnosticAt = 0L
        registrationPrimaryClicked = false
        running = true
        enterState(AutomationState.VERIFYING_LICENSE, "验证卡密")

        licenseManager.verify(config.cardKey) { result ->
            if (!running) return@verify
            result.onSuccess {
                licenseManager.startHeartbeat { reason ->
                    if (running) fail("卡密心跳失败: $reason")
                }
                enterState(AutomationState.WAITING_HOME, "等待前台得物首页")
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

    fun snapshot(): AutomationRuntime = runtime.copy()

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
            AutomationState.PRECHECK,
            AutomationState.VERIFYING_LICENSE,
            AutomationState.SOFT_RESETTING_DEWU,
            AutomationState.LAUNCHING_DEWU,
            AutomationState.IDLE,
            AutomationState.FINISHED,
            AutomationState.ERROR,
            AutomationState.PAUSED_FOR_SECURITY -> Unit

            AutomationState.WAITING_HOME -> handleWaitingHome(root)
            AutomationState.OPENING_PROFILE,
            AutomationState.REENTERING_PROFILE -> handleOpenProfile(root)
            AutomationState.WAITING_PROFILE -> handleWaitingProfile(root)
            AutomationState.OPENING_BRAND_COOPERATION,
            AutomationState.REENTERING_BRAND -> handleOpenBrand(root)
            AutomationState.WAITING_BRAND_PAGE -> handleWaitingBrandPage(root)
            AutomationState.APPLYING_INITIAL_SORT -> handleSort(root, DewuSelectors.SORT_RECENT, initial = true)
            AutomationState.APPLYING_CATEGORY -> handleCategory(root)
            AutomationState.SCANNING_TASKS -> handleScanTasks(root)
            AutomationState.SCROLLING_TASKS -> handleScrollTasks()
            AutomationState.APPLYING_USER_SORT -> handleSort(root, config.sortMode, initial = false)
            AutomationState.OPENING_REGISTRATION -> Unit
            AutomationState.WAITING_REGISTRATION_PAGE -> handleRegistrationPage(root)
            AutomationState.CONFIRMING_REGISTRATION -> handleConfirmRegistration(root)
            AutomationState.WAITING_REGISTRATION_RESULT -> handleRegistrationResult(root)
            AutomationState.WAITING_USER_CONFIRM -> handleWaitingUserConfirm(root)
            AutomationState.RETURNING_TO_TASK_LIST -> handleReturnToTaskList(root)
            AutomationState.RETURNING_HOME -> handleReturningHome(root)
            AutomationState.BROWSING_HOME -> handleBrowsingHome(root)
            AutomationState.BROWSING_CONTENT -> handleBrowsingContent(root)
            AutomationState.WAITING_ROUND_REST -> handleRoundRest(root)
        }
    }

    private fun handleWaitingHome(root: AccessibilityNodeInfo?) {
        if (isHome(root)) {
            enterState(
                if (reentryAfterBrowse) AutomationState.REENTERING_PROFILE else AutomationState.OPENING_PROFILE,
                "检测到首页，准备进入个人中心",
            )
            return
        }

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

    private fun handleOpenProfile(root: AccessibilityNodeInfo?) {
        if (!isHome(root)) {
            if (isProfile(root)) {
                enterState(AutomationState.WAITING_PROFILE, "已进入个人中心")
            }
            return
        }
        if (clickText(root, DewuSelectors.PROFILE_TAB, "点击“我/我的”")) {
            enterState(AutomationState.WAITING_PROFILE, "等待个人中心")
        }
    }

    private fun handleWaitingProfile(root: AccessibilityNodeInfo?) {
        if (!isProfile(root)) return
        enterState(
            if (reentryAfterBrowse) AutomationState.REENTERING_BRAND else AutomationState.OPENING_BRAND_COOPERATION,
            "检测到个人中心",
        )
    }

    private fun handleOpenBrand(root: AccessibilityNodeInfo?) {
        if (isBrandPage(root)) {
            enterState(AutomationState.WAITING_BRAND_PAGE, "已进入商单页面")
            return
        }

        val brandNode = NodeUtils.findFirstByTexts(root, DewuSelectors.BRAND_ENTRY)
        if (NodeUtils.clickNode(brandNode)) {
            touchAction("点击商单/品牌合作")
            enterState(AutomationState.WAITING_BRAND_PAGE, "等待商单页面")
            return
        }

        val hasBrandText = NodeUtils.hasAnyText(root, DewuSelectors.BRAND_ENTRY)
        if (hasBrandText && clickText(root, DewuSelectors.MORE, "点击品牌合作查看更多")) {
            enterState(AutomationState.WAITING_BRAND_PAGE, "等待商单列表")
        }
    }

    private fun handleWaitingBrandPage(root: AccessibilityNodeInfo?) {
        if (!isDewuRoot(root)) return

        if (!isBrandPage(root)) {
            val hasBrandContext = NodeUtils.hasAnyText(root, DewuSelectors.BRAND_ENTRY)
            if (hasBrandContext && NodeUtils.hasAnyText(root, DewuSelectors.MORE)) {
                clickText(root, DewuSelectors.MORE, "点击商单查看更多")
            }
            return
        }

        sortMenuOpened = false
        enterState(AutomationState.APPLYING_INITIAL_SORT, "首次排序：最近发布")
    }

    private fun handleSort(root: AccessibilityNodeInfo?, target: String, initial: Boolean) {
        if (root == null) return
        if (!sortMenuOpened) {
            val current = NodeUtils.findFirstByTexts(root, DewuSelectors.SORT_ENTRY)
            if (NodeUtils.clickNode(current)) {
                sortMenuOpened = true
                touchAction("打开排序菜单")
            }
            return
        }

        val targetNode = NodeUtils.findFirstByTexts(root, listOf(target))
        if (NodeUtils.clickNode(targetNode)) {
            touchAction("选择排序：$target")
            delayByRefreshWindow()
            sortMenuOpened = false
            runtime.listScrollCount = 0
            if (initial) {
                categoryStep = 0
                enterState(AutomationState.APPLYING_CATEGORY, "设置产品类目：${config.productCategory}")
            } else {
                runtime.userSortApplied = true
                enterState(AutomationState.SCANNING_TASKS, "按 $target 继续扫描")
            }
        } else if (elapsedInState() > 5_000L) {
            sortMenuOpened = false
            stateEnteredAt = SystemClock.elapsedRealtime()
        }
    }

    private fun handleCategory(root: AccessibilityNodeInfo?) {
        if (root == null) return
        when (categoryStep) {
            0 -> {
                val categoryAlreadyVisible = NodeUtils.findFirstByTexts(root, listOf(DewuSelectors.PRODUCT_CATEGORY))
                if (categoryAlreadyVisible != null) {
                    categoryStep = 1
                    return
                }
                val rewardNode = NodeUtils.findFirstByTexts(root, listOf(DewuSelectors.REWARD_TYPE))
                val rect = NodeUtils.bounds(rewardNode)
                if (rect != null && rect.width() > 0) {
                    val y = rect.centerY().toFloat()
                    performSwipe(
                        startX = rect.right - rect.width() * 0.15f,
                        startY = y,
                        endX = rect.left + rect.width() * 0.15f,
                        endY = y,
                        durationMs = 420L,
                        label = "奖励类型区域左滑",
                    )
                } else {
                    performHorizontalSwipe(left = true, label = "筛选栏左滑")
                }
                categoryStep = 1
            }

            1 -> {
                if (clickText(root, listOf(DewuSelectors.PRODUCT_CATEGORY), "打开产品类目")) {
                    categoryStep = 2
                }
            }

            else -> {
                val categoryNode = NodeUtils.findFirstByTexts(root, listOf(config.productCategory))
                if (NodeUtils.clickNode(categoryNode)) {
                    touchAction("选择类目：${config.productCategory}")
                    delayByRefreshWindow()
                    runtime.listScrollCount = 0
                    runtime.userSortApplied = false
                    enterState(AutomationState.SCANNING_TASKS, "扫描当前页任务")
                }
            }
        }
    }

    private fun handleScanTasks(root: AccessibilityNodeInfo?) {
        if (root == null || !isBrandPage(root)) return
        val candidate = findEligibleRegisterNode(root)
        if (candidate != null) {
            val signature = taskSignature(candidate)
            if (NodeUtils.clickNode(candidate)) {
                visitedTaskSignatures += signature
                registrationPrimaryClicked = false
                touchAction("打开符合条件的报名任务")
                enterState(AutomationState.WAITING_REGISTRATION_PAGE, "等待报名页面")
            }
            return
        }
        enterState(AutomationState.SCROLLING_TASKS, "当前页无合适任务")
    }

    private fun handleScrollTasks() {
        if (runtime.listScrollCount < config.maxListScrolls) {
            runtime.listScrollCount++
            performVerticalSwipe(up = true, label = "商单列表下滑 ${runtime.listScrollCount}/${config.maxListScrolls}")
            delayByRefreshWindow()
            enterState(AutomationState.SCANNING_TASKS, "继续扫描任务")
            return
        }

        if (!runtime.userSortApplied) {
            sortMenuOpened = false
            enterState(AutomationState.APPLYING_USER_SORT, "达到下滑次数，切换 UI 排序：${config.sortMode}")
        } else {
            enterState(AutomationState.RETURNING_HOME, "本轮商单扫描完成，返回首页")
            returnBackCount = 0
        }
    }

    private fun handleRegistrationPage(root: AccessibilityNodeInfo?) {
        if (root == null) return
        if (isRegistrationSuccess(root)) {
            onRegistrationSuccess()
            return
        }
        if (NodeUtils.hasAnyText(root, DewuSelectors.REGISTER_FAILED)) {
            log("任务不可报名，返回列表")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            touchAction("报名失败返回")
            enterState(AutomationState.RETURNING_TO_TASK_LIST, "返回任务列表")
            return
        }

        if (config.sizeSpec.isNotBlank()) {
            NodeUtils.findFirstByTexts(root, listOf(config.sizeSpec))?.let {
                if (NodeUtils.clickNode(it)) touchAction("选择规格：${config.sizeSpec}")
            }
        }

        if (!registrationPrimaryClicked) {
            val primary = NodeUtils.findFirstByTexts(root, DewuSelectors.REGISTER_BUTTONS)
            val primaryLabel = primary?.text?.toString()?.trim().orEmpty().ifBlank {
                primary?.contentDescription?.toString()?.trim().orEmpty()
            }
            if (primary != null && primaryLabel in DewuSelectors.REGISTER_BUTTONS && NodeUtils.clickNode(primary)) {
                registrationPrimaryClicked = true
                touchAction("点击报名主按钮")
                stateEnteredAt = SystemClock.elapsedRealtime()
                return
            }
        }

        val confirm = NodeUtils.findFirstByTexts(root, DewuSelectors.CONFIRM_REGISTER_BUTTONS)
        if (confirm != null) {
            if (config.autoConfirmRegistration) {
                enterState(AutomationState.CONFIRMING_REGISTRATION, "自动确认报名")
            } else {
                enterState(AutomationState.WAITING_USER_CONFIRM, "等待你手动确认报名")
            }
            return
        }

        if (elapsedInState() > REGISTER_RESULT_TIMEOUT_MS) {
            log("报名页未识别到确认/结果，返回列表避免死循环")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            touchAction("报名页超时返回")
            enterState(AutomationState.RETURNING_TO_TASK_LIST, "报名页超时")
        }
    }

    private fun handleConfirmRegistration(root: AccessibilityNodeInfo?) {
        if (clickText(root, DewuSelectors.CONFIRM_REGISTER_BUTTONS, "点击确认报名")) {
            enterState(AutomationState.WAITING_REGISTRATION_RESULT, "等待报名结果")
        }
    }

    private fun handleWaitingUserConfirm(root: AccessibilityNodeInfo?) {
        if (isRegistrationSuccess(root)) {
            onRegistrationSuccess()
            return
        }
        if (NodeUtils.hasAnyText(root, DewuSelectors.REGISTER_FAILED)) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            touchAction("手动报名失败返回")
            enterState(AutomationState.RETURNING_TO_TASK_LIST, "返回任务列表")
        }
    }

    private fun handleRegistrationResult(root: AccessibilityNodeInfo?) {
        if (isRegistrationSuccess(root)) {
            onRegistrationSuccess()
            return
        }
        if (NodeUtils.hasAnyText(root, DewuSelectors.REGISTER_FAILED)) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            touchAction("报名失败返回")
            enterState(AutomationState.RETURNING_TO_TASK_LIST, "报名失败")
            return
        }
        if (elapsedInState() > REGISTER_RESULT_TIMEOUT_MS) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            touchAction("等待报名结果超时返回")
            enterState(AutomationState.RETURNING_TO_TASK_LIST, "报名结果超时")
        }
    }

    private fun onRegistrationSuccess() {
        runtime.registrationCount++
        runtime.listScrollCount = 0
        registrationPrimaryClicked = false
        log("报名成功：${runtime.registrationCount}/${config.targetRegistrationCount}")
        if (runtime.registrationCount >= config.targetRegistrationCount) {
            finish("达到目标报名次数 ${config.targetRegistrationCount}")
            return
        }
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        touchAction("报名成功后返回列表")
        enterState(AutomationState.RETURNING_TO_TASK_LIST, "继续寻找更多任务")
    }

    private fun handleReturnToTaskList(root: AccessibilityNodeInfo?) {
        if (isBrandPage(root)) {
            if (runtime.listScrollCount < config.maxListScrolls) {
                runtime.listScrollCount++
                performVerticalSwipe(up = true, label = "报名后列表下滑 ${runtime.listScrollCount}/${config.maxListScrolls}")
                delayByRefreshWindow()
                enterState(AutomationState.SCANNING_TASKS, "报名后继续扫描")
            } else {
                enterState(AutomationState.SCROLLING_TASKS, "报名后达到下滑阈值")
            }
            return
        }
        if (elapsedInState() > 1_500L) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            touchAction("继续返回商单列表")
        }
    }

    private fun handleReturningHome(root: AccessibilityNodeInfo?) {
        if (isHome(root)) {
            runtime.browsedCount = 0
            enterState(AutomationState.BROWSING_HOME, "回到首页，开始浏览作品")
            return
        }
        if (returnBackCount >= MAX_BACK_TO_HOME) {
            DewuLauncher.launch(service)
            touchAction("返回首页失败，重新拉起得物")
            enterState(AutomationState.WAITING_HOME, "等待首页")
            returnBackCount = 0
            return
        }
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        returnBackCount++
        touchAction("返回首页 $returnBackCount/$MAX_BACK_TO_HOME")
    }

    private fun handleBrowsingHome(root: AccessibilityNodeInfo?) {
        if (!isHome(root)) return
        if (config.homeBrowseCount <= 0 || runtime.browsedCount >= config.homeBrowseCount) {
            reentryAfterBrowse = true
            runtime.userSortApplied = false
            runtime.listScrollCount = 0
            runtime.browsedCount = 0
            categoryStep = 0
            sortMenuOpened = false

            val restMs = randomBetween(config.restMinMinutes, config.restMaxMinutes) * 60_000L
            if (restMs > 0) {
                roundRestUntil = SystemClock.elapsedRealtime() + restMs
                enterState(AutomationState.WAITING_ROUND_REST, "本轮结束，休息 ${restMs / 60_000} 分钟")
            } else {
                enterState(AutomationState.REENTERING_PROFILE, "重新进入个人中心")
            }
            return
        }

        if (tapHomeContent(root)) {
            remainingContentSwipes = randomBetween(config.imageSwipeMin, config.imageSwipeMax)
            contentDwellUntil = SystemClock.elapsedRealtime() + randomBetween(3, 8) * 1_000L
            enterState(AutomationState.BROWSING_CONTENT, "浏览首页作品 ${runtime.browsedCount + 1}/${config.homeBrowseCount}")
        }
    }

    private fun handleBrowsingContent(root: AccessibilityNodeInfo?) {
        if (root == null) return
        if (isHome(root)) {
            runtime.browsedCount++
            enterState(AutomationState.BROWSING_HOME, "继续浏览首页")
            return
        }

        val looksVideo = NodeUtils.hasAnyText(root, DewuSelectors.VIDEO_MARKERS)
        val looksImage = NodeUtils.hasAnyText(root, DewuSelectors.IMAGE_MARKERS)

        if (!looksVideo && (looksImage || remainingContentSwipes > 0)) {
            if (remainingContentSwipes > 0) {
                val left = Random.nextBoolean()
                performHorizontalSwipe(left = left, label = if (left) "图文左滑" else "图文右滑")
                remainingContentSwipes--
                return
            }
        }

        if (SystemClock.elapsedRealtime() >= contentDwellUntil || (!looksVideo && remainingContentSwipes <= 0)) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            touchAction("作品浏览完成返回首页")
        }
    }

    private fun handleRoundRest(root: AccessibilityNodeInfo?) {
        if (SystemClock.elapsedRealtime() < roundRestUntil) return
        roundRestUntil = 0L
        if (!isHome(root)) {
            enterState(AutomationState.WAITING_HOME, "休息结束，等待首页")
            return
        }
        enterState(AutomationState.REENTERING_PROFILE, "休息结束，重新进入个人中心")
    }

    private fun findEligibleRegisterNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val nodes = NodeUtils.findAllByTexts(root, DewuSelectors.REGISTER_BUTTONS)
        for (node in nodes) {
            val label = node.text?.toString()?.trim().orEmpty().ifBlank {
                node.contentDescription?.toString()?.trim().orEmpty()
            }
            if (label !in DewuSelectors.REGISTER_BUTTONS) continue

            val contextText = NodeUtils.ancestorText(node, levels = 5)
            val signature = contextText.take(600).hashCode().toString()
            if (signature in visitedTaskSignatures) continue
            if (config.excludedWords.any { contextText.contains(it, ignoreCase = true) }) continue

            val price = extractPrice(contextText)
            if (price != null && price !in config.minPrice..config.maxPrice) continue
            if (price == null && (config.minPrice > 0.0 || config.maxPrice < 9_999_999.0)) continue
            return node
        }
        return null
    }

    private fun taskSignature(node: AccessibilityNodeInfo): String =
        NodeUtils.ancestorText(node, levels = 5).take(600).hashCode().toString()

    private fun extractPrice(text: String): Double? {
        val currency = Regex("[¥￥]\\s*(\\d+(?:\\.\\d+)?)")
            .find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        if (currency != null) return currency
        return Regex("(\\d+(?:\\.\\d+)?)\\s*元")
            .find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    private fun isDewuRoot(root: AccessibilityNodeInfo?): Boolean =
        root?.packageName?.toString() == DewuSelectors.PACKAGE_NAME

    private fun isHome(root: AccessibilityNodeInfo?): Boolean =
        isDewuRoot(root) &&
            NodeUtils.hasAnyText(root, DewuSelectors.HOME_MARKERS) &&
            NodeUtils.hasAnyText(root, DewuSelectors.PROFILE_TAB)

    private fun isProfile(root: AccessibilityNodeInfo?): Boolean =
        isDewuRoot(root) && (
            NodeUtils.hasAnyText(root, DewuSelectors.BRAND_ENTRY) ||
                (NodeUtils.hasAnyText(root, DewuSelectors.PROFILE_MARKERS) && NodeUtils.hasAnyText(root, DewuSelectors.MORE))
            )

    private fun isBrandPage(root: AccessibilityNodeInfo?): Boolean =
        isDewuRoot(root) &&
            NodeUtils.hasAnyText(root, DewuSelectors.SORT_ENTRY) &&
            NodeUtils.hasAnyText(root, DewuSelectors.BRAND_PAGE_MARKERS)

    private fun isRegistrationSuccess(root: AccessibilityNodeInfo?): Boolean =
        NodeUtils.hasAnyText(root, DewuSelectors.REGISTER_SUCCESS)

    private fun clickText(root: AccessibilityNodeInfo?, texts: Collection<String>, label: String): Boolean {
        val node = NodeUtils.findFirstByTexts(root, texts)
        if (!NodeUtils.clickNode(node)) return false
        touchAction(label)
        return true
    }

    private fun tapHomeContent(root: AccessibilityNodeInfo?): Boolean {
        val screen = ScreenInfo.from(service)
        val candidates = findLargeClickableNodes(root, screen)
        val node = candidates.randomOrNull()
        if (node != null && NodeUtils.clickNode(node)) {
            touchAction("点击首页作品节点")
            return true
        }

        val x = (screen.widthPx * Random.nextDouble(0.28, 0.72)).toFloat()
        val y = (screen.heightPx * Random.nextDouble(0.28, 0.67)).toFloat()
        return performTap(x, y, "首页作品坐标兜底")
    }

    private fun findLargeClickableNodes(root: AccessibilityNodeInfo?, screen: ScreenInfo): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && result.size < 30) {
            val node = queue.removeFirst()
            if (node.isClickable && node.isEnabled) {
                val rect = Rect().also(node::getBoundsInScreen)
                val centerY = rect.centerY()
                if (
                    rect.width() >= screen.widthPx * 0.35 &&
                    rect.height() >= screen.heightPx * 0.08 &&
                    centerY in (screen.heightPx * 0.18).roundToInt()..(screen.heightPx * 0.76).roundToInt()
                ) {
                    result += node
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return result
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
        val y = screen.heightPx * 0.52f
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
        return Random.nextInt(low, high + 1)
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
