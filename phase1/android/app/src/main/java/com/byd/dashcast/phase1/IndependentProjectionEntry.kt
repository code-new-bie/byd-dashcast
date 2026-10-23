package com.byd.dashcast.phase1

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import java.io.IOException

/** Resolve aliases and recheck the installed manifest in both UI and authorized shell. */
internal object IndependentProjectionEntry {
    @JvmStatic
    @Throws(IOException::class)
    fun resolve(context: Context, target: ProjectionTarget): IndependentAppPlan.Plan {
        if (!target.isRemote) { throw IOException("APP_PRIMARY_MIRROR_DISABLED") }
        val requested = ComponentName.unflattenFromString(target.component())
            ?: throw IOException("APP_INDEPENDENT_ENTRY_UNAVAILABLE")
        val manager = context.packageManager
        try {
            val launch = manager.getActivityInfo(requested, 0)
            val chosen = ComponentName.unflattenFromString(IndependentAppPlan.candidate(target.component()))
                ?: throw IOException("APP_INDEPENDENT_ENTRY_UNAVAILABLE")
            val activity = manager.getActivityInfo(chosen, 0)
            val installed = manager.getPackageInfo(requested.packageName,
                android.content.pm.PackageManager.GET_ACTIVITIES)
            val version = installed.longVersionCode
            fun actual(info: ActivityInfo): ActivityInfo = info.targetActivity?.let {
                manager.getActivityInfo(ComponentName(info.packageName, it), 0)
            } ?: info
            if (requested.packageName != IndependentAppPlan.BYD_PACKAGE) {
                IndependentAppPlan.requireMultiInstance(installed.activities.orEmpty().map { facts(it, actual(it)) })
            }
            return IndependentAppPlan.select(facts(launch, actual(launch)), facts(activity, actual(activity)), version)
        } catch (error: android.content.pm.PackageManager.NameNotFoundException) {
            throw IOException("APP_INDEPENDENT_ENTRY_UNAVAILABLE", error)
        }
    }

    private fun facts(info: ActivityInfo, actual: ActivityInfo): IndependentAppPlan.Activity {
        return IndependentAppPlan.Activity(ComponentName(info.packageName, info.name).flattenToString(),
            info.enabled && actual.enabled && info.applicationInfo.enabled, info.exported, info.permission,
            actual.launchMode, actual.taskAffinity,
            (info.flags or actual.flags) and ActivityInfo.FLAG_ALLOW_TASK_REPARENTING != 0,
            ComponentName(actual.packageName, actual.name).flattenToString())
    }
}
