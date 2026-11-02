package com.byd.dashcast.phase1

import java.io.IOException

/** Same-user rendering plans. Never clone user data or move a caller's existing task. */
internal object IndependentAppPlan {
    const val BYD_PACKAGE = "com.byd.automap"
    const val BYD_METER = "$BYD_PACKAGE/com.byd.automap.extra.MeterActivity"
    const val BYD_PRIMARY = "$BYD_PACKAGE/com.byd.automap.central.CentralActivity"
    // Historical evidence version, not a runtime allowlist of one APK build.
    const val VERIFIED_BYD_VERSION = 220102029L

    data class Activity(
        val component: String,
        val enabled: Boolean,
        val exported: Boolean,
        val permission: String?,
        val launchMode: Int,
        val affinity: String?,
        val reparents: Boolean,
        val root: String = component
    )

    data class Plan(val component: String, val nativeSecondary: Boolean, val taskRoot: String = component) {
        fun arguments(display: Int, user: Int): List<String> {
            require(display > 0 && user >= 0)
            val args = mutableListOf("/system/bin/am", "start", "-W", "--display", display.toString(),
                "--user", user.toString(), "-f", "0x18000000", "-n", component)
            if (!nativeSecondary) {
                args += listOf("-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER")
            }
            return args
        }
    }

    @JvmStatic fun candidate(requested: String): String =
        if (requested.substringBefore('/') == BYD_PACKAGE) BYD_METER else requested

    @JvmStatic
    fun requireMultiInstance(activities: List<Activity>) {
        val entry = activities.firstOrNull() ?: throw IOException("APP_INDEPENDENT_ENTRY_UNAVAILABLE empty_scope")
        // Separate-affinity screens (e.g. an unrelated settings task) do not prove
        // that the selected task reuses a primary instance. Same-affinity redirects
        // remain conservatively blocked; launching them can steal that instance.
        val unsafe = activities.firstOrNull { it.enabled &&
            (it.root == entry.root || it.affinity == entry.affinity) &&
            (it.launchMode !in 0..1 || it.reparents) }
        if (unsafe != null) {
            throw IOException("APP_SINGLE_INSTANCE_UNSUPPORTED; scope=selected_task offending=${safe(unsafe.root)}" +
                " mode=${unsafe.launchMode} reparent=${unsafe.reparents}")
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun select(requested: Activity, candidate: Activity, version: Long): Plan {
        val ownPackage = requested.component.substringBefore('/')
        if (candidate.component.substringBefore('/') != ownPackage ||
            candidate.root.substringBefore('/') != ownPackage || !candidate.enabled ||
            !candidate.exported || candidate.permission != null || candidate.reparents) {
            throw IOException("APP_INDEPENDENT_ENTRY_UNAVAILABLE")
        }
        if (ownPackage == BYD_PACKAGE) {
            if (candidate.component != BYD_METER || candidate.root != BYD_METER) {
                throw IOException("APP_NATIVE_SECONDARY_ENTRY_MISMATCH")
            }
            if (version <= 0) { throw IOException("APP_NATIVE_SECONDARY_VERSION_UNAVAILABLE") }
            if (candidate.launchMode !in 0..2) { throw IOException("APP_NATIVE_SECONDARY_MODE_UNSUPPORTED") }
            if (candidate.affinity.isNullOrEmpty() || requested.affinity.isNullOrEmpty() ||
                candidate.affinity == requested.affinity || candidate.root == requested.root) {
                throw IOException("APP_NATIVE_SECONDARY_AFFINITY_NOT_INDEPENDENT")
            }
            return Plan(candidate.component, true, candidate.root)
        }
        if (candidate.component != requested.component || candidate.launchMode !in 0..1) {
            throw IOException("APP_SINGLE_INSTANCE_UNSUPPORTED; original task left untouched")
        }
        return Plan(candidate.component, false, candidate.root)
    }

    /** Package metadata is untrusted text; diagnostics must be bounded and single-line. */
    internal fun safe(value: String?): String = value.orEmpty().take(384)
        .map { if (it.isLetterOrDigit() || it in "._/:-$") it else '_' }.joinToString("")
}
