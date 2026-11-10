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
    override fun onServiceConnected() { active = WeakReference(this); configure() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.packageName?.toString() == "com.byd.automap") {
            var node = event.source
            try {
                repeat(3) {
                    if (MapButtonPolicy.accepts(event.packageName?.toString(), node?.viewIdResourceName,
                            event.eventTime, lastMapClick)) {
                        lastMapClick = event.eventTime
                        mapClick(this)
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
    override fun onInterrupt() {}
    override fun onDestroy() { active = null; super.onDestroy() }

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
        fun connected(): Boolean = active?.get()!=null
        /** The OEM button only asks the vehicle for a navi-type change and waits for the cluster to echo it;
         * this cluster never echoes, so the map itself does nothing. Act on the click here instead. */
        fun mapClick(context: android.content.Context) {
            val controller = ProductController.get(context)
            controller.recordExternal("MAP_ENTRY_CLICK source=oem")
            if (ProjectionService.active() || !ExternalDisplayAccess.enabled()) { controller.mapButton(); return }
            // A cluster that does echo opens the map's own MeterActivity on the standby screen; give it a
            // moment so both paths do not race, then use the established native map path.
            android.os.Handler(context.mainLooper).postDelayed({
                if (!ProjectionService.active() && !controller.busy) { controller.mapButton() }
            },1500)
        }
        fun refresh() {
            active?.get()?.let { service -> android.os.Handler(service.mainLooper).post { service.configure() } }
        }
    }
}

/** Edits the colon-separated Settings.Secure list without dropping other services. */
internal object AccessibilitySetting {
    fun component(packageName: String) = "$packageName/${MirrorIconService::class.java.name}"

    /** Returns the new value, or null when the service is already listed. */
    fun withService(current: String?, component: String): String? {
        val entries = current.orEmpty().trim().takeUnless { it == "null" }.orEmpty()
            .split(':').map { it.trim() }.filter { it.isNotEmpty() }
        if (component in entries) { return null }
        return (entries + component).joinToString(":")
    }
}

internal object MapButtonPolicy {
    val ids = setOf("com.byd.automap:id/v_main_home_expand_screen_btn",
        "com.byd.automap:id/v_navi_drive_expand_screen_btn")
    fun accepts(pkg: String?, id: String?, now: Long, previous: Long): Boolean =
        pkg == "com.byd.automap" && id in ids && now >= 0 && now >= previous && now - previous >= 1000
}
