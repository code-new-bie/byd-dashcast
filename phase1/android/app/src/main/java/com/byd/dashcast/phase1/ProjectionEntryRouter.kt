package com.byd.dashcast.phase1

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import java.io.IOException
import java.util.function.Consumer
import org.json.JSONArray

/** Data table for real native-secondary entries; every other app uses the generic single-instance host.
 * @author Codex
 * @date 2026/10/25
 */
internal object ProjectionEntryRouter {
    data class NativeEntry(val packageName: String, val component: String, val extras: Map<String, Int>)
    data class Selection(val native: NativeEntry?, val entry: SingleAppEntry.Entry?)

    fun parse(text: String): List<NativeEntry> {
        require(text.length <= 16384)
        val array = JSONArray(text)
        require(array.length() <= 32)
        val result = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val pkg = item.getString("package")
            val component = item.getString("component")
            require(pkg.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")))
            require(component.startsWith("$pkg/") && component.length < 512 &&
                component.substringAfter('/').matches(Regex("[a-zA-Z0-9_.$]+")))
            val values = item.optJSONObject("intExtras")
            require(values == null || values.length() <= 8)
            val extras = values?.keys()?.asSequence()?.associateWith { key ->
                require(key.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,63}")))
                values.getInt(key)
            }.orEmpty()
            NativeEntry(pkg, component, extras)
        }
        require(result.map { it.packageName }.distinct().size == result.size)
        return result
    }

    @JvmStatic @JvmOverloads
    fun resolve(context: Context, target: ProjectionTarget,
        record: Consumer<String> = Consumer {}): Selection {
        if (target.isNamed) { return Selection(null, null) }
        val installed = context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
        val entries = installed.assets.open("native_projection_entries.json").bufferedReader().use {
            val buffer = CharArray(16385)
            var count = 0
            while (count < buffer.size) {
                val read = it.read(buffer, count, buffer.size - count)
                if (read < 0) { break }
                count += read
            }
            if (count > 16384) { throw IOException("NATIVE_ENTRY_TABLE_LIMIT") }
            parse(String(buffer, 0, count))
        }
        val native = entries.singleOrNull { it.packageName == target.component().substringBefore('/') }
        if (native == null) { return Selection(null, SingleAppEntry.resolve(context, target, record)) }
        // Keep the established BYD Meter capability checks, including its separate task affinity.
        if (native.packageName == IndependentAppPlan.BYD_PACKAGE) {
            IndependentProjectionEntry.resolve(context, target, record)
        } else {
            val manager = context.packageManager
            val secondary = manager.getActivityInfo(ComponentName.unflattenFromString(native.component)!!, 0)
            val primaryName = manager.getLaunchIntentForPackage(native.packageName)?.component
                ?: throw IOException("NATIVE_PRIMARY_ENTRY_MISSING")
            var primary = manager.getActivityInfo(primaryName, 0)
            primary.targetActivity?.let {
                primary = manager.getActivityInfo(ComponentName(native.packageName, it), 0)
            }
            if (!secondary.enabled || !secondary.exported || !secondary.applicationInfo.enabled ||
                secondary.permission != null || secondary.targetActivity != null ||
                secondary.taskAffinity.isNullOrBlank() || secondary.taskAffinity == primary.taskAffinity ||
                secondary.flags and ActivityInfo.FLAG_ALLOW_TASK_REPARENTING != 0) {
                throw IOException("NATIVE_SECONDARY_NOT_INDEPENDENT")
            }
        }
        record.accept("APP_NATIVE_PROFILE component=${IndependentAppPlan.safe(native.component)}")
        return Selection(native, null)
    }

    fun assess(context: Context, target: ProjectionTarget): AppEntryCapability {
        val evidence = StringBuilder()
        return try {
            val selected = resolve(context, target, Consumer { evidence.append(it.take(1800)).append('\n') })
            AppEntryCapability(true, selected.native != null, "MIRROR_SINGLE_INSTANCE", evidence.take(2200).toString())
        } catch (error: Exception) {
            AppEntryCapability(false, false, error.javaClass.simpleName + ":" + error.message.orEmpty().take(300),
                evidence.take(2200).toString())
        }
    }
}
