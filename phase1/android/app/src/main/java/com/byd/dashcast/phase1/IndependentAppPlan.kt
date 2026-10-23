package com.byd.dashcast.phase1

import java.io.IOException

/** Same-user rendering plans. Never clone user data or move a caller's existing task. */
internal object IndependentAppPlan {
    const val BYD_PACKAGE = "com.byd.automap"
    const val BYD_METER = "$BYD_PACKAGE/com.byd.automap.extra.MeterActivity"
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
        if (activities.isEmpty() || activities.any { it.launchMode !in 0..1 || it.reparents }) {
            throw IOException("APP_SINGLE_INSTANCE_UNSUPPORTED; application requires a dedicated secondary entry")
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
            if (version != VERIFIED_BYD_VERSION || candidate.component != BYD_METER ||
                candidate.affinity.isNullOrEmpty() || candidate.affinity == requested.affinity ||
                candidate.component == requested.component) {
                throw IOException("APP_NATIVE_SECONDARY_VERSION_UNVERIFIED")
            }
            return Plan(candidate.component, true, candidate.root)
        }
        if (candidate.component != requested.component || candidate.launchMode !in 0..1) {
            throw IOException("APP_SINGLE_INSTANCE_UNSUPPORTED; original task left untouched")
        }
        return Plan(candidate.component, false, candidate.root)
    }
}
