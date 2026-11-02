package com.byd.dashcast.phase1

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
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
    private lateinit var embedded: EmbeddedCastView
    private var consumerResumed = false
    private var appBack: Button? = null
    private var fullMode: Button? = null
    private var smallMode: Button? = null
    private var modeNote: TextView? = null
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
        embedded = EmbeddedCastView(this)
        apps = ProductAppCatalog.load(this)
        page = savedInstanceState?.getInt("page")?.coerceIn(0, 2) ?: intent.getIntExtra("page", 0).coerceIn(0, 2)
        query = savedInstanceState?.getString("query").orEmpty()
        favoritesOnly = savedInstanceState?.getBoolean("favorites") ?: false
        advancedPicture = savedInstanceState?.getBoolean("advanced_picture") ?: false
        build()
        if (!settings.setupComplete && savedInstanceState == null) { firstRun() }
        control.restoreSavedConnections()
    }

    override fun onResume() {
        super.onResume()
        consumerResumed = true
        embedded.setConsumerVisible(page == 0)
        main.removeCallbacks(refresh)
        main.post(refresh)
    }
    override fun onPause() {
        consumerResumed = false; embedded.setConsumerVisible(false)
        main.removeCallbacks(refresh); super.onPause()
    }
    override fun onDestroy() { embedded.setConsumerVisible(false); super.onDestroy() }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent); setIntent(intent)
        val next = intent.getIntExtra("page", 0).coerceIn(0, 2)
        if (page != next) { navigate(next) } else { updateStatus() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", page)
        outState.putString("query", query)
        outState.putBoolean("favorites", favoritesOnly)
        outState.putBoolean("advanced_picture", advancedPicture)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Vehicle runs Android 10 legacy back dispatch")
    override fun onBackPressed() {
        if (page != 0) { navigate(0) } else if (!embedded.applicationBack()) { super.onBackPressed() }
    }
    private fun navigate(next: Int) { if (page != next) { page = next; build() } }

    private fun build() {
        val oldY = if (renderedPage == page) scroll?.scrollY ?: 0 else 0
        (currentFocus as? EditText)?.let {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
        embedded.setConsumerVisible(false)
        (embedded.parent as? ViewGroup)?.removeView(embedded)
        headline = null; connection = null; detail = null; sourceLabel = null
        primary = null; chooseApp = null; appBack = null; export = null
        fullMode = null; smallMode = null; modeNote = null; scroll = null
        roleSwitch = null; roleState = null
        accessSwitch = null; accessState = null; copyScreenName = null
        window.statusBarColor = canvas
        window.navigationBarColor = canvas
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.decorView.systemUiVisibility = if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        val root = column().apply {
            setBackgroundColor(canvas); setPadding(dp(18), dp(10), dp(18), dp(10))
            isFocusableInTouchMode = true; requestFocus()
        }
        val header = row()
        header.addView(text("Dashcast", 23, true).apply { minHeight = dp(52) }, LinearLayout.LayoutParams(0, -2, 1f))
        val nav = row()
        listOf("投屏", "应用", "设置").forEachIndexed { index, label ->
            nav.addView(button(label, index == page) { navigate(index) }.apply {
                id = intArrayOf(R.id.product_tab_home, R.id.product_tab_apps,
                    R.id.product_tab_settings, R.id.product_tab_help)[index]
                isSelected = index == page
                contentDescription = "$label${if (index == page) "，当前页面" else ""}"
            }, LinearLayout.LayoutParams(0, navigationHeight(52), 1f).apply { marginEnd = if (index == 2) 0 else dp(12) })
        }
        if (wide) { header.addView(nav, LinearLayout.LayoutParams(dp(340), -2)) }
        connection = text("", 15).apply { gravity = Gravity.END; maxLines = 2 }
        header.addView(connection, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        root.addView(header)
        if (!wide) { root.addView(nav, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }) }
        if (page == 0) { home(root) }
        else {
            scroll = ScrollView(this).apply { isFillViewport = true; id = R.id.product_scroll }
            body = column().apply { setPadding(0, dp(2), 0, dp(14)) }
            scroll!!.addView(body)
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            if (page == 1) { applications() } else { preferences() }
        }
        renderedPage = page
        setContentView(root)
        scroll?.post { scroll?.scrollTo(0, oldY) }
        updateStatus()
        embedded.setConsumerVisible(consumerResumed && page == 0)
    }

    private fun home(root: LinearLayout) {
        sourceLabel = text("", 22, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        headline = text("", 17).apply { id = R.id.product_status; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val heading = CastHeading(this, sourceLabel!!, headline!!)
        detail = text("", 15).apply { id = R.id.product_feedback; maxLines = 2 }
        primary = button("开始投屏", true) { primaryAction() }.apply { id = R.id.product_primary }
        chooseApp = button("更换应用") { navigate(1) }.apply { id = R.id.product_choose_app }
        appBack = button("应用返回") { embedded.applicationBack() }.apply { id = R.id.product_app_back }
        val line = row().apply { setPadding(dp(8), 0, dp(8), 0) }
        line.addView(text("仪表连接", 16), LinearLayout.LayoutParams(0, -2, 1f))
        roleSwitch = makeSwitch(control.connectionState().observed).apply {
            id = R.id.product_mode_switch
            contentDescription = "仪表投屏连接，开启用于投屏，关闭请求恢复U盘"
            setOnCheckedChangeListener { _, on ->
                if (!reflectingRole) {
                    if (!settings.setupComplete) { firstRun() }
                    else {
                        settings.instrumentConnection = on
                        control.switchRole(if (on) InstrumentUsbCommand.NCM else InstrumentUsbCommand.HOST)
                    }
                    updateStatus()
                }
            }
        }
        line.addView(roleSwitch, LinearLayout.LayoutParams(dp(76), dp(52)))
        fullMode = button("全屏") { selectMode(true) }.apply { id = R.id.product_mode_full }
        smallMode = button("小窗") { selectMode(false) }.apply { id = R.id.product_mode_small }
        modeNote = text("", 13).apply { id = R.id.product_mode_note; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val controls = CastControls(this, listOf(primary!!, chooseApp!!, appBack!!), line, modeNote!!,
            listOf(fullMode!!, smallMode!!))
        embedded.palette(surface, ink)
        embedded.refresh(settings.underlay)
        root.addView(CastWorkspaceLayout(this, heading, embedded, controls, detail!!, wide),
            LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun selectMode(full: Boolean) {
        settings.underlay = full
        if (ExternalDisplayAccess.enabled()) { ExternalDisplayAccess.modeChanged(full) }
        updateStatus()
    }

    private fun primaryAction() {
        val app = ProductAppCatalog.select(apps, settings.target)
        when (presentation(app).action) {
            ProductPresentation.Action.STOP -> control.stop()
            ProductPresentation.Action.CHOOSE -> navigate(1)
            ProductPresentation.Action.SETUP -> firstRun()
            ProductPresentation.Action.START -> app?.let { control.startApp(it) }
            ProductPresentation.Action.NONE -> Unit
        }
        updateStatus()
    }

    private fun applications() {
        body.addView(text("选择投屏应用", 27, true))
        body.addView(text("一次投屏一个应用。更换时会先结束当前投屏，原应用的数据和任务会保留。", 16))
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
        val current = MirrorWorkspace.current()
        val sameTask = (current?.target?.component() == app.component &&
            current.stage == MirrorWorkspace.Stage.RUNNING && MirrorBridge.current()?.endpoint != null) ||
            (app.capability.nativeSecondary && control.projecting(app.target()))
        val selection = when {
            !app.capability.allowed -> "查看说明"
            sameTask -> "返回投屏"
            ProjectionService.active() -> "更换并投屏"
            else -> "选择并开始"
        }
        actions.addView(button(selection, app.capability.allowed) {
            if (!app.capability.allowed) {
                control.recordAppCapability(app)
                AlertDialog.Builder(this@ProductActivity).setTitle(app.label)
                    .setMessage(ProductUiModel.appIssue(app.capability)).setPositiveButton("知道了", null).show()
            } else if (presentation(app).canChooseApp) {
                settings.target = app.component; navigate(0)
                if (sameTask) { updateStatus() }
                else if (!settings.setupComplete) { firstRun() }
                else { control.startApp(app) }
            } else { Toast.makeText(this@ProductActivity, "请等当前操作结束后再更换应用。", Toast.LENGTH_SHORT).show() }
        }, LinearLayout.LayoutParams(if (wide) dp(100) else 0, -2, if (wide) 0f else 1f))
        content.addView(actions, LinearLayout.LayoutParams(if (wide) -2 else -1, -2).apply { if (!wide) topMargin = dp(8) })
        addView(content)
    }

    private fun preferences() {
        body.addView(text("投屏设置", 27, true))
        externalAccessCard()
        addCard {
            addView(text("其他接入的画面", 21, true))
            addView(button(if (advancedPicture) "收起其他接入选项" else "其他接入的画面选项") {
                advancedPicture = !advancedPicture; build()
            }.apply { id = R.id.product_picture_more })
            if (advancedPicture) {
                choices(this, "画面比例", listOf("完整显示", "填满区域"), if (settings.crop) 1 else 0) {
                    settings.crop = it == 1; build()
                }
                addView(text("用于定制地图的仪表界面、地图内按钮、按名称等接入方式。普通应用从本应用开始的操作窗口会自动适配，不受此项影响。完整显示保留内容；填满区域会裁去多出的边缘。", 15))
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
        addCard {
            addView(text("帮助与反馈", 21, true))
            export = button("导出日志到下载目录") { exportLogs() }.also {
                it.id = R.id.product_export_logs; addView(it)
            }
            addView(button("连接与首次使用") { connectionHelp() })
            addView(button("技术详情") { info("技术详情", control.diagnosticText().takeLast(20000)) })
            addView(text("本应用 ${BuildConfig.VERSION_NAME}。配套仪表包为1.19系列（推荐1.19.3）；只更新本应用时无需重刷仪表。", 15))
        }
    }

    private fun externalAccessCard() {
        addCard {
            val line = row().apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(text("应用内投屏接入",21,true),LinearLayout.LayoutParams(0,-2,1f))
            accessSwitch = makeSwitch(ExternalDisplayAccess.enabled()).apply {
                id = R.id.product_external_access
                textOn = ""; textOff = ""; showText = false
                isChecked = ExternalDisplayAccess.enabled()
                contentDescription = "应用内投屏接入开关"
                setOnCheckedChangeListener { _, checked ->
                    if (!reflectingAccess) {
                        if (!settings.setupComplete) { firstRun(); build() }
                        else {
                            settings.externalAccess = checked
                            ExternalDisplayService.setEnabled(this@ProductActivity,checked)
                        }
                    }
                }
            }.also { line.addView(it,LinearLayout.LayoutParams(dp(76),dp(52)).apply { marginStart=dp(16) }) }
            addView(line)
            addView(text("开启后，可在支持投屏的应用中填写屏幕名发送画面。开关不改变仪表，关闭也不打断当前投屏。",16))
            addView(text("开启后管理定制地图的投屏入口，切到其他应用后隐藏。与从原图标继续操作分别管理。",15))
            accessState = text(ExternalDisplayAccess.message,15).also { addView(it) }
            copyScreenName = button("复制屏幕名") {
                if (!ExternalDisplayAccess.ready()) {
                    Toast.makeText(this@ProductActivity,"接收屏还在准备，请稍候再复制。",Toast.LENGTH_SHORT).show()
                    return@button
                }
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("仪表屏幕名",NamedDisplayContract.NAME))
                Toast.makeText(this@ProductActivity,"屏幕名已复制",Toast.LENGTH_SHORT).show()
            }.also {
                it.visibility=if (ExternalDisplayAccess.enabled()) View.VISIBLE else View.GONE
                it.isEnabled=ExternalDisplayAccess.ready()
                addView(it)
            }
        }
    }

    /** The result used to land only in the cast page's status line, so pressing this on the settings page
     * looked like nothing happened. Say that it started, then show where the file went or why it failed. */
    private fun exportLogs() {
        val started = control.exportLogs { result ->
            if (!isFinishing && !isDestroyed) {
                AlertDialog.Builder(this).setTitle("导出日志").setMessage(result).setPositiveButton("知道了", null).show()
            }
            updateStatus()
        }
        Toast.makeText(this, if (started) "正在导出日志，完成后会提示保存位置。" else control.message, Toast.LENGTH_SHORT).show()
        updateStatus()
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
        export?.text = if (control.operation == ProductOperation.EXPORT) "正在导出日志…" else "导出日志到下载目录"
        val active = state == ProjectionSessionController.State.STARTING || state == ProjectionSessionController.State.STREAMING
        sourceLabel?.text = if (active) control.activeSourceLabel() ?: "正在准备应用"
            else ProductAppCatalog.select(apps, settings.target)?.label ?: "还未选择应用"
        val workspace = MirrorWorkspace.current()
        if (workspace?.stage == MirrorWorkspace.Stage.STOPPING && !control.busy &&
            ProjectionService.readyForNewService() && MirrorBridge.current() == null) { MirrorWorkspace.stopped() }
        if (page == 0) { embedded.refresh(settings.underlay) }
        appBack?.isEnabled = MirrorBridge.current()?.endpoint != null
        listOf(fullMode, smallMode).forEachIndexed { index, value ->
            val selected = settings.underlay == (index == 0)
            value?.isSelected = selected
            value?.isEnabled = !control.busy
            // Refresh runs every 500 ms; only swap the background on a real change so a press ripple survives.
            if (value != null && value.tag != selected) {
                value.tag = selected
                value.setTextColor(if (selected) Color.WHITE else ink)
                value.background = pressable(if (selected) accent else surface, if (selected) accent else border, selected)
            }
        }
        modeNote?.text = "${if (active || control.busy) "下次使用" else "仪表画面"}${if (settings.underlay) "全屏" else "小窗"}"
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
            accessSwitch?.trackDrawable?.setTint(if (accessSwitch?.isChecked == true) accent else border)
            accessState?.text = ExternalDisplayAccess.message
            copyScreenName?.visibility = if (ExternalDisplayAccess.enabled()) View.VISIBLE else View.GONE
            copyScreenName?.isEnabled = ExternalDisplayAccess.ready()
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
    private fun button(label: String, filled: Boolean = false, action: () -> Unit) = ProductButton(this).apply {
        text = label; textSize = 18f; isAllCaps = false; minHeight = dp(54); minimumWidth = 0
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7); bottomMargin = dp(7) }
        setTextColor(if (filled) Color.WHITE else ink)
        background = pressable(if (filled) accent else surface, if (filled) accent else border, filled)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setOnClickListener { action() }
    }
    /** The plain shape looked identical when pressed; a ripple over it shows the tap registered. */
    private fun pressable(fill: Int, stroke: Int, onAccent: Boolean) = RippleDrawable(
        ColorStateList.valueOf(if (onAccent || !light) 0x55FFFFFF else 0x33162941), shape(fill, stroke), null)
    private fun shape(value: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(value); cornerRadius = dp(12).toFloat(); stroke?.let { setStroke(dp(1), it) }
    }
    private fun color(value: String) = Color.parseColor(value)
    private fun navigationHeight(base: Int) = dp(base + ((resources.configuration.fontScale - 1f).coerceAtLeast(0f) * 32).toInt())
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
}

/** Custom flat backgrounds look the same disabled as enabled; dim instead so it reads as unavailable. */
private class ProductButton(context: Context) : Button(context) {
    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else .5f }
}
