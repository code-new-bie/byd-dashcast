package com.byd.dashcast.phase1

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Optional primary-display consumer. This view never owns or resizes the hosted task. */
internal class EmbeddedCastView(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val texture = TextureView(context).apply {
        id = R.id.mirror_surface
        contentDescription = "正在投屏的应用操作画面"
        surfaceTextureListener = this@EmbeddedCastView
        setOnTouchListener { _, event -> forward(event); true }
    }
    private val placeholder = TextView(context).apply {
        id = R.id.workspace_status
        textSize = 20f
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(16), dp(20), dp(16))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private var visibleConsumer = false
    private var ticket: String? = null
    private var lease = 0L
    private var attachedWidth = 0
    private var attachedHeight = 0
    private var gesture = false
    private var lastTouch: MotionEvent? = null
    private var sourceWidth = 3392
    private var sourceHeight = 912
    /** Width / height of what the window currently shows; the page layout picks its arrangement from it. */
    val sourceAspect: Float get() = sourceWidth.toFloat() / sourceHeight

    init {
        id = R.id.embedded_cast_window
        addView(texture, LayoutParams(1, 1, Gravity.CENTER))
        addView(placeholder, LayoutParams(-1, -1))
    }

    fun palette(background: Int, foreground: Int) {
        setBackgroundColor(background)
        placeholder.setBackgroundColor(background)
        placeholder.setTextColor(foreground)
    }

    fun setConsumerVisible(value: Boolean) {
        if (!value) { detachConsumer() }
        visibleConsumer = value
        if (value) { attachConsumer() }
    }

    fun refresh(underlay: Boolean) {
        val request = MirrorWorkspace.current()
        val owner = MirrorBridge.current()
        val terminal = request?.stage in setOf(MirrorWorkspace.Stage.STOPPING,
            MirrorWorkspace.Stage.STOPPED, MirrorWorkspace.Stage.FAILED)
        val ready = owner?.endpoint != null && !terminal && (request == null || request.ticket == owner.ticket)
        val profile = WorkspaceDisplayProfile.forMode(!underlay)
        val width = if (ready) owner!!.width else profile.width
        val height = if (ready) owner!!.height else profile.height
        if (sourceWidth != width || sourceHeight != height) {
            sourceWidth = width; sourceHeight = height; requestLayout()
        }
        if (ready) {
            if (ticket != owner!!.ticket) {
                detachConsumer(); ticket = owner.ticket
            }
            texture.visibility = View.VISIBLE; placeholder.visibility = View.GONE
            fitTexture(); attachConsumer()
        } else {
            if (ticket != null) { detachConsumer(); ticket = null }
            texture.visibility = View.INVISIBLE; placeholder.visibility = View.VISIBLE
            placeholder.text = when (request?.stage) {
                MirrorWorkspace.Stage.PREPARING, MirrorWorkspace.Stage.STARTING -> "正在打开应用，画面会显示在这里。"
                MirrorWorkspace.Stage.STOPPING -> "正在结束投屏，应用状态会保留。"
                MirrorWorkspace.Stage.FAILED -> request.error ?: "暂时无法投屏，请检查连接后重试。"
                else -> if (ProjectionService.active()) "仪表正在显示画面，请在原应用中操作。"
                    else "选择应用后，点击开始投屏。"
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val naturalHeight = (width.toDouble() * sourceHeight / sourceWidth).roundToInt().coerceAtLeast(1)
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) naturalHeight
            else minOf(naturalHeight, MeasureSpec.getSize(heightMeasureSpec)).coerceAtLeast(1)
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); fitTexture()
    }

    private fun fitTexture() {
        if (width < 1 || height < 1) { return }
        val fit = MirrorGeometry.fit(sourceWidth, sourceHeight, width, height)
        val w = fit.width.roundToInt().coerceAtLeast(1)
        val h = fit.height.roundToInt().coerceAtLeast(1)
        if (texture.layoutParams.width != w || texture.layoutParams.height != h) {
            texture.layoutParams = LayoutParams(w, h, Gravity.CENTER)
        }
    }

    private fun attachConsumer() {
        if (!visibleConsumer || ticket == null || !texture.isAvailable || texture.width < 2 || texture.height < 2) { return }
        if (lease != 0L && attachedWidth == texture.width && attachedHeight == texture.height) { return }
        detachConsumer()
        val source = texture.surfaceTexture ?: return
        source.setDefaultBufferSize(texture.width, texture.height)
        lease = MirrorBridge.nextLease()
        attachedWidth = texture.width; attachedHeight = texture.height
        MirrorBridge.attach(lease, Surface(source), attachedWidth, attachedHeight)
    }

    private fun detachConsumer() {
        cancelGesture()
        if (lease != 0L) { MirrorBridge.detach(lease) }
        lease = 0L; attachedWidth = 0; attachedHeight = 0
    }

    private fun cancelGesture() {
        lastTouch?.let {
            if (gesture && lease != 0L && MirrorBridge.current()?.ticket == ticket) {
                MirrorBridge.touch(lease, MotionEvent.obtain(it).apply { action = MotionEvent.ACTION_CANCEL },
                    sourceWidth, sourceHeight)
            }
            it.recycle()
        }
        lastTouch = null; gesture = false
    }

    private fun forward(event: MotionEvent) {
        val owner = MirrorBridge.current() ?: return
        if (!visibleConsumer || lease == 0L || owner.ticket != ticket || texture.width < 2 || texture.height < 2) { return }
        val geometry = MirrorGeometry.fit(owner.width, owner.height, texture.width, texture.height)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelGesture(); gesture = geometry.contains(event.x, event.y)
        }
        if (!gesture) { return }
        val copy = MotionEvent.obtainNoHistory(event)
        copy.transform(Matrix().apply {
            setValues(floatArrayOf(owner.width / geometry.width, 0f, -geometry.left * owner.width / geometry.width,
                0f, owner.height / geometry.height, -geometry.top * owner.height / geometry.height, 0f, 0f, 1f))
        })
        lastTouch?.recycle(); lastTouch = MotionEvent.obtain(copy)
        MirrorBridge.touch(lease, copy, owner.width, owner.height)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            gesture = false; lastTouch?.recycle(); lastTouch = null
        }
    }

    fun applicationBack(): Boolean {
        if (!visibleConsumer || lease == 0L || MirrorBridge.current()?.ticket != ticket) { return false }
        cancelGesture(); MirrorBridge.back(lease); return true
    }

    override fun onDetachedFromWindow() { setConsumerVisible(false); super.onDetachedFromWindow() }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { attachConsumer() }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { attachConsumer() }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { detachConsumer(); return true }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).roundToInt()
}
