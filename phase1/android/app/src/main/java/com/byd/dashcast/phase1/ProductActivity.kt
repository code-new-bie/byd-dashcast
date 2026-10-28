package com.byd.dashcast.phase1

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Switch

/** Product launcher, implemented in Kotlin. Engineering tools are an explicit diagnostics-only route.
 * @author Codex
 * @date 2026/10/21
 */
class ProductActivity : Activity() {
    private lateinit var settings: ProductSettings
    private lateinit var control: ProductController
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var connection: TextView
    private lateinit var detail: TextView
    private var primary: Button? = null
    private var roleSwitch: Switch? = null
    private var reflectingRole = false
    private var apps: List<ProductApp> = emptyList()
    private var page = 0
    private val main = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { updateStatus(); main.postDelayed(this, 500) }
    }
    private val light get() = settings.light
    private val textColor get() = Color.parseColor(if (light) "#15243B" else "#F1F5FC")
    private val muted get() = Color.parseColor(if (light) "#53647D" else "#A4B3CB")
    private val canvasColor get() = Color.parseColor(if (light) "#EDF2F8" else "#0B1322")
    private val surface get() = Color.parseColor(if (light) "#FFFFFF" else "#162237")
    private val accent = Color.parseColor("#2678E8")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = ProductSettings(this)
        control = ProductController.get(this)
        apps = ProductAppCatalog.load(this)
        page = savedInstanceState?.getInt("page") ?: 0
        build()
        if (!settings.setupComplete) { firstRun() }
    }

    override fun onResume() { super.onResume(); main.post(refresh) }
    override fun onPause() { main.removeCallbacks(refresh); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("page", page); super.onSaveInstanceState(outState) }

    private fun build() {
        window.statusBarColor = canvasColor
        window.navigationBarColor = canvasColor
        window.decorView.systemUiVisibility = if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        primary = null
        roleSwitch = null
        val root = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)); setBackgroundColor(canvasColor) }
        val header = row()
        header.addView(text("Dashcast", 30, true), LinearLayout.LayoutParams(0, dp(58), 1f))
        header.addView(text("仪表导航  /  ${BuildConfig.VERSION_NAME}", 16).apply { gravity = Gravity.CENTER_VERTICAL })
        root.addView(header)
        val tabs = row()
        listOf("投屏首页", "应用中心", "投屏设置", "连接诊断").forEachIndexed { index, label ->
            tabs.addView(button(label, index == page) { page = index; build() },
                LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginEnd = if (index == 3) 0 else dp(12) })
        }
        root.addView(tabs)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        body = column().apply { setPadding(0, dp(16), 0, dp(8)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        status = text("", 24, true)
        connection = text("", 17)
        detail = text("", 17)
        when (page) { 0 -> home(); 1 -> applications(); 2 -> preferences(); else -> diagnostics() }
        root.addView(text("原生仪表信息优先  ·  USB 有线传输  ·  无视频授权", 14).apply { setTextColor(muted) })
        setContentView(root)
        updateStatus()
    }

    private fun home() {
        card {
            addView(text("同一个应用，两端同状态", 30, true))
            addView(text("普通应用由副屏托管；原图标进入本应用的可操作镜像，切走不停止仪表。原车定制地图保留原生双入口。", 18))
            addView(status)
            addView(connection)
            addView(detail)
        }
        val selected = ProductAppCatalog.select(apps, settings.target)
        card {
            addView(text("当前应用", 16))
            addView(text(selected?.label ?: "尚未选择导航应用", 27, true))
            addView(text(selected?.capability?.summary ?: "请在应用中心查看当前已安装应用的独立入口能力", 17))
            addView(text(if (selected?.navigation == true) "导航专注 · 同一账号 / 独立任务 · ${if (settings.underlay) "顶部状态 / 中央地图 / 底部数据" else "大中心窗 · 有效区15:8"}"
                else "请在应用中心选择；不同应用的副屏兼容性不同", 17))
            addView(button("更换应用") { page = 1; build() })
        }
        val actions = row()
        primary = button("开始应用投屏", true) { selected?.let { start(it) } ?: run { page = 1; build() } }
        actions.addView(primary, LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginEnd = dp(12) })
        actions.addView(button("停止投屏") { control.stop(); updateStatus() }, LinearLayout.LayoutParams(0, dp(72), 1f))
        body.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        body.addView(button("打开当前操作画面") { MirrorBridge.open(this) })
        card {
            addView(text("仪表 USB 模式", 20, true))
            val switchRow = row()
            switchRow.addView(text("投屏 NCM / U盘 Host，切换无需拔线；结果以仪表提示为准", 16),
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
            roleSwitch = Switch(this@ProductActivity).apply {
                showText = false
                isChecked = control.ncmObserved()
                thumbDrawable = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.WHITE); setSize(dp(26), dp(26))
                }
                trackDrawable = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat(); setSize(dp(56), dp(28))
                    setColor(trackColor(control.ncmObserved()))
                }
                switchMinWidth = dp(56)
                minWidth = dp(72)
                minHeight = dp(48)
                splitTrack = false
                contentDescription = "仪表 USB 模式，打开为 NCM，关闭请求 U盘 Host"
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setOnCheckedChangeListener { _, checked ->
                    if (!reflectingRole) {
                        trackDrawable?.setTint(trackColor(checked))
                        control.switchRole(if (checked) InstrumentUsbCommand.NCM else InstrumentUsbCommand.HOST)
                        updateStatus()
                    }
                }
            }
            switchRow.addView(roleSwitch, LinearLayout.LayoutParams(-2, -2))
            addView(switchRow)
        }
        body.addView(button("仪表地图 · 开 / 关") { control.toggleMap(); updateStatus() })
        body.addView(button("按屏幕名接入") {
            AlertDialog.Builder(this).setTitle("接入应用自带的投屏页面")
                .setMessage("开始后，在应用的投屏设置填写屏幕名：${NamedDisplayContract.NAME}。此屏幕仅在本轮会话存在；只接入一个应用，换应用前先停止。原地图的隐藏按钮不会因此出现。")
                .setPositiveButton("开始接入") { _, _ -> control.namedDisplay(); updateStatus() }
                .setNeutralButton("复制屏幕名") { _, _ ->
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("仪表屏幕名", NamedDisplayContract.NAME))
                }.setNegativeButton("取消", null).show()
        })
        body.addView(text("切换模式无需手动拔线：应用先停止投屏并释放连接。驾驶中仅使用导航，不观看娱乐视频；勿在行车中操作应用选择或设置。", 16))
    }

    private fun applications() {
        body.addView(text("选择独立投屏应用", 26, true))
        body.addView(text("不限制应用类别；普通应用采用同实例托管与主屏镜像，也支持单任务入口。受保护视频、安全窗口及应用自身的多屏限制仍由系统决定。", 16))
        body.addView(button("刷新应用能力") { apps = ProductAppCatalog.load(this); build() })
        val search = EditText(this).apply {
            hint = "搜索应用名称或包名"
            setTextColor(textColor); setHintTextColor(muted); textSize = 20f
            isSingleLine = true
            contentDescription = "搜索投屏应用"
        }
        body.addView(search, LinearLayout.LayoutParams(-1, dp(64)))
        val list = column()
        body.addView(list)
        fun show(query: String) {
            list.removeAllViews()
            val favorites = settings.favorites()
            val found = apps.filter { it.label.contains(query, true) || it.component.contains(query, true) }
                .sortedByDescending { it.component in favorites }
            if (found.isEmpty()) { list.addView(text("没有找到可启动的应用", 20)); return }
            found.forEach { app ->
                val container = column().apply { setPadding(dp(16), dp(8), dp(16), dp(8)); background = shape(surface) }
                val item = row()
                val info = column()
                info.addView(text(app.label, 22, true))
                info.addView(text(if (app.navigation) "导航应用 · 推荐类别" else "其他应用 · 副屏兼容性由应用决定", 14))
                info.addView(text(app.capability.summary, 14))
                item.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) })
                item.addView(button(if (app.component in favorites) "已收藏" else "收藏") {
                    settings.toggleFavorite(app.component); show(search.text.toString())
                }, LinearLayout.LayoutParams(dp(100), dp(64)).apply { marginEnd = dp(12) })
                item.addView(button(if (!app.capability.allowed) "原因" else if (settings.target == app.component) "已选择" else "选择", true) {
                    if (!app.capability.allowed) {
                        control.recordAppCapability(app)
                        AlertDialog.Builder(this).setTitle(app.label).setMessage(app.capability.summary +
                            "\n\n这不是仪表或USB报错。导出日志包含实际版本、入口和具体拒绝条件。")
                            .setPositiveButton("知道了", null).show()
                    } else {
                        if (settings.target != app.component) { control.changingTarget() }
                        settings.target = app.component; page = 0; build()
                    }
                }, LinearLayout.LayoutParams(dp(100), dp(64)))
                container.addView(item)
                list.addView(container, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { show(s.toString()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        show("")
    }

    private fun preferences() {
        card {
            addView(text("画面与外观", 26, true))
            addView(toggle("导航仪表布局：顶部状态、中央地图、底部数据", settings.underlay) { settings.underlay = it })
            addView(text("开启：全屏左右各外扩32像素、上下各外扩8像素，保留原弧线圆角和原生背景融合；续航底板与原生信息位置不动，仍显示在视频上方。两侧装饰圈隐藏。关闭：沿用原小窗范围和比例。需配套RWVE仪表 App；普通车模与静态路面让位，真实告警保留。", 16))
            addView(toggle("仪表中心裁剪填充（可能裁掉边缘提示）", settings.crop) { settings.crop = it })
            addView(text("普通应用按仪表承载区比例托管（密度沿用主屏），视频铺满设计显示区域；操作镜像完整显示应用画面（超宽内容上下留边）。裁剪开关在比例一致时无实际裁切；关闭可保留完整应用画面。设置在下次开始生效。", 16))
            addView(toggle("日间浅色界面", settings.light) { settings.light = it; build() })
            addView(text("上面的开关只改变 APK 界面。仪表融合底色自动跟随原厂深浅模式；地图配色仍由导航应用自己的昼夜设置决定。", 16))
        }
        card {
            addView(text("原图标与双端操作", 23, true))
            addView(text("普通应用：原图标进入当前同实例镜像；触摸和返回操作同步到仪表。回主页或切其他应用不停止投屏。停止后任务保留回车机，原图标恢复原应用。", 17))
            addView(text("无障碍只处理当前投屏应用的窗口事件，以及定制地图两个仪表按钮的控件ID。不读取文字、密码。目标车地图无此按钮时，使用首页“仪表地图”。不开启时原图标仍有宿主定向检查兜底。", 17))
            addView(button("设置原图标路由（无障碍）") {
                SafeSettingsNavigator.open(this@ProductActivity)
            })
        }
        card {
            addView(text("使用与边界", 23, true))
            addView(text("持续导航没有累计时长上限，直到主动停止、断开连接或出现异常；不会定时结束，也不会暗中循环重连。", 17))
            addView(text("软件不读取或伪造车速，不自动判定车辆在行驶。不能以保留当前前景信息代替全部告警/硬件合成的实车确认；本候选版本不宣称道路安全认证。", 17))
            addView(button("查看首次连接说明") { firstRun() })
        }
    }

    private fun diagnostics() {
        card {
            addView(text("连接与恢复", 26, true)); addView(status); addView(connection); addView(detail)
            addView(button("连接 / 授权 ADB", true) { control.connect(); updateStatus() })
            addView(button("导出日志到下载目录") { control.exportLogs(); updateStatus() })
            addView(text("ADB 与 USB 是系统访问许可，不是视频付费授权。USB 调试弹窗核对本应用指纹后允许；覆盖安装保留原有 ADB 身份。", 17))
            addView(button("查看当前诊断详情") {
                AlertDialog.Builder(this@ProductActivity).setTitle("会话详情")
                    .setMessage(control.diagnosticText().takeLast(20000)).setPositiveButton("关闭", null).show()
            })
            addView(button("高级工程工具") {
                AlertDialog.Builder(this@ProductActivity).setTitle("高级工程工具")
                    .setMessage("这里保留历史短时探针与工程回归入口，不是日常投屏流程。运行中的会话请先停止。")
                    .setPositiveButton("打开") { _, _ -> startActivity(Intent(this@ProductActivity, MainActivity::class.java)) }
                    .setNegativeButton("取消", null).show()
            })
        }
    }

    private fun start(app: ProductApp) {
        if (!settings.setupComplete) { firstRun(); return }
        val begin = { control.start(app.target()); updateStatus() }
        if (app.navigation) { begin() } else {
            AlertDialog.Builder(this).setTitle("投屏其他应用")
                .setMessage("驾驶中仅使用导航；不要观看娱乐视频或分心操作。投屏期间此应用由副屏托管，主屏原图标进入同实例操作画面。停止将任务保留回车机，不复制账号或强停应用。")
                .setPositiveButton("开始") { _, _ -> begin() }.setNegativeButton("取消", null).show()
        }
    }

    private fun firstRun() {
        AlertDialog.Builder(this).setTitle("首次连接 Dashcast")
            .setMessage("1. 本版需升级配套 RWVE 仪表 App；不改 MCU 和参考 OS。\n2. USB 数据线可保持连接切模式。\n3. 允许 ADB 调试及 USB 访问；系统有入口时可开启无障碍图标路由，无入口不影响宿主路由。\n\n普通应用投屏后点原图标进入同实例操作画面；定制地图仍开原生主界面。停止保留业务任务并恢复原厂仪表背景。驾驶时不要操作设置或观看视频；原生信息异常立即停止。本版本不宣称道路安全认证。")
            .setPositiveButton("已安装配套仪表包，开始设置") { _, _ -> settings.setupComplete = true; control.connect(); updateStatus() }
            .setNegativeButton("稍后", null).show()
    }

    private fun updateStatus() {
        if (!::status.isInitialized) { return }
        val state = ProjectionService.state()
        status.text = when {
            control.busy -> "正在准备连接 / 处理切换"
            else -> when (state) {
                ProjectionSessionController.State.STARTING -> "正在启动独立投屏"
                ProjectionSessionController.State.STREAMING -> "投屏运行中"
                ProjectionSessionController.State.STOPPING -> "正在恢复背景与释放连接"
                ProjectionSessionController.State.QUARANTINED -> "需要恢复 · 未确认的清理已隔离"
                ProjectionSessionController.State.FAILED -> "投屏未完成 · 可查看诊断详情"
                ProjectionSessionController.State.DISCONNECTED -> "连接已断开 · 重新接线后可恢复，无需重启"
                ProjectionSessionController.State.RECOVERY_REQUIRED -> "等待仪表恢复确认 · 点击开始安全检查"
                ProjectionSessionController.State.STOPPED -> "投屏已停止 · 连接已释放"
                else -> "准备就绪 · 选择应用开始"
            }
        }
        connection.text = "${if (control.adbReady) "ADB 已连接" else "ADB 待连接"}  ·  ${control.usbSummary()}"
        detail.text = if (!control.busy && (state == ProjectionSessionController.State.FAILED ||
            state == ProjectionSessionController.State.QUARANTINED || state == ProjectionSessionController.State.DISCONNECTED ||
            state == ProjectionSessionController.State.RECOVERY_REQUIRED)) {
            ProductErrors.explain(java.io.IOException(ProjectionService.status()))
        } else { control.message + if (MirrorBridge.current() != null) "\n" + MirrorBridge.message else "" }
        primary?.isEnabled = !control.busy && !ProjectionService.blocksOperations() &&
            ProductAppCatalog.select(apps, settings.target)?.capability?.allowed == true
        reflectingRole = true
        try {
            // Keep a pending user's choice stable while role switching is in progress.
            // Only an observed NCM device confirms the on state after completion.
            if (!control.busy) { roleSwitch?.isChecked = control.ncmObserved() }
            roleSwitch?.trackDrawable?.setTint(trackColor(roleSwitch?.isChecked == true))
            roleSwitch?.isEnabled = !control.busy && !ProjectionService.blocksOperations()
        } finally { reflectingRole = false }
    }

    private fun trackColor(on: Boolean): Int =
        if (on) accent else Color.parseColor(if (light) "#C6D2E4" else "#2C3E57")

    private fun card(content: LinearLayout.() -> Unit) {
        val view = column().apply {
            setPadding(dp(22), dp(16), dp(22), dp(18)); background = shape(surface); content()
        }
        body.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Int, strong: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(if (strong) textColor else muted)
        if (strong) { setTypeface(typeface, Typeface.BOLD) }
        setPadding(0, dp(5), 0, dp(7))
    }
    private fun button(label: String, filled: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 18f; isAllCaps = false; minHeight = dp(56)
        // Replacing Material's inset background also removes its visual spacing. Margins
        // belong outside the drawable; padding only enlarges the joined touch surface.
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) }
        setTextColor(if (filled) Color.WHITE else textColor)
        background = shape(if (filled) accent else if (light) Color.parseColor("#DEE8F7") else Color.parseColor("#263953"))
        setPadding(dp(18), dp(10), dp(18), dp(10))
        setOnClickListener { action() }
    }
    private fun toggle(label: String, checked: Boolean, changed: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label; textSize = 20f; setTextColor(textColor); minHeight = dp(62); isChecked = checked
        setOnCheckedChangeListener { _, selected -> changed(selected) }
    }
    private fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
}
