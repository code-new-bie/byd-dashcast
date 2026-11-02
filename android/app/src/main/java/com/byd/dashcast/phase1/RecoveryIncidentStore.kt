package com.byd.dashcast.phase1

import android.content.Context

/** Bounded last terminal-cleanup evidence, diagnostic only; never authorizes a new session.
 * @author Codex
 * @date 2026/10/24
 */
internal object RecoveryIncidentStore {
    private const val MAX_DETAIL = 2200
    private const val STORE = "dashcast-cleanup-evidence"

    @JvmStatic
    fun record(context: Context, state: ProjectionSessionController.State, detail: String) {
        if (state != ProjectionSessionController.State.QUARANTINED &&
            state != ProjectionSessionController.State.RECOVERY_REQUIRED &&
            state != ProjectionSessionController.State.DISCONNECTED) { return }
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putString("last", format(state, detail, BuildConfig.VERSION_NAME, System.currentTimeMillis())).apply()
    }

    @JvmStatic
    fun snapshot(context: Context): String = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        .getString("last", null)?.let { "HISTORICAL_DIAGNOSTIC_NOT_CURRENT_STATE\n${it.take(2500)}\n" }.orEmpty()

    internal fun format(state: ProjectionSessionController.State, detail: String, version: String, at: Long): String =
        "LAST_CLEANUP_INCIDENT version=${version.take(40)} at=$at state=$state detail=${detail.take(MAX_DETAIL)}"
}
