package com.byd.dashcast.phase1

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
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
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Product UI. Transport, recovery and application ownership remain in their existing owners. */
class ProductActivity : Activity() {
    private lateinit var settings: ProductSettings
    private lateinit var control: ProductController
    private lateinit var body: LinearLayout
    private var scroll: ScrollView? = null
    private var headline: TextView? = null
    private var connection: TextView? = null
    private var detail: TextView? = null
    private var sourceLabel: TextView? = null
    private var primary: Button? = null
    private var chooseApp: Button? = null
    private var mirror: Button? = null
    private var export: Button? = null
    private var roleSwitch: Switch? = null
    private var roleState: TextView? = null
    private var accessSwitch: Switch? = null
    private var accessState: TextView? = null
    private var copyScreenName: Button? = null
    private var reflectingAccess = false
    private var reflectingRole = false
    private var apps: List<ProductApp> = emptyList()
    private var page = 0
    private var renderedPage = -1
    private var query = ""
    private var favoritesOnly = false
    private var advancedPicture = false
    private var advancedHelp = false
    private val main = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { updateStatus(); main.postDelayed(this, 500) }
    }
    private val light get() = settings.light
    private val ink get() = color(if (light) "#162941" else "#F1F5FC")
    private val muted get() = color(if (light) "#55677D" else "#ADBCD0")
    private val canvas get() = color(if (light) "#EFF3F8" else "#0D1624")
    private val surface get() = color(if (light) "#FFFFFF" else "#192638")
    private val border get() = color(if (light) "#D4DEEB" else "#35465D")
    private val accent get() = color(if (light) "#1764CF" else "#3585EF")
    private val wide get() = resources.configuration.screenWidthDp >= 900

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = ProductSettings(this)
        control = ProductController.get(this)
        apps = ProductAppCatalog.load(this)
        page = savedInstanceState?.getInt("page")?.coerceIn(0, 3) ?: 0
        query = savedInstanceState?.getString("query").orEmpty()
        favoritesOnly = savedInstanceState?.getBoolean("favorites") ?: false
        advancedPicture = savedInstanceState?.getBoolean("advanced_picture") ?: false
        advancedHelp = savedInstanceState?.getBoolean("advanced_help") ?: false
        build()
        if (!settings.setupComplete && savedInstanceState == null) { firstRun() }
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(refresh)
        main.post(refresh)
    }
    override fun onPause() { main.removeCallbacks(refresh); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", page)
        outState.putString("query", query)
        outState.putBoolean("favorites", favoritesOnly)
        outState.putBoolean("advanced_picture", advancedPicture)
        outState.putBoolean("advanced_help", advancedHelp)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Vehicle runs Android 10 legacy back dispatch")
    override fun onBackPressed() {
        if (page != 0) { navigate(0) } else { super.onBackPressed() }
    }
    private fun navigate(next: Int) { page = next; build() }

    private fun build() {
        val oldY = if (renderedPage == page) scroll?.scrollY ?: 0 else 0
        headline = null; connection = null; detail = null; sourceLabel = null
        primary = null; chooseApp = null; mirror = null; export = null
        roleSwitch = null; roleState = null
        accessSwitch = null; accessState = null; copyScreenName = null
        window.statusBarColor = canvas
        window.navigationBarColor = canvas
        window.decorView.systemUiVisibility = if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        val root = column().apply { setBackgroundColor(canvas); setPadding(dp(18), dp(10), dp(18), dp(10)) }
        val header = row()
        header.addView(text("Dashcast", 27, true).apply { minHeight = dp(52) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text("仪表投屏", 16))
        root.addView(header)
        val shell = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val nav = LinearLayout(this).apply { orientation = if (wide) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        listOf("投屏", "应用", "设置", "帮助").forEachIndexed { index, label ->
            nav.addView(button(label, index == page) { navigate(index) }.apply {
                id = intArrayOf(R.id.product_tab_home, R.id.product_tab_apps,
                    R.id.product_tab_settings, R.id.product_tab_help)[index]
                isSelected = index == page
                contentDescription = "$label${if (index == page) "，当前页面" else ""}"
            }, if (wide) LinearLayout.LayoutParams(-1, navigationHeight(58)).apply { bottomMargin = dp(12) }
                else LinearLayout.LayoutParams(0, navigationHeight(52), 1f).apply { marginEnd = if (index == 3) 0 else dp(8) })
        }
        shell.addView(nav, if (wide) LinearLayout.LayoutParams(dp(144), -1).apply { marginEnd = dp(20) }
            else LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        scroll = ScrollView(this).apply { isFillViewport = true; id = R.id.product_scroll }
        body = column().apply { setPadding(0, dp(2), 0, dp(14)) }
        scroll!!.addView(body)
        shell.addView(scroll, if (wide) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(shell, LinearLayout.LayoutParams(-1, 0, 1f))
        when (page) { 0 -> home(); 1 -> applications(); 2 -> preferences(); else -> help() }
        if (page == 0) { homeActions(root) }
        renderedPage = page
        setContentView(root)
        scroll!!.post { scroll?.scrollTo(0, oldY) }
        updateStatus()
    }

    private fun home() {
        addCard {
            headline = text("", 27, true).also { it.id = R.id.product_status; addView(it) }
            connection = text("", 16).also { addView(it) }
            detail = text("", 16).also { it.id = R.id.product_feedback; addView(it) }
        }
        val panels = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val selected = ProductAppCatalog.select(apps, settings.target)
        val appPanel = card {
            addView(text("投屏应用", 16))
            sourceLabel = text(selected?.label ?: "还未选择应用", 27, true).also { addView(it) }
            addView(text(selected?.let { ProductUiModel.appSummary(it.capability) }
                ?: "选择一个应用，画面就能显示到仪表。", 16))
            addView(text("仪表画面：${if (settings.underlay) "全屏" else "小窗"}", 16))
            mirror = button("打开正在投屏的应用") { MirrorBridge.open(this@ProductActivity) }.also {
                it.id = R.id.product_open_app; addView(it)
            }
        }
        panels.addView(appPanel, if (wide) LinearLayout.LayoutParams(0, -2, 1.35f).apply { marginEnd = dp(14) }
            else LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        val connectPanel = card {
            addView(text("仪表投屏连接", 21, true))
            val line = row()
            roleState = text("", 17).also { line.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
            roleSwitch = makeSwitch(control.connectionState().observed).apply {
                id = R.id.product_mode_switch
                contentDescription = "仪表投屏连接，开启用于投屏，关闭请求恢复U盘"
                setOnCheckedChangeListener { _, on ->
                    if (!reflectingRole) {
                        if (!settings.setupComplete) { firstRun() }
                        else { control.switchRole(if (on) InstrumentUsbCommand.NCM else InstrumentUsbCommand.HOST) }
                        updateStatus()
                    }
                }
            }
            line.addView(roleSwitch, LinearLayout.LayoutParams(dp(76), dp(52)))
            addView(line)
            addView(text("开启后可投屏；关闭会请求恢复仪表U盘使用，请以仪表提示为准。数据线无需拔插。", 15))
            addView(button("连接帮助") { connectionHelp() })
        }
        panels.addView(connectPanel, if (wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        body.addView(panels)
    }

    private fun homeActions(root: LinearLayout) {
        // Keep cancel/stop reachable even on short displays, with large text, or after scrolling.
        val actions = row()
        chooseApp = button("更换应用") { navigate(1) }.also { it.id = R.id.product_choose_app }
        actions.addView(chooseApp, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        primary = button("开始投屏", true) { primaryAction() }.also { it.id = R.id.product_primary }
        actions.addView(primary, LinearLayout.LayoutParams(0, -2, 1.3f))
        root.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun primaryAction() {
        val app = ProductAppCatalog.select(apps, settings.target)
        when (presentation(app).action) {
            ProductPresentation.Action.STOP -> control.stop()
            ProductPresentation.Action.CHOOSE -> navigate(1)
            ProductPresentation.Action.SETUP -> firstRun()
            ProductPresentation.Action.START -> app?.let { control.start(it.target()) }
            ProductPresentation.Action.NONE -> Unit
        }
        updateStatus()
    }

    private fun applications() {
        body.addView(text("选择投屏应用", 27, true))
        body.addView(text("一次投屏一个应用。更换时会先结束当前投屏，原应用的数据和任务会保留。", 16))
        externalAccessCard()
        val utilities = row()
        utilities.addView(button("刷新列表") { apps = ProductAppCatalog.load(this); build() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        utilities.addView(button("仪表地图") {
            if (!settings.setupComplete) { firstRun() } else { control.toggleMap(); navigate(0) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(utilities)
        val search = EditText(this).apply {
            id = R.id.product_app_search; hint = "搜索应用"; textSize = 20f
            setTextColor(ink); setHintTextColor(muted); isSingleLine = true
            setPadding(dp(16), dp(8), dp(16), dp(8)); background = shape(surface, border)
            contentDescription = "搜索投屏应用"; setText(query)
        }
        body.addView(search, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(8); bottomMargin = dp(12) })
        val filters = row()
        filters.addView(button("全部", !favoritesOnly) { favoritesOnly = false; build() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        filters.addView(button("常用", favoritesOnly) { favoritesOnly = true; build() }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(filters)
        val list = column()
        body.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        fun show() {
            list.removeAllViews()
            val favorites = settings.favorites()
            val found = apps.filter {
                (!favoritesOnly || it.component in favorites) &&
                    (it.label.contains(query, true) || it.component.contains(query, true))
            }.sortedByDescending { it.component in favorites }
            if (found.isEmpty()) {
                list.addView(text(if (favoritesOnly && query.isBlank()) "还没有常用应用，点“收藏”即可添加。"
                    else "没有找到应用，请换个名称或刷新列表。", 18))
            }
            found.forEach { app ->
                list.addView(applicationCard(app, favorites) { show() },
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty(); show()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        show()
    }

    private fun applicationCard(app: ProductApp, favorites: Set<String>, redraw: () -> Unit): LinearLayout = card {
        val content = LinearLayout(this@ProductActivity).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val info = row()
        val component = ComponentName.unflattenFromString(app.component)
        if (component != null) {
            try {
                info.addView(ImageView(this@ProductActivity).apply {
                    setImageDrawable(packageManager.getActivityIcon(component)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
            } catch (_: PackageManager.NameNotFoundException) { /* A removed app can be refreshed safely. */ }
        }
        val labels = column()
        labels.addView(text(app.label, 22, true))
        labels.addView(text(ProductUiModel.appSummary(app.capability), 15))
        info.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(info, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) }
            else LinearLayout.LayoutParams(-1, -2))
        val actions = row()
        actions.addView(button(if (app.component in favorites) "已收藏" else "收藏") {
            settings.toggleFavorite(app.component); redraw()
        }, LinearLayout.LayoutParams(if (wide) dp(90) else 0, -2, if (wide) 0f else 1f).apply { marginEnd = dp(12) })
        val selection = if (!app.capability.allowed) "查看说明" else if (settings.target == app.component) "已选择" else "选择"
        actions.addView(button(selection, app.capability.allowed) {
            if (!app.capability.allowed) {
                control.recordAppCapability(app)
                AlertDialog.Builder(this@ProductActivity).setTitle(app.label)
                    .setMessage(ProductUiModel.appIssue(app.capability)).setPositiveButton("知道了", null).show()
            } else if (presentation(app).canChooseApp) {
                if (settings.target != app.component) { control.changingTarget() }
                settings.target = app.component; navigate(0)
            } else { Toast.makeText(this@ProductActivity, "请等当前操作结束后再更换应用。", Toast.LENGTH_SHORT).show() }
        }, LinearLayout.LayoutParams(if (wide) dp(100) else 0, -2, if (wide) 0f else 1f))
        content.addView(actions, LinearLayout.LayoutParams(if (wide) -2 else -1, -2).apply { if (!wide) topMargin = dp(8) })
        addView(content)
    }

    private fun preferences() {
        body.addView(text("投屏设置", 27, true))
        body.addView(text("画面设置在下次投屏时生效。", 16))
        externalAccessCard()
        addCard {
            addView(text("仪表画面", 21, true))
            choices(this, "画面大小", listOf("全屏", "小窗"), if (settings.underlay) 0 else 1) {
                settings.underlay = it == 0; build()
            }
            addView(text("全屏扩大显示范围；小窗保留周围仪表布局。车速、挡位和续航等信息由仪表正常显示。", 16))
            addView(text(if (settings.crop) "当前按原比例填满仪表区域，边缘内容可能被裁掉。车机操作画面保持完整。"
                else "当前完整显示应用画面，比例不同时仪表周围会留边。可在更多画面选项中改为填满。", 16))
            addView(button(if (advancedPicture) "收起更多画面选项" else "更多画面选项") {
                advancedPicture = !advancedPicture; build()
            }.apply { id = R.id.product_picture_more })
            if (advancedPicture) {
                choices(this, "画面比例", listOf("完整显示", "填满区域"), if (settings.crop) 1 else 0) {
                    settings.crop = it == 1; build()
                }
                addView(text("填满区域：保持原比例并裁去多出的边缘。完整显示：保留全部内容，比例不同时会留边。只影响仪表，车机操作画面保持完整。", 15))
            }
        }
        addCard {
            addView(text("应用外观", 21, true))
            choices(this, "界面颜色", listOf("深色", "浅色"), if (settings.light) 1 else 0) {
                settings.light = it == 1; build()
            }
            addView(text("只改变本应用。仪表边缘自动跟随仪表深浅模式，地图配色在地图应用中设置。", 16))
        }
        addCard {
            addView(text("从原应用图标打开", 21, true))
            addView(text("投屏时，点击原应用图标即可继续操作；回到桌面或打开其他应用，仪表仍会显示投屏画面。", 16))
            addView(text(if (accessibilityEnabled()) "快捷打开已开启" else "可在系统无障碍设置中开启快捷打开。部分车机未提供此设置，也可继续使用。", 16))
            addView(button("前往系统设置") { SafeSettingsNavigator.open(this@ProductActivity) }.apply {
                id = R.id.product_accessibility
            })
            addView(text("此功能只响应投屏应用的打开操作和地图投屏按钮，不读取输入的文字或密码。", 15))
        }
    }

    private fun help() {
        body.addView(text("帮助与反馈", 27, true))
        addCard {
            headline = text("", 23, true).also { addView(it) }
            connection = text("", 16).also { addView(it) }
            detail = text("", 16).also { it.id = R.id.product_feedback; addView(it) }
            addView(button("重新连接车机") { control.connect(); updateStatus() })
            export = button("导出日志到下载目录", true) { control.exportLogs(); updateStatus() }.also {
                it.id = R.id.product_export_logs; addView(it)
            }
            addView(text("日志仅保存到车机下载目录，你可以自行选择是否分享。", 15))
        }
        addCard {
            addView(text("使用说明", 21, true))
            addView(button("连接与首次使用") { connectionHelp() })
            addView(button("投屏后如何操作应用") {
                info("投屏后如何操作", "点击原应用图标继续操作，回到桌面不会结束投屏。\n\n更换应用会先结束原投屏。主动停止后，应用任务保留在车机；导航是否继续以地图应用自身行为为准。\n\n部分地图使用自带的仪表画面，两屏的布局可能不同。受保护的视频或不支持投屏的应用可能无法显示。")
            })
            addView(text("投屏没有定时结束限制。请在不影响驾驶时操作应用和设置；原生仪表信息异常时及时停止投屏。", 15))
        }
        addCard {
            addView(text("关于 Dashcast", 21, true))
            addView(text("应用版本 ${BuildConfig.VERSION_NAME}\n配套仪表包为 1.14.1，已安装的无需重复更新。", 16))
            addView(button(if (advancedHelp) "收起支持选项" else "更多支持选项") { advancedHelp = !advancedHelp; build() })
            if (advancedHelp) {
                addView(button("查看技术详情") { info("技术详情", control.diagnosticText().takeLast(20000)) })
                addView(button("开发工具") {
                    if (control.busy || ProjectionService.active() || ProjectionService.blocksOperations()) {
                        info("请先结束当前操作", "投屏或恢复过程中不能打开开发工具，请等待当前操作结束。")
                    } else {
                        AlertDialog.Builder(this@ProductActivity).setTitle("开发工具")
                            .setMessage("这里保留测试和排查功能，日常投屏无需使用。")
                            .setPositiveButton("打开") { _, _ -> startActivity(Intent(this@ProductActivity, MainActivity::class.java)) }
                            .setNegativeButton("取消", null).show()
                    }
                })
            }
        }
    }

    private fun externalAccessCard() {
        addCard {
            val line = row().apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(text("应用内投屏接入",21,true),LinearLayout.LayoutParams(0,-2,1f))
            accessSwitch = Switch(this@ProductActivity).apply {
                id = R.id.product_external_access
                textOn = ""; textOff = ""; showText = false
                isChecked = ExternalDisplayAccess.enabled()
                contentDescription = "应用内投屏接入开关"
                setOnCheckedChangeListener { _, checked ->
                    if (!reflectingAccess) {
                        if (!settings.setupComplete) { firstRun(); build() }
                        else { ExternalDisplayService.setEnabled(this@ProductActivity,checked) }
                    }
                }
            }.also { line.addView(it,LinearLayout.LayoutParams(dp(64),dp(48)).apply { marginStart=dp(16) }) }
            addView(line)
            addView(text("开启后，可在支持投屏的应用中填写屏幕名发送画面。开关不改变仪表，关闭也不打断当前投屏。",16))
            addView(text("定制地图缺少原按钮时，启用应用的无障碍服务即可显示地图投屏入口。切到其他应用后自动隐藏。",15))
            accessState = text(ExternalDisplayAccess.message,15).also { addView(it) }
            copyScreenName = button("复制屏幕名") {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("仪表屏幕名",NamedDisplayContract.NAME))
                Toast.makeText(this@ProductActivity,"屏幕名已复制",Toast.LENGTH_SHORT).show()
            }.also { it.visibility=if (ExternalDisplayAccess.enabled()) View.VISIBLE else View.GONE; addView(it) }
        }
    }

    private fun firstRun() {
        AlertDialog.Builder(this).setTitle("开始前的准备")
            .setMessage("1. 安装与本应用配套的仪表包。\n2. 用数据线连接车机和仪表。\n3. 首次连接时，允许系统弹出的调试和 USB 访问请求。\n\n准备好后即可连接，之后选择应用开始投屏。")
            .setPositiveButton("已安装配套仪表包，连接") { _, _ -> settings.setupComplete = true; control.connect(); updateStatus() }
            .setNegativeButton("稍后", null).show()
    }

    private fun connectionHelp() {
        AlertDialog.Builder(this).setTitle("连接仪表")
            .setMessage("先确认已安装配套仪表包，并用数据线连接车机和仪表。\n\n首次连接时，在“允许 USB 调试”和访问仪表的系统弹窗中选择允许。已有授权会在覆盖安装后保留。\n\n开始投屏会自动准备连接，也可以在首页打开“仪表投屏连接”。关闭开关会先停止投屏，再请求恢复U盘使用，无需拔线。")
            .setPositiveButton("知道了", null)
            .setNeutralButton("首次连接设置") { _, _ -> firstRun() }.show()
    }

    private fun presentation(app: ProductApp? = ProductAppCatalog.select(apps, settings.target)) = ProductUiModel.present(
        ProjectionService.state(), control.operation, control.busy, settings.setupComplete, app != null, app?.capability?.allowed == true)

    private fun updateStatus() {
        if (!::control.isInitialized) { return }
        val state = ProjectionService.state()
        val ui = presentation()
        val usb = control.connectionState()
        headline?.text = ui.title
        connection?.text = "${if (control.adbReady) "车机已连接" else "车机待连接"} · ${usb.description}"
        val hasFailure = !control.busy && state in setOf(ProjectionSessionController.State.FAILED,
            ProjectionSessionController.State.QUARANTINED, ProjectionSessionController.State.DISCONNECTED,
            ProjectionSessionController.State.RECOVERY_REQUIRED)
        detail?.text = if (hasFailure) ProductErrors.explain(java.io.IOException(ProjectionService.status())) else control.message
        primary?.text = ui.actionLabel
        primary?.isEnabled = ui.enabled
        primary?.alpha = if (ui.enabled) 1f else .55f
        chooseApp?.isEnabled = ui.canChooseApp
        chooseApp?.visibility = if (ProductAppCatalog.select(apps, settings.target) != null) View.VISIBLE else View.GONE
        export?.isEnabled = !control.busy
        val active = state == ProjectionSessionController.State.STARTING || state == ProjectionSessionController.State.STREAMING
        sourceLabel?.text = if (active) control.activeSourceLabel() ?: "正在准备应用"
            else ProductAppCatalog.select(apps, settings.target)?.label ?: "还未选择应用"
        mirror?.visibility = if (active && MirrorBridge.current()?.endpoint != null) View.VISIBLE else View.GONE
        reflectingRole = true
        try {
            if (!control.busy) { roleSwitch?.isChecked = usb.observed }
            roleSwitch?.isEnabled = ui.modeEnabled
            roleSwitch?.trackDrawable?.setTint(if (roleSwitch?.isChecked == true) accent else border)
            roleState?.text = if (control.busy && control.operation == ProductOperation.MODE) "正在切换…"
                else if (usb.observed) usb.description else "未检测到投屏连接"
        } finally { reflectingRole = false }
        reflectingAccess = true
        try {
            accessSwitch?.isChecked = ExternalDisplayAccess.enabled()
            accessState?.text = ExternalDisplayAccess.message
            copyScreenName?.visibility = if (ExternalDisplayAccess.enabled()) View.VISIBLE else View.GONE
        } finally { reflectingAccess = false }
    }

    private fun accessibilityEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo?.serviceInfo?.let { service -> service.packageName == packageName &&
                service.name == MirrorIconService::class.java.name } == true
        }
    }

    private fun info(title: String, message: String) {
        val value = text(message, 17).apply { setPadding(dp(24), dp(16), dp(24), dp(16)); setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(value) })
            .setPositiveButton("关闭", null).show()
    }

    private fun choices(parent: LinearLayout, title: String, labels: List<String>, selected: Int, change: (Int) -> Unit) {
        parent.addView(text(title, 17))
        val line = row()
        labels.forEachIndexed { index, label ->
            line.addView(button(label, index == selected) { change(index) }.apply {
                isSelected = index == selected
                contentDescription = "$title，$label${if (index == selected) "，已选择" else ""}"
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = if (index == labels.lastIndex) 0 else dp(12) })
        }
        parent.addView(line)
    }

    private fun makeSwitch(checked: Boolean) = Switch(this).apply {
        showText = false; isChecked = checked; switchMinWidth = dp(56); minHeight = dp(48); splitTrack = false
        thumbDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setSize(dp(26), dp(26)) }
        trackDrawable = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setSize(dp(56), dp(28)); setColor(border) }
        setPadding(dp(8), dp(10), dp(8), dp(10))
    }
    private fun addCard(content: LinearLayout.() -> Unit) {
        body.addView(card(content), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }
    private fun card(content: LinearLayout.() -> Unit) = column().apply {
        setPadding(dp(20), dp(14), dp(20), dp(16)); background = shape(surface, border); content()
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Int, strong: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(if (strong) ink else muted)
        if (strong) { setTypeface(typeface, Typeface.BOLD) }
        setPadding(0, dp(4), 0, dp(6))
    }
    private fun button(label: String, filled: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 18f; isAllCaps = false; minHeight = dp(54); minimumWidth = 0
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7); bottomMargin = dp(7) }
        setTextColor(if (filled) Color.WHITE else ink)
        background = shape(if (filled) accent else surface, if (filled) accent else border)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setOnClickListener { action() }
    }
    private fun shape(value: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(value); cornerRadius = dp(12).toFloat(); stroke?.let { setStroke(dp(1), it) }
    }
    private fun color(value: String) = Color.parseColor(value)
    private fun navigationHeight(base: Int) = dp(base + ((resources.configuration.fontScale - 1f).coerceAtLeast(0f) * 32).toInt())
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
}
