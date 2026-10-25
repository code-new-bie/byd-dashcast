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
    override fun onServiceConnected() { active = WeakReference(this); configure() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) { return }
        val selected = MirrorBridge.current() ?: return
        if (event.packageName?.toString() == selected.packageName) { MirrorBridge.route(selected.packageName) }
    }
    override fun onInterrupt() = Unit
    override fun onDestroy() { active = null; super.onDestroy() }

    private fun configure() {
        val info = serviceInfo ?: return
        val selected = MirrorBridge.current()
        info.packageNames = arrayOf(selected?.packageName ?: packageName)
        info.eventTypes = if (selected == null) 0 else AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.notificationTimeout = 40
        serviceInfo = info
    }

    companion object {
        @Volatile private var active: WeakReference<MirrorIconService>? = null
        fun refresh() { active?.get()?.let { service ->
            android.os.Handler(service.mainLooper).post { service.configure() }
        } }
    }
}
