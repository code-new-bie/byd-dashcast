package com.byd.dashcast.phase1

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

/** Narrow event subscription: only the current generic projection package, no content retrieval.
 * The shell rechecks display 0 and task identity; an accessibility event never authorizes deletion.
 * @author Codex
 * @date 2026/10/25
 */
class MirrorIconService : AccessibilityService() {
    private var lastMapClick = -1000L
    private val mapEntry by lazy { MapEntryOverlay(this) }
    override fun onServiceConnected() { active = WeakReference(this); configure(); mapEntry.refresh() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        mapEntry.refresh()
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.packageName?.toString() == "com.byd.automap") {
            var node = event.source
            try {
                repeat(3) {
                    if (MapButtonPolicy.accepts(event.packageName?.toString(), node?.viewIdResourceName,
                            event.eventTime, lastMapClick)) {
                        lastMapClick = event.eventTime
                        mapClick(this,true)
                        return
                    }
                    val parent = node?.parent
                    @Suppress("DEPRECATION")
                    node?.recycle()
                    node = parent
                }
            } finally {
                @Suppress("DEPRECATION")
                node?.recycle()
            }
        }
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) { return }
        val selected = MirrorBridge.current() ?: return
        if (event.packageName?.toString() == selected.packageName) { MirrorBridge.route(selected.packageName) }
    }
    override fun onInterrupt() { mapEntry.close() }
    override fun onDestroy() { mapEntry.close(); active = null; super.onDestroy() }

    private fun configure() {
        val info = serviceInfo ?: return
        val selected = MirrorBridge.current()
        info.packageNames = listOfNotNull(selected?.packageName, "com.byd.automap").distinct().toTypedArray()
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.notificationTimeout = 40
        serviceInfo = info
    }

    companion object {
        @Volatile private var active: WeakReference<MirrorIconService>? = null
        fun mapClick(context: android.content.Context, original: Boolean) {
            val controller = ProductController.get(context)
            controller.recordExternal("MAP_ENTRY_CLICK source=${if (original) "oem" else "fallback"}")
            if (ProjectionService.active()) { controller.stop(); return }
            if (!original || !ExternalDisplayAccess.enabled()) { controller.toggleMap(); return }
            // Give the original button's own MeterActivity time to arrive at the standby screen.
            // If the OEM action does nothing on this vehicle, use the established native map path.
            android.os.Handler(context.mainLooper).postDelayed({
                if (ExternalDisplayAccess.mapEntryVisible() && !ProjectionService.active() && !controller.busy) {
                    controller.toggleMap()
                }
            },1500)
        }
        fun refresh() { active?.get()?.let { service ->
            android.os.Handler(service.mainLooper).post { service.configure(); service.mapEntry.refresh() }
        } }
    }
}

internal object MapButtonPolicy {
    val ids = setOf("com.byd.automap:id/v_main_home_expand_screen_btn",
        "com.byd.automap:id/v_navi_drive_expand_screen_btn")
    fun accepts(pkg: String?, id: String?, now: Long, previous: Long): Boolean =
        pkg == "com.byd.automap" && id in ids && now >= 0 && now >= previous && now - previous >= 1000
}
