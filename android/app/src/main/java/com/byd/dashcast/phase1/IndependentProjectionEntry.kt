package com.byd.dashcast.phase1

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import java.io.IOException
import java.util.function.Consumer

/** Resolve aliases and recheck the installed manifest in both UI and authorized shell. */
internal object IndependentProjectionEntry {
    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun resolve(context: Context, target: ProjectionTarget,
        record: Consumer<String> = Consumer {}): IndependentAppPlan.Plan {
        if (!target.isRemote) { throw IOException("APP_PRIMARY_MIRROR_DISABLED") }
        val requested = ComponentName.unflattenFromString(target.component())
            ?: throw IOException("APP_INDEPENDENT_ENTRY_UNAVAILABLE")
        val manager = context.packageManager
        val prefix = "APP_ENTRY_CHECK requested=${IndependentAppPlan.safe(target.component())}"
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
            val selected = facts(launch, actual(launch))
            val candidate = facts(activity, actual(activity))
            record.accept("$prefix version=$version version_name=${IndependentAppPlan.safe(installed.versionName)} " +
                "candidate=${IndependentAppPlan.safe(candidate.component)} root=${IndependentAppPlan.safe(candidate.root)}")
            // A launcher alias can itself target MeterActivity. Compare the native
            // secondary against the real primary entry, not against that alias.
            val primary = if (requested.packageName == IndependentAppPlan.BYD_PACKAGE) {
                val info = try {
                    manager.getActivityInfo(ComponentName.unflattenFromString(IndependentAppPlan.BYD_PRIMARY)!!, 0)
                } catch (missing: android.content.pm.PackageManager.NameNotFoundException) {
                    val canonical = manager.getLaunchIntentForPackage(requested.packageName)?.component
                        ?: throw missing
                    manager.getActivityInfo(canonical, 0)
                }
                facts(info, actual(info))
            } else { selected }
            record.accept("APP_ENTRY_FACTS " +
                "primary_root=${IndependentAppPlan.safe(primary.root)} mode=${candidate.launchMode} " +
                "affinity=${IndependentAppPlan.safe(candidate.affinity)} " +
                "primary_affinity=${IndependentAppPlan.safe(primary.affinity)} enabled=${candidate.enabled} " +
                "exported=${candidate.exported} permission=${IndependentAppPlan.safe(candidate.permission)} " +
                "reparent=${candidate.reparents} reference_version=${IndependentAppPlan.VERIFIED_BYD_VERSION}")
            if (requested.packageName != IndependentAppPlan.BYD_PACKAGE) {
                val manifest = installed.activities.orEmpty().toList()
                val sameTask = manifest.filter { it.enabled && it.applicationInfo.enabled }.map { info ->
                    val resolved = info.targetActivity?.let { name ->
                        manifest.firstOrNull { it.name == name } ?: actual(info)
                    } ?: info
                    facts(info, resolved)
                }
                    .filter { it.root != selected.root }
                IndependentAppPlan.requireMultiInstance(listOf(selected) + sameTask)
            }
            return IndependentAppPlan.select(primary, candidate, version).also {
                record.accept("APP_ENTRY_ALLOWED component=${IndependentAppPlan.safe(it.component)} " +
                    "native_secondary=${it.nativeSecondary} installed_capability=true")
            }
        } catch (error: android.content.pm.PackageManager.NameNotFoundException) {
            record.accept("$prefix rejected=APP_INDEPENDENT_ENTRY_UNAVAILABLE missing=${IndependentAppPlan.safe(error.message)}")
            throw IOException("APP_INDEPENDENT_ENTRY_UNAVAILABLE", error)
        } catch (error: IOException) {
            record.accept("$prefix rejected=${error.message.orEmpty().take(768)}")
            throw error
        }
    }

    fun assess(context: Context, target: ProjectionTarget): AppEntryCapability {
        val evidence = StringBuilder()
        val record = Consumer<String> { line ->
            if (evidence.length < 2200) { evidence.append(line.take(2200 - evidence.length)).append('\n') }
        }
        return try {
            val plan = resolve(context, target, record)
            AppEntryCapability(true, plan.nativeSecondary, "", evidence.toString().take(2200))
        } catch (error: IOException) {
            AppEntryCapability(false, false, error.message.orEmpty().take(768), evidence.toString().take(2200))
        } catch (error: SecurityException) {
            AppEntryCapability(false, false, "APP_INDEPENDENT_ENTRY_PERMISSION_DENIED", evidence.toString().take(2200))
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
