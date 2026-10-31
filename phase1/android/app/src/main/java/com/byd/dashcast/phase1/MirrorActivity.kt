package com.byd.dashcast.phase1

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** The hosted task and instrument survive this optional main-screen consumer. */
class MirrorActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var texture: TextureView
    private lateinit var canvas: FrameLayout
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var retry: Button
    private lateinit var placeholder: LinearLayout
    private lateinit var control: ProductController
    private var lease = 0L
    private var resumed = false
    private var gesture = false
    private var ticket: String? = null
    private var exitAfterStop = false
    private val main = Handler(Looper.getMainLooper())
    private val watch = object : Runnable {
        override fun run() { refresh(); if (resumed) { main.postDelayed(this, 200) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        control = ProductController.get(this)
        exitAfterStop = savedInstanceState?.getBoolean("exit_after_stop") ?: false
        val light = ProductSettings(this).light
        val background = Color.parseColor(if (light) "#EFF3F8" else "#0D1624")
        val panel = Color.parseColor(if (light) "#FFFFFF" else "#192638")
        val ink = Color.parseColor(if (light) "#162941" else "#F1F5FC")
        window.statusBarColor = background
        window.navigationBarColor = background
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.decorView.systemUiVisibility = if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(background) }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(4), dp(12), dp(4)); setBackgroundColor(panel)
        }
        title = TextView(this).apply { textSize = 18f; setTextColor(ink); maxLines = 1; gravity = Gravity.CENTER_VERTICAL }
        header.addView(title, LinearLayout.LayoutParams(0, dp(52), 1f))
        action = Button(this).apply {
            id = R.id.workspace_controls; textSize = 16f; minHeight = dp(48)
            setOnClickListener { controls() }
        }
        header.addView(action, LinearLayout.LayoutParams(-2, dp(52)))
        root.addView(header)
        canvas = FrameLayout(this).apply { setBackgroundColor(background) }
        texture = TextureView(this).apply {
            id = R.id.mirror_surface; surfaceTextureListener = this@MirrorActivity
            contentDescription = "正在投屏的应用操作画面"
            setOnTouchListener { _, event -> forward(event); true }
        }
        canvas.addView(texture, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        placeholder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16)); setBackgroundColor(background)
        }
        status = TextView(this).apply {
            id = R.id.workspace_status; textSize = 20f; gravity = Gravity.CENTER; setTextColor(ink)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        placeholder.addView(status, LinearLayout.LayoutParams(-1, -2))
        retry = Button(this).apply {
            id = R.id.workspace_retry; text = "重试"; minHeight = dp(48)
            setOnClickListener { MirrorWorkspace.current()?.target?.let { control.startWorkspace(it) } }
        }
        placeholder.addView(retry, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
        canvas.addView(placeholder, FrameLayout.LayoutParams(-1, -1))
        canvas.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitWindow() }
        root.addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume(); resumed = true
        val previous = ticket
        refresh()
        if (previous != null && previous == ticket && texture.isAvailable) { attach() }
        main.removeCallbacks(watch); main.post(watch)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); exitAfterStop = false
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("exit_after_stop", exitAfterStop)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        resumed = false; gesture = false
        MirrorBridge.detach(lease); main.removeCallbacks(watch)
        super.onPause()
    }

    private fun refresh() {
        if (!::texture.isInitialized) { return }
        val request = MirrorWorkspace.current()
        val owner = MirrorBridge.current()
        val matches = owner?.endpoint != null && (request == null || request.ticket == owner.ticket)
        val ready = matches && request?.stage != MirrorWorkspace.Stage.STOPPING &&
            request?.stage != MirrorWorkspace.Stage.STOPPED && request?.stage != MirrorWorkspace.Stage.FAILED
        if (ready) {
            title.text = "${request?.target?.displayLabel() ?: applicationLabel(owner.packageName)} · 正在投屏"
            if (ticket != owner.ticket) {
                MirrorBridge.detach(lease); gesture = false; ticket = owner.ticket
                fitWindow()
                if (resumed && texture.isAvailable) { attach() }
            } else { fitWindow() }
            texture.visibility = View.VISIBLE; placeholder.visibility = View.GONE
            action.text = "投屏控制"
            return
        }
        if (ticket != null) { MirrorBridge.detach(lease); ticket = null; gesture = false }
        texture.visibility = View.INVISIBLE; placeholder.visibility = View.VISIBLE
        title.text = "${request?.target?.displayLabel() ?: "应用"} · 操作画面"
        if (request?.stage == MirrorWorkspace.Stage.STOPPING && !control.busy &&
            !ProjectionService.active() && owner == null) { MirrorWorkspace.stopped() }
        val phase = MirrorWorkspace.current()?.stage
        if (exitAfterStop && phase == MirrorWorkspace.Stage.STOPPED && resumed) { management(0); return }
        val failed = phase == MirrorWorkspace.Stage.FAILED
        retry.visibility = if (failed) View.VISIBLE else View.GONE
        retry.isEnabled = !control.busy
        action.text = if (phase == MirrorWorkspace.Stage.PREPARING || phase == MirrorWorkspace.Stage.STARTING)
            "取消启动" else "返回首页"
        status.text = when (phase) {
            MirrorWorkspace.Stage.FAILED -> request?.error ?: "暂时无法投屏，请重试或返回首页检查连接。"
            MirrorWorkspace.Stage.STOPPING -> "正在结束投屏…"
            MirrorWorkspace.Stage.STOPPED -> "投屏已结束，应用状态已保留。"
            else -> control.message.takeIf { it.isNotBlank() } ?: "正在打开应用，画面会显示在这里。"
        }
    }

    private fun fitWindow() {
        val owner = MirrorBridge.current() ?: return
        if (owner.ticket != ticket || owner.width <= 0 || owner.height <= 0 || canvas.width <= 0 || canvas.height <= 0) { return }
        val fit = MirrorGeometry.fit(owner.width, owner.height, canvas.width, canvas.height)
        val width = maxOf(1, kotlin.math.round(fit.width).toInt())
        val height = maxOf(1, kotlin.math.round(fit.height).toInt())
        val old = texture.layoutParams
        if (old.width != width || old.height != height) {
            texture.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
        }
    }

    private fun controls() {
        val request = MirrorWorkspace.current()
        if (ticket == null) {
            if (request?.stage == MirrorWorkspace.Stage.PREPARING || request?.stage == MirrorWorkspace.Stage.STARTING) {
                exitAfterStop = true; control.stop(); refresh()
            } else { management(0) }
            return
        }
        AlertDialog.Builder(this).setTitle("投屏控制")
            .setItems(arrayOf("返回投屏首页", "更换应用", "停止投屏", "继续操作")) { _, index ->
                when (index) {
                    0 -> management(0)
                    1 -> management(1)
                    2 -> { exitAfterStop = true; control.stop(); refresh() }
                }
            }.show()
    }

    private fun management(page: Int) {
        startActivity(Intent(this, ProductActivity::class.java).putExtra("page", page)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    @Deprecated("Android 10 vehicle uses legacy back dispatch")
    override fun onBackPressed() {
        if (ticket != null) { MirrorBridge.back(lease) } else { controls() }
    }

    private fun attach() {
        if (!resumed || ticket == null || texture.width <= 1 || texture.height <= 1) { return }
        val source = texture.surfaceTexture ?: return
        source.setDefaultBufferSize(texture.width, texture.height)
        lease = MirrorBridge.nextLease()
        MirrorBridge.attach(lease, Surface(source), texture.width, texture.height)
    }

    private fun forward(event: MotionEvent) {
        val owner = MirrorBridge.current() ?: return
        if (!resumed || owner.ticket != ticket || owner.width <= 0 || texture.width <= 1 || texture.height <= 1) { return }
        val geometry = MirrorGeometry.fit(owner.width, owner.height, texture.width, texture.height)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { gesture = geometry.contains(event.x, event.y) }
        if (!gesture) { return }
        val copy = MotionEvent.obtainNoHistory(event)
        copy.transform(Matrix().apply {
            setValues(floatArrayOf(owner.width / geometry.width, 0f, -geometry.left * owner.width / geometry.width,
                0f, owner.height / geometry.height, -geometry.top * owner.height / geometry.height, 0f, 0f, 1f))
        })
        MirrorBridge.touch(lease, copy, owner.width, owner.height)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) { gesture = false }
    }

    private fun applicationLabel(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) { "应用" }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { attach() }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { attach() }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { MirrorBridge.detach(lease); return true }
}
