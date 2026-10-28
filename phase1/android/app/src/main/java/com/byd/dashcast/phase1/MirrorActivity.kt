package com.byd.dashcast.phase1

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout

/** Optional main-display consumer of the SAME hosted app. Home/Recents remain Android's own behavior.
 * @author Codex
 * @date 2026/10/25
 */
class MirrorActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var texture: TextureView
    private var lease = 0L
    private var resumed = false
    private var gesture = false
    private var ticket: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val watch = object : Runnable {
        override fun run() {
            if (MirrorBridge.current()?.ticket != ticket) { finish(); return }
            main.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val owner = MirrorBridge.current()
        if (owner?.endpoint == null) { finish(); return }
        ticket = owner.ticket
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        // Keep the primary screen's system navigation/IME instead of hiding it for a secondary-screen app.
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        texture = TextureView(this).apply {
            id = R.id.mirror_surface
            surfaceTextureListener = this@MirrorActivity
            contentDescription = "正在投屏的应用操作画面"
            setOnTouchListener { _, event -> forward(event); true }
        }
        root.addView(texture, FrameLayout.LayoutParams(-1, -1))
        // Light watermark instead of a button: the page belongs to the hosted app's
        // own interface; the control hint stays readable but visually recessive.
        root.addView(android.widget.TextView(this).apply {
            text = "返回车机 · 仪表继续投屏"
            textSize = 13f
            setTextColor(Color.argb(0x99, 0xFF, 0xFF, 0xFF))
            setShadowLayer(2f, 0f, 1f, Color.argb(0x99, 0, 0, 0))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            contentDescription = "离开操作画面，仪表继续投屏"
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.END).apply {
            topMargin = dp(18); marginEnd = dp(18)
        })
        setContentView(root)
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    override fun onResume() {
        super.onResume()
        resumed = true
        if (::texture.isInitialized && texture.isAvailable) { attach() }
        main.post(watch)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val owner = MirrorBridge.current()
        if (owner?.endpoint == null) { finish(); return }
        if (ticket != owner.ticket) {
            MirrorBridge.detach(lease)
            gesture = false
            ticket = owner.ticket
            if (resumed && ::texture.isInitialized && texture.isAvailable) { attach() }
        }
    }

    override fun onPause() {
        resumed = false
        gesture = false
        MirrorBridge.detach(lease)
        main.removeCallbacks(watch)
        super.onPause()
    }

    @Deprecated("Android 10 vehicle uses legacy back dispatch")
    override fun onBackPressed() { MirrorBridge.back(lease) }

    private fun attach() {
        if (!resumed || texture.width <= 0 || texture.height <= 0) { return }
        val source = texture.surfaceTexture ?: return
        source.setDefaultBufferSize(texture.width, texture.height)
        lease = MirrorBridge.nextLease()
        MirrorBridge.attach(lease, Surface(source), texture.width, texture.height)
    }

    private fun forward(event: MotionEvent) {
        val owner = MirrorBridge.current() ?: return
        if (!resumed || owner.ticket != ticket || owner.width <= 0 || texture.width <= 0 || texture.height <= 0) { return }
        val sourceWidth = owner.width
        val sourceHeight = owner.height
        val geometry = MirrorGeometry.fit(sourceWidth, sourceHeight, texture.width, texture.height)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { gesture = geometry.contains(event.x, event.y) }
        if (!gesture) { return }
        val copy = MotionEvent.obtainNoHistory(event)
        val transform = Matrix().apply {
            setValues(floatArrayOf(sourceWidth / geometry.width, 0f, -geometry.left * sourceWidth / geometry.width,
                0f, sourceHeight / geometry.height, -geometry.top * sourceHeight / geometry.height, 0f, 0f, 1f))
        }
        copy.transform(transform)
        MirrorBridge.touch(lease, copy, sourceWidth, sourceHeight)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            gesture = false
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { attach() }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { attach() }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        MirrorBridge.detach(lease)
        return true
    }
}
