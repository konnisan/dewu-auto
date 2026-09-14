package com.konnisan.dewuauto

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.konnisan.dewuauto.accessibility.AccessibilityStatus
import com.konnisan.dewuauto.accessibility.DewuAccessibilityService
import com.konnisan.dewuauto.automation.AutomationRuntime
import com.konnisan.dewuauto.automation.DewuLauncher
import com.konnisan.dewuauto.automation.DewuSelectors
import com.konnisan.dewuauto.automation.PreviewTaskResult
import com.konnisan.dewuauto.config.AutomationConfig
import com.konnisan.dewuauto.config.AutomationPrefs
import com.konnisan.dewuauto.license.LicenseManager
import com.konnisan.dewuauto.util.ScreenInfo

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: AutomationPrefs
    private lateinit var licenseManager: LicenseManager
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var tvRuntimeStatus: TextView
    private lateinit var tvAccessibility: TextView
    private lateinit var tvDewuVersion: TextView
    private lateinit var tvAccountNotice: TextView
    private lateinit var tvScreen: TextView
    private lateinit var tvScannedCount: TextView
    private lateinit var tvEligibleCount: TextView
    private lateinit var tvExcludedCount: TextView
    private lateinit var tvResultOne: TextView
    private lateinit var tvResultTwo: TextView
    private lateinit var tvAdvancedSummary: TextView
    private lateinit var advancedPanel: LinearLayout
    private lateinit var advancedChevron: ImageView
    private lateinit var spCategory: Spinner
    private lateinit var spSortMode: Spinner
    private lateinit var btnStart: Button

    private var isLicenseVerifying = false
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

        bindViews()
        setupSpinners()
        bindConfig(prefs.load())
        setupActions()
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
        tvResultOne = findViewById(R.id.tvResultOne)
        tvResultTwo = findViewById(R.id.tvResultTwo)
        tvAdvancedSummary = findViewById(R.id.tvAdvancedSummary)
        advancedPanel = findViewById(R.id.advancedPanel)
        advancedChevron = findViewById(R.id.ivAdvancedChevron)
        spCategory = findViewById(R.id.spCategory)
        spSortMode = findViewById(R.id.spSortMode)
        btnStart = findViewById(R.id.btnStart)
    }

    private fun setupActions() {
        findViewById<View>(R.id.advancedHeader).setOnClickListener {
            val expanding = advancedPanel.visibility != View.VISIBLE
            advancedPanel.visibility = if (expanding) View.VISIBLE else View.GONE
            advancedChevron.rotation = if (expanding) 90f else 0f
            advancedChevron.contentDescription = if (expanding) "收起高级设置" else "展开高级设置"
        }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnOpenDewu).setOnClickListener {
            if (!DewuLauncher.launch(this)) toast("未检测到得物")
        }
        btnStart.setOnClickListener {
            startPreview()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            DewuAccessibilityService.instance?.stopAutomation()
            licenseManager.stopHeartbeat()
            licenseStatusOverride = "已停止 · 下次开始会重新验证卡密"
            toast("已停止筛选预演")
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

    private fun startPreview() {
        if (isLicenseVerifying) return

        val config = readConfig().normalized()
        if (config.cardKey.isBlank()) {
            licenseStatusOverride = "卡密未验证 · 请先输入卡密"
            toast("请输入卡密")
            return
        }

        if (!AccessibilityStatus.isEnabled(this)) {
            toast("请先开启“得物任务筛选服务”无障碍权限")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        if (DewuAccessibilityService.instance == null) {
            toast("无障碍服务已开启但尚未连接，请关闭后重新开启一次")
            return
        }

        prefs.save(config)
        updateAdvancedSummary(config)
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
                    startVerifiedPreview(config)
                },
                onFailure = { error ->
                    val message = error.message?.takeIf { it.isNotBlank() } ?: "卡密验证失败"
                    licenseStatusOverride = "卡密验证失败 · $message"
                    toast(message)
                },
            )
        }
    }

    private fun startVerifiedPreview(config: AutomationConfig) {
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
        if (!DewuLauncher.launch(this)) {
            service.stopAutomation()
            licenseManager.stopHeartbeat()
            licenseStatusOverride = "卡密已验证 · 未检测到得物"
            toast("未检测到得物，请确认已安装")
            return
        }

        licenseManager.startHeartbeat { reason ->
            DewuAccessibilityService.instance?.stopAutomation()
            licenseStatusOverride = "授权已失效 · $reason"
            toast("卡密授权失效：$reason")
        }

        toast("卡密验证成功，筛选预演已启动")
    }

    private fun readConfig(): AutomationConfig = AutomationConfig(
        cardKey = text(R.id.etCardKey),
        productCategory = spCategory.selectedItem?.toString() ?: "服装",
        sortMode = spSortMode.selectedItem?.toString() ?: "最近发布",
        maxListScrolls = intValue(R.id.etMaxScrolls, 5),
        homeBrowseCount = intValue(R.id.etHomeBrowseCount, 1),
        restMinMinutes = intValue(R.id.etRestMin, 5),
        restMaxMinutes = intValue(R.id.etRestMax, 10),
        imageSwipeMin = intValue(R.id.etImageSwipeMin, 1),
        imageSwipeMax = intValue(R.id.etImageSwipeMax, 8),
        minPrice = doubleValue(R.id.etMinPrice, 21.0),
        maxPrice = doubleValue(R.id.etMaxPrice, 9_999_999.0),
        excludedWords = text(R.id.etExcludedWords)
            .split(Regex("(?:##|[,，、;；\\s]+)"))
            .map { it.trim() }
            .filter { it.isNotEmpty() },
        sizeSpec = text(R.id.etSizeSpec),
        refreshMinSeconds = intValue(R.id.etRefreshMin, 2),
        refreshMaxSeconds = intValue(R.id.etRefreshMax, 10),
    )

    private fun bindConfig(config: AutomationConfig) {
        setText(R.id.etCardKey, config.cardKey)
        selectSpinner(spCategory, config.productCategory)
        selectSpinner(spSortMode, config.sortMode)
        setText(R.id.etMaxScrolls, config.maxListScrolls)
        setText(R.id.etHomeBrowseCount, config.homeBrowseCount)
        setText(R.id.etRestMin, config.restMinMinutes)
        setText(R.id.etRestMax, config.restMaxMinutes)
        setText(R.id.etImageSwipeMin, config.imageSwipeMin)
        setText(R.id.etImageSwipeMax, config.imageSwipeMax)
        setText(R.id.etMinPrice, config.minPrice)
        setText(R.id.etMaxPrice, config.maxPrice)
        setText(R.id.etExcludedWords, config.excludedWords.joinToString(","))
        setText(R.id.etSizeSpec, config.sizeSpec)
        setText(R.id.etRefreshMin, config.refreshMinSeconds)
        setText(R.id.etRefreshMax, config.refreshMaxSeconds)
        updateAdvancedSummary(config)
    }

    private fun refreshStatus() {
        val enabled = AccessibilityStatus.isEnabled(this)
        tvAccessibility.text = if (enabled) "● 无障碍  已开启" else "● 无障碍  未开启"
        tvAccessibility.setTextColor(Color.parseColor(if (enabled) "#067F85" else "#687278"))
        tvDewuVersion.text = dewuVersion()?.let { "得物  $it" } ?: "得物  未检测"

        val screen = ScreenInfo.from(this)
        tvScreen.text = "${screen.widthPx} × ${screen.heightPx} · 不会点击报名或申请入驻"

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
        tvRuntimeStatus.text = "${runtime.state} · ${runtime.lastMessage}"
        tvScannedCount.text = "已扫描\n${runtime.scannedCount}"
        tvEligibleCount.text = "符合\n${runtime.eligibleCount}"
        tvExcludedCount.text = "已排除\n${runtime.excludedCount}"
        tvAccountNotice.text = if (runtime.requiresCreatorEnrollment) {
            "已检测到“申请入驻”，当前账号按非达人模式安全预演"
        } else {
            "当前账号可继续预览筛选规则；不会提交报名或入驻申请"
        }

        bindResult(tvResultOne, runtime.recentResults.getOrNull(0))
        bindResult(tvResultTwo, runtime.recentResults.getOrNull(1))
    }

    private fun bindResult(view: TextView, result: PreviewTaskResult?) {
        if (result == null) {
            view.setBackgroundResource(R.drawable.bg_result_neutral)
            view.setTextColor(Color.parseColor("#687278"))
            view.text = "尚无扫描结果"
            return
        }

        val status = if (result.eligible) "符合" else "已排除"
        view.setBackgroundResource(
            if (result.eligible) R.drawable.bg_result_eligible else R.drawable.bg_result_excluded,
        )
        view.setTextColor(Color.parseColor(if (result.eligible) "#256E2A" else "#4F575B"))
        view.text = buildString {
            append(status).append("  ").append(result.title)
            append('\n').append(result.rewardText)
            append(" · ").append(result.capacityText)
            append(" · ").append(result.deadlineText)
            append('\n').append(result.reason)
        }
    }

    private fun updateAdvancedSummary(config: AutomationConfig) {
        tvAdvancedSummary.text =
            "下滑 ${config.maxListScrolls} 次 · 刷新 ${config.refreshMinSeconds}–${config.refreshMaxSeconds} 秒"
    }

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
