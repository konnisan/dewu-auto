package com.konnisan.dewuauto

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.konnisan.dewuauto.accessibility.AccessibilityStatus
import com.konnisan.dewuauto.accessibility.DewuAccessibilityService
import com.konnisan.dewuauto.automation.AutomationRuntime
import com.konnisan.dewuauto.automation.AutomationState
import com.konnisan.dewuauto.automation.DewuLauncher
import com.konnisan.dewuauto.automation.DewuSelectors
import com.konnisan.dewuauto.automation.PreviewTaskResult
import com.konnisan.dewuauto.config.AutomationConfig
import com.konnisan.dewuauto.config.AutomationPrefs
import com.konnisan.dewuauto.license.LicenseManager
import com.konnisan.dewuauto.license.PurchaseManager
import com.konnisan.dewuauto.util.ScreenInfo

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: AutomationPrefs
    private lateinit var licenseManager: LicenseManager
    private lateinit var purchaseManager: PurchaseManager
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var tvRuntimeStatus: TextView
    private lateinit var tvAccessibility: TextView
    private lateinit var tvDewuVersion: TextView
    private lateinit var tvAccountNotice: TextView
    private lateinit var tvScreen: TextView
    private lateinit var tvScannedCount: TextView
    private lateinit var tvEligibleCount: TextView
    private lateinit var tvExcludedCount: TextView
    private lateinit var tvEnrollmentSuccessCount: TextView
    private lateinit var tvResultEmpty: TextView
    private lateinit var resultList: LinearLayout
    private lateinit var spCategory: Spinner
    private lateinit var spSortMode: Spinner
    private lateinit var btnFinalConfirmationMode: MaterialButton
    private lateinit var btnStart: Button

    private var finalConfirmationArmed = false
    private var lastClearedTerminalRunId = ""
    private var lastRenderedResults: List<PreviewTaskResult> = emptyList()
    private var isLicenseVerifying = false
    private var isPurchasePending = false
    private var licenseStatusOverride: String? = null

    private val uiTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            uiHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = AutomationPrefs(this)
        licenseManager = LicenseManager(applicationContext)
        purchaseManager = PurchaseManager(applicationContext)

        bindViews()
        setupSpinners()
        bindConfig(prefs.load())
        setupActions()
        lastClearedTerminalRunId = DewuAccessibilityService.instance?.snapshot()?.runId.orEmpty()
        renderFinalConfirmationButton(finalConfirmationArmed, locked = false)
    }

    override fun onResume() {
        super.onResume()
        uiHandler.removeCallbacks(uiTicker)
        uiHandler.post(uiTicker)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(uiTicker)
        super.onPause()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            DewuAccessibilityService.instance?.stopAutomation()
            licenseManager.shutdown()
            purchaseManager.shutdown()
        }
        super.onDestroy()
    }

    private fun bindViews() {
        tvRuntimeStatus = findViewById(R.id.tvRuntimeStatus)
        tvAccessibility = findViewById(R.id.tvAccessibility)
        tvDewuVersion = findViewById(R.id.tvDewuVersion)
        tvAccountNotice = findViewById(R.id.tvAccountNotice)
        tvScreen = findViewById(R.id.tvScreen)
        tvScannedCount = findViewById(R.id.tvScannedCount)
        tvEligibleCount = findViewById(R.id.tvEligibleCount)
        tvExcludedCount = findViewById(R.id.tvExcludedCount)
        tvEnrollmentSuccessCount = findViewById(R.id.tvEnrollmentSuccessCount)
        tvResultEmpty = findViewById(R.id.tvResultEmpty)
        resultList = findViewById(R.id.resultList)
        spCategory = findViewById(R.id.spCategory)
        spSortMode = findViewById(R.id.spSortMode)
        btnFinalConfirmationMode = findViewById(R.id.btnFinalConfirmationMode)
        btnStart = findViewById(R.id.btnStart)
    }

    private fun setupActions() {
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnOpenDewu).setOnClickListener {
            if (!DewuLauncher.launch(this)) toast("未检测到得物")
        }
        btnStart.setOnClickListener {
            startAutomation()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            DewuAccessibilityService.instance?.stopAutomation()
            licenseManager.stopHeartbeat()
            finalConfirmationArmed = false
            licenseStatusOverride = "已停止 · 下次开始会重新验证卡密"
            renderFinalConfirmationButton(armed = false, locked = false)
            toast("已停止自动报名任务")
        }
        btnFinalConfirmationMode.setOnClickListener {
            val runtime = DewuAccessibilityService.instance?.snapshot()
            if (runtime != null && isActiveState(runtime.state)) {
                toast("任务运行期间不能更改最终确认设置")
                return@setOnClickListener
            }
            finalConfirmationArmed = !finalConfirmationArmed
            renderFinalConfirmationButton(finalConfirmationArmed, locked = false)
            toast(if (finalConfirmationArmed) "本轮将执行真实最终报名" else "已切换为多任务演练，不会最终报名")
        }
    }

    private fun setupSpinners() {
        spCategory.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            DewuSelectors.PRODUCT_CATEGORIES,
        )
        spSortMode.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            DewuSelectors.SORT_OPTIONS,
        )
    }

    private fun startAutomation() {
        if (isLicenseVerifying || isPurchasePending) return

        val config = readConfig().normalized()
        if (config.cardKey.isBlank()) {
            beginPurchase()
            return
        }
        if (config.sizeSpec.isBlank()) {
            toast("请填写样品规格，避免报名时误选")
            return
        }
        if (!AccessibilityStatus.isEnabled(this)) {
            toast("请先开启“得物自动报名服务”无障碍权限")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (DewuAccessibilityService.instance == null) {
            toast("无障碍服务已开启但尚未连接，请关闭后重新开启一次")
            return
        }

        prefs.save(config)
        isLicenseVerifying = true
        btnStart.isEnabled = false
        licenseStatusOverride = "卡密验证中…"
        refreshStatus()

        licenseManager.verify(config.cardKey) { result ->
            isLicenseVerifying = false
            btnStart.isEnabled = true
            result.fold(
                onSuccess = {
                    licenseStatusOverride = null
                    startVerifiedAutomation(config)
                },
                onFailure = { error ->
                    val message = error.message?.takeIf { it.isNotBlank() } ?: "卡密验证失败"
                    licenseStatusOverride = "卡密验证失败 · $message"
                    toast(message)
                },
            )
        }
    }

    private fun beginPurchase() {
        if (isPurchasePending) return
        isPurchasePending = true
        btnStart.isEnabled = false
        licenseStatusOverride = "正在创建 30 天测试订单…"
        refreshStatus()

        purchaseManager.createOrder(planDays = 30) { result ->
            result.fold(
                onSuccess = { order ->
                    licenseStatusOverride = "等待测试支付 · ${order.orderNo}"
                    val opened = runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(order.payUrl)))
                    }.isSuccess
                    if (!opened) {
                        isPurchasePending = false
                        btnStart.isEnabled = true
                        licenseStatusOverride = "订单已创建 · 无法打开支付网页"
                        toast("无法打开测试支付网页")
                        return@fold
                    }

                    purchaseManager.startPolling(
                        orderNo = order.orderNo,
                        clientToken = order.clientToken,
                        onPaid = { cardKey ->
                            isPurchasePending = false
                            btnStart.isEnabled = true
                            setText(R.id.etCardKey, cardKey)
                            prefs.save(readConfig().normalized())
                            licenseStatusOverride = "支付成功 · 卡密已自动填入"
                            toast("支付成功，卡密已自动填入；再次点击开始即可验证并运行")
                        },
                        onFailure = { reason ->
                            isPurchasePending = false
                            btnStart.isEnabled = true
                            licenseStatusOverride = "订单失败 · $reason"
                            toast(reason)
                        },
                    )
                    toast("已创建 30 天测试订单，请在网页完成模拟支付")
                },
                onFailure = { error ->
                    isPurchasePending = false
                    btnStart.isEnabled = true
                    val message = error.message?.takeIf { it.isNotBlank() } ?: "创建订单失败"
                    licenseStatusOverride = "创建订单失败 · $message"
                    toast(message)
                },
            )
        }
    }

    private fun startVerifiedAutomation(config: AutomationConfig) {
        if (!AccessibilityStatus.isEnabled(this)) {
            licenseStatusOverride = "卡密已验证 · 无障碍权限未开启"
            toast("卡密已验证，但无障碍权限已关闭")
            return
        }

        val service = DewuAccessibilityService.instance
        if (service == null) {
            licenseStatusOverride = "卡密已验证 · 无障碍服务未连接"
            toast("卡密已验证，但无障碍服务未连接")
            return
        }

        service.startAutomation(config)
        renderFinalConfirmationButton(config.finalConfirmationEnabled, locked = true)

        if (!DewuLauncher.launch(this)) {
            service.stopAutomation()
            licenseManager.stopHeartbeat()
            finalConfirmationArmed = false
            licenseStatusOverride = "卡密已验证 · 未检测到得物"
            renderFinalConfirmationButton(armed = false, locked = false)
            toast("未检测到得物，请确认已安装")
            return
        }

        licenseManager.startHeartbeat { reason ->
            DewuAccessibilityService.instance?.stopAutomation()
            finalConfirmationArmed = false
            licenseStatusOverride = "授权已失效 · $reason"
            renderFinalConfirmationButton(armed = false, locked = false)
            toast("卡密授权失效：$reason")
        }

        toast(
            if (config.finalConfirmationEnabled) {
                "卡密验证成功，真实报名已启动；达到目标次数后停止"
            } else {
                "卡密验证成功，多任务演练已启动；最终弹窗会自动取消"
            },
        )
    }

    private fun readConfig(): AutomationConfig = AutomationConfig(
        cardKey = text(R.id.etCardKey),
        productCategory = spCategory.selectedItem?.toString() ?: "服装",
        sortMode = spSortMode.selectedItem?.toString() ?: "最近发布",
        targetEnrollmentCount = intValue(R.id.etTargetEnrollments, 1),
        finalConfirmationEnabled = finalConfirmationArmed,
        maxListScrolls = intValue(R.id.etMaxScrolls, 5),
        minPrice = doubleValue(R.id.etMinPrice, 21.0),
        maxPrice = doubleValue(R.id.etMaxPrice, 9_999_999.0),
        excludedWords = text(R.id.etExcludedWords)
            .split(Regex("(?:##|[,，、;；\\s]+)"))
            .map { it.trim() }
            .filter { it.isNotEmpty() },
        sizeSpec = text(R.id.etSizeSpec),
    )

    private fun bindConfig(config: AutomationConfig) {
        setText(R.id.etCardKey, config.cardKey)
        selectSpinner(spCategory, config.productCategory)
        selectSpinner(spSortMode, config.sortMode)
        setText(R.id.etTargetEnrollments, config.targetEnrollmentCount)
        setText(R.id.etMaxScrolls, config.maxListScrolls)
        setText(R.id.etMinPrice, config.minPrice)
        setText(R.id.etMaxPrice, config.maxPrice)
        setText(R.id.etExcludedWords, config.excludedWords.joinToString(","))
        setText(R.id.etSizeSpec, config.sizeSpec)
    }

    private fun refreshStatus() {
        val enabled = AccessibilityStatus.isEnabled(this)
        tvAccessibility.text = if (enabled) "● 无障碍  已开启" else "● 无障碍  未开启"
        tvAccessibility.setTextColor(Color.parseColor(if (enabled) "#067F85" else "#687278"))
        tvDewuVersion.text = dewuVersion()?.let { "得物  $it" } ?: "得物  未检测"

        val screen = ScreenInfo.from(this)
        tvScreen.text = "${screen.widthPx} × ${screen.heightPx} · 自动报名 V1.4 · 默认演练"

        licenseStatusOverride?.let {
            tvRuntimeStatus.text = it
            return
        }

        val runtime = DewuAccessibilityService.instance?.snapshot()
        if (runtime == null) {
            tvRuntimeStatus.text = "等待开始 · 需要有效卡密"
            return
        }

        renderRuntime(runtime)
    }

    private fun renderRuntime(runtime: AutomationRuntime) {
        val active = isActiveState(runtime.state)
        val terminal = runtime.state in setOf(
            AutomationState.IDLE,
            AutomationState.FINISHED,
            AutomationState.ERROR,
            AutomationState.PAUSED_FOR_SECURITY,
        )
        if (terminal && runtime.runId.isNotBlank() && runtime.runId != lastClearedTerminalRunId) {
            finalConfirmationArmed = false
            lastClearedTerminalRunId = runtime.runId
        }
        val displayedMode = if (active) runtime.finalConfirmationEnabled else finalConfirmationArmed
        renderFinalConfirmationButton(displayedMode, locked = active)

        val completed = if (runtime.finalConfirmationEnabled) {
            runtime.enrollmentSuccessCount
        } else {
            runtime.rehearsalCompletedCount
        }
        val modeText = if (runtime.finalConfirmationEnabled) "真实报名" else "演练模式"
        tvRuntimeStatus.text = "$modeText · $completed/${runtime.targetTaskCount} · ${runtime.lastMessage}"
        tvScannedCount.text = "已扫描\n${runtime.scannedCount}"
        tvEligibleCount.text = "符合\n${runtime.eligibleCount}"
        tvExcludedCount.text = "已跳过\n${runtime.excludedCount + runtime.enrollmentFailedCount}"
        tvEnrollmentSuccessCount.text = if (runtime.finalConfirmationEnabled) {
            "已报名\n${runtime.enrollmentSuccessCount}"
        } else {
            "已演练\n${runtime.rehearsalCompletedCount}"
        }
        tvAccountNotice.text = if (runtime.requiresCreatorEnrollment) {
            "当前页面仅显示“申请入驻”，任务已安全停止"
        } else {
            if (runtime.finalConfirmationEnabled) {
                "真实报名模式 · 最终确认后自动返回商单继续"
            } else {
                "多任务演练 · 最终弹窗停留后自动取消并继续"
            }
        }

        renderTaskResults(runtime.taskResults)
    }

    private fun renderTaskResults(results: List<PreviewTaskResult>) {
        if (results == lastRenderedResults) return
        lastRenderedResults = results.toList()
        tvResultEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
        resultList.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
        resultList.removeAllViews()
        results.forEachIndexed { index, result ->
            val view = TextView(this)
            val layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (index > 0) topMargin = (8 * resources.displayMetrics.density).toInt()
            }
            view.layoutParams = layoutParams
            view.setPadding(
                (14 * resources.displayMetrics.density).toInt(),
                (14 * resources.displayMetrics.density).toInt(),
                (14 * resources.displayMetrics.density).toInt(),
                (14 * resources.displayMetrics.density).toInt(),
            )
            view.textSize = 13f
            bindResult(view, result)
            resultList.addView(view)
        }
    }

    private fun bindResult(view: TextView, result: PreviewTaskResult) {
        val isSubscription = result.enrollmentStatus == "未点击"
        val status = when (result.enrollmentStatus) {
            "未点击" -> "尚未上架"
            "仅视频" -> "仅视频"
            else -> if (result.eligible) "符合" else "已排除"
        }
        view.setBackgroundResource(
            when {
                isSubscription -> R.drawable.bg_result_neutral
                result.eligible -> R.drawable.bg_result_eligible
                else -> R.drawable.bg_result_excluded
            },
        )
        view.setTextColor(
            Color.parseColor(
                when {
                    isSubscription -> "#687278"
                    result.eligible -> "#256E2A"
                    else -> "#4F575B"
                },
            ),
        )
        view.text = buildString {
            append(status).append("  ").append(result.title)
            append('\n').append(result.rewardText)
            append(" · ").append(result.capacityText)
            append(" · ").append(result.deadlineText)
            append('\n').append("内容类型：").append(result.contentTypeText)
            append(" · ").append(result.enrollmentStatus)
            append('\n').append(result.reason)
            if (!result.matchedField.isNullOrBlank()) {
                append(" · 命中：").append(result.matchedField)
                if (!result.matchedWord.isNullOrBlank()) append(" / ").append(result.matchedWord)
            }
        }
    }

    private fun renderFinalConfirmationButton(armed: Boolean, locked: Boolean) {
        btnFinalConfirmationMode.isEnabled = !locked
        btnFinalConfirmationMode.text = if (armed) {
            "最终确认报名：已开启（仅本轮）"
        } else {
            "最终确认报名：未开启"
        }
        btnFinalConfirmationMode.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (armed) "#C66A00" else "#FFFFFF"),
        )
        btnFinalConfirmationMode.strokeColor = ColorStateList.valueOf(Color.parseColor("#C66A00"))
        btnFinalConfirmationMode.setTextColor(Color.parseColor(if (armed) "#FFFFFF" else "#B96200"))
    }

    private fun isActiveState(state: AutomationState): Boolean = state !in setOf(
        AutomationState.IDLE,
        AutomationState.FINISHED,
        AutomationState.ERROR,
        AutomationState.PAUSED_FOR_SECURITY,
    )

    @Suppress("DEPRECATION")
    private fun dewuVersion(): String? = runCatching {
        packageManager.getPackageInfo(DewuSelectors.PACKAGE_NAME, 0).versionName
    }.getOrNull()

    private fun text(id: Int): String = findViewById<EditText>(id).text?.toString()?.trim().orEmpty()
    private fun intValue(id: Int, fallback: Int): Int = text(id).toIntOrNull() ?: fallback
    private fun doubleValue(id: Int, fallback: Double): Double = text(id).toDoubleOrNull() ?: fallback

    private fun setText(id: Int, value: Any) {
        findViewById<EditText>(id).setText(value.toString())
    }

    private fun selectSpinner(spinner: Spinner, value: String) {
        val adapter = spinner.adapter ?: return
        for (i in 0 until adapter.count) {
            if (adapter.getItem(i)?.toString() == value) {
                spinner.setSelection(i)
                return
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
