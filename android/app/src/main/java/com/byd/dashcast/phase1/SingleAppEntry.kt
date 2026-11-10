package com.byd.dashcast.phase1

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import java.io.IOException
import java.util.function.Consumer

/** One original task, not a second business instance. No package/category/launchMode allowlist. */
internal object SingleAppEntry {
    data class Entry(val component: String, val root: String, val packageName: String, val version: Long)

    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun resolve(context: Context, target: ProjectionTarget, record: Consumer<String> = Consumer {}): Entry {
        if (!target.isRemote) { throw IOException("APP_PRIMARY_MIRROR_DISABLED") }
        val name = ComponentName.unflattenFromString(target.component()) ?: throw IOException("APP_ENTRY_INVALID")
        try {
            val pm = context.packageManager
            val info = pm.getActivityInfo(name, 0)
            val actual = info.targetActivity?.let { pm.getActivityInfo(ComponentName(info.packageName, it), 0) } ?: info
            if (!info.enabled || !actual.enabled || !info.applicationInfo.enabled || !info.exported ||
                actual.packageName != name.packageName || name.packageName == BuildConfig.APPLICATION_ID) {
                throw IOException("APP_ENTRY_UNAVAILABLE")
            }
            val version = pm.getPackageInfo(name.packageName, 0).longVersionCode
            record.accept("APP_SINGLE_TASK_ENTRY component=${IndependentAppPlan.safe(target.component())} " +
                "root=${IndependentAppPlan.safe(actual.name)} version=$version launch_mode=${actual.launchMode} " +
                "same_task=true same_user_data=true installed_entry_only=true runtime_unverified=true")
            return Entry(name.flattenToString(), ComponentName(name.packageName, actual.name).flattenToString(),
                name.packageName, version)
        } catch (error: PackageManager.NameNotFoundException) {
            throw IOException("APP_ENTRY_UNAVAILABLE", error)
        }
    }

    fun assess(context: Context, target: ProjectionTarget): AppEntryCapability {
        val evidence = StringBuilder()
        return try {
            resolve(context, target) { evidence.append(it.take(1800)).append('\n') }
            AppEntryCapability(true, false, "APP_SINGLE_TASK_DUPLEX", evidence.toString().take(2200))
        } catch (error: IOException) {
            AppEntryCapability(false, false, error.message.orEmpty().take(300), evidence.toString().take(2200))
        } catch (error: SecurityException) {
            AppEntryCapability(false, false, "APP_ENTRY_PERMISSION_DENIED", evidence.toString().take(2200))
        }
    }
}
