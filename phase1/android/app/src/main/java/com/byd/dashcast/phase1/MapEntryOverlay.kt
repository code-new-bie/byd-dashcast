package com.byd.dashcast.phase1

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** A map-only fallback when the OEM vehicle-capability gate hides its original button.
 * It never changes the OEM map, vehicle capability, global input focus or Activity controller.
 */
internal class MapEntryOverlay(private val service: Context) {
    private var button: TextView? = null
    private val windows = service.getSystemService(WindowManager::class.java)

    fun refresh() {
        if (!ExternalDisplayAccess.mapEntryVisible()) { close(); return }
        if (!android.provider.Settings.canDrawOverlays(service)) { close(); return }
        val root = MirrorIconService.mapRoot()
        val originalVisible = if (root == null) false else try {
            if (root.packageName?.toString() != "com.byd.automap") { false } else {
                MapButtonPolicy.ids.any { id ->
                    val nodes = root.findAccessibilityNodeInfosByViewId(id)
                    try { nodes.any { it.isVisibleToUser } }
                    finally { @Suppress("DEPRECATION") for (node in nodes) { node.recycle() } }
                }
            }
        } finally { @Suppress("DEPRECATION") root.recycle() }
        if (originalVisible) { close(); return }
        if (button == null) {
            val density = service.resources.displayMetrics.density
            fun dp(value: Int) = (value*density+0.5f).toInt()
            val view = TextView(service).apply {
                textSize = 16f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE); setPadding(dp(16),dp(10),dp(16),dp(10))
                minHeight = dp(48)
                background = GradientDrawable().apply { setColor(0xe6223446.toInt()); cornerRadius=dp(20).toFloat() }
                elevation = dp(4).toFloat()
                setOnClickListener { MirrorIconService.mapClick(service,false) }
            }
            val layout = WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.START or Gravity.BOTTOM; x=dp(20); y=dp(100)
                title = "Dashcast map projection entry"
            }
            try {
                windows.addView(view,layout); button=view
                ProductController.get(service).recordExternal("MAP_ENTRY_VISIBLE source=owned_overlay position=bottom_left")
            }
            catch (error: Exception) {
                ProductController.get(service).recordExternal("MAP_ENTRY_ADD_FAILED type=${error.javaClass.simpleName}")
                return
            }
        }
        button?.text = if (ProjectionService.active()) "结束投屏" else "仪表投屏"
        button?.contentDescription = "Dashcast 地图投屏入口"
    }

    fun close() {
        val current = button ?: return
        button = null
        try { windows.removeView(current) }
        catch (error: Exception) { ProductController.get(service).recordExternal("MAP_ENTRY_REMOVE_FAILED type=${error.javaClass.simpleName}") }
    }
}
