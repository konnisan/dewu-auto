package com.konnisan.dewuauto

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.konnisan.dewuauto.accessibility.AccessibilityStatus
import com.konnisan.dewuauto.accessibility.DewuAccessibilityService
import com.konnisan.dewuauto.automation.DewuLauncher
import com.konnisan.dewuauto.automation.DewuSelectors
import com.konnisan.dewuauto.config.AutomationConfig
import com.konnisan.dewuauto.config.AutomationPrefs
import com.konnisan.dewuauto.util.ScreenInfo

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: AutomationPrefs
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var tvStatus: TextView
    private lateinit var tvScreen: TextView
    private lateinit var spCategory: Spinner
    private lateinit var spSortMode: Spinner

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

        tvStatus = findViewById(R.id.tvStatus)
        tvScreen = findViewById(R.id.tvScreen)
        spCategory = findViewById(R.id.spCategory)
        spSortMode = findViewById(R.id.spSortMode)

        setupSpinners()
        bindConfig(prefs.load())

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnOpenDewu).setOnClickListener {
            if (!DewuLauncher.launch(this)) toast("未检测到得物")
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            startAutomation()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            DewuAccessibilityService.instance?.stopAutomation()
            toast("已发送停止指令")
        }
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
        if (!AccessibilityStatus.isEnabled(this)) {
            toast("请先开启“得物自动报名服务”无障碍权限")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        val service = DewuAccessibilityService.instance
        if (service == null) {
            toast("无障碍服务已开启但尚未连接，请关闭后重新开启一次")
            return
        }

        val config = readConfig().normalized()
        prefs.save(config)
        service.startAutomation(config)
        toast("自动化已启动")
    }

    private fun readConfig(): AutomationConfig = AutomationConfig(
        cardKey = text(R.id.etCardKey),
        productCategory = spCategory.selectedItem?.toString() ?: "服装",
        sortMode = spSortMode.selectedItem?.toString() ?: "默认排序",
        maxListScrolls = intValue(R.id.etMaxScrolls, 5),
        homeBrowseCount = intValue(R.id.etHomeBrowseCount, 1),
        restMinMinutes = intValue(R.id.etRestMin, 5),
        restMaxMinutes = intValue(R.id.etRestMax, 10),
        imageSwipeMin = intValue(R.id.etImageSwipeMin, 1),
        imageSwipeMax = intValue(R.id.etImageSwipeMax, 8),
        targetRegistrationCount = intValue(R.id.etTargetRegistrations, 40),
        minPrice = doubleValue(R.id.etMinPrice, 21.0),
        maxPrice = doubleValue(R.id.etMaxPrice, 9_999_999.0),
        excludedWords = text(R.id.etExcludedWords)
            .split(",", "，")
            .map { it.trim() }
            .filter { it.isNotEmpty() },
        sizeSpec = text(R.id.etSizeSpec),
        refreshMinSeconds = intValue(R.id.etRefreshMin, 2),
        refreshMaxSeconds = intValue(R.id.etRefreshMax, 10),
        autoConfirmRegistration = findViewById<CheckBox>(R.id.cbAutoConfirm).isChecked,
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
        setText(R.id.etTargetRegistrations, config.targetRegistrationCount)
        setText(R.id.etMinPrice, config.minPrice)
        setText(R.id.etMaxPrice, config.maxPrice)
        setText(R.id.etExcludedWords, config.excludedWords.joinToString(","))
        setText(R.id.etSizeSpec, config.sizeSpec)
        setText(R.id.etRefreshMin, config.refreshMinSeconds)
        setText(R.id.etRefreshMax, config.refreshMaxSeconds)
        findViewById<CheckBox>(R.id.cbAutoConfirm).isChecked = config.autoConfirmRegistration
    }

    private fun refreshStatus() {
        val enabled = AccessibilityStatus.isEnabled(this)
        val runtime = DewuAccessibilityService.instance?.snapshot()
        val serviceText = if (enabled) "无障碍：已开启" else "无障碍：未开启"
        tvStatus.text = if (runtime == null) {
            "$serviceText | 状态：未运行"
        } else {
            "$serviceText | ${runtime.state} | 报名 ${runtime.registrationCount} | ${runtime.lastMessage}"
        }
        val screen = ScreenInfo.from(this)
        tvScreen.text = "屏幕：${screen.widthPx} × ${screen.heightPx} px"
    }

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
