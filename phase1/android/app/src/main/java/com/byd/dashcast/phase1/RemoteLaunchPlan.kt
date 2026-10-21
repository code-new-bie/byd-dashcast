package com.byd.dashcast.phase1

/** Avoid the known Amap launcher gateway which finishes itself on a cold map process.
 * The caller must still verify enabled/exported/permission with the installed PackageManager.
 * @author Codex
 * @date 2026/10/21
 */
internal object RemoteLaunchPlan {
    @JvmStatic
    fun mapActivityCandidate(component: String): String? {
        val packageName = component.substringBefore('/')
        val activity = component.substringAfter('/', "")
        if (packageName !in setOf("com.autonavi.amapauto", "com.autonavi.amapautoclone") ||
            activity != "com.autonavi.auto.remote.fill.UsbFillActivity") { return null }
        return "$packageName/com.autonavi.amapauto.MainMapActivity"
    }
}
