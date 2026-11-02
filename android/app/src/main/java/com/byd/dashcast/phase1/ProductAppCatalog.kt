package com.byd.dashcast.phase1

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Explicit application selection; no implicit main-screen fallback or licensing requirement.
 * @author Codex
 * @date 2026/10/21
 */
internal data class ProductApp(val component: String, val label: String, val navigation: Boolean,
    val capability: AppEntryCapability = AppEntryCapability.UNASSESSED) {
    fun target(): ProjectionTarget = ProjectionTarget.remoteComponent(component, label)
}

internal object ProductAppCatalog {
    // Category advice, not a claim that every version supports Android secondary displays.
    private val navigationPackages = setOf("com.byd.automap", "com.autonavi.amapautoclone", "com.autonavi.amapauto",
        "com.autonavi.minimap", "com.baidu.BaiduMap", "com.baidu.navi", "com.tencent.map")
    fun navigation(component: String): Boolean = component.substringBefore('/') in navigationPackages
    fun load(context: Context): List<ProductApp> {
        val manager = context.packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return manager.queryIntentActivities(launch, 0).mapNotNull { item ->
            val info = item.activityInfo ?: return@mapNotNull null
            if (!info.enabled || info.packageName == context.packageName) { return@mapNotNull null }
            val component = ComponentName(info.packageName, info.name).flattenToString()
            val target = ProjectionTarget.remoteComponent(component, null)
            ProductApp(component, item.loadLabel(manager).toString().take(128), navigation(component),
                ProjectionEntryRouter.assess(context, target))
        }.distinctBy { it.component }.sortedWith(compareBy<ProductApp>(
            { if (it.capability.allowed) 0 else 1 },
            { if (it.component.startsWith("com.autonavi.amapautoclone/")) 0 else if (it.navigation) 1 else 2 },
            { it.label }))
    }
    fun select(apps: List<ProductApp>, token: String?): ProductApp? =
        apps.firstOrNull { it.component == token } ?: apps.firstOrNull { it.navigation && it.capability.allowed }

    fun diagnostics(apps: List<ProductApp>): String = buildString {
        append("INSTALLED_APP_ENTRY_CAPABILITIES diagnostic_only=true count=${apps.size}\n")
        apps.take(32).forEach { app ->
            append("APP_CATALOG component=${IndependentAppPlan.safe(app.component)} allowed=${app.capability.allowed}\n")
            append(app.capability.evidence.take(2200))
        }
    }
}
