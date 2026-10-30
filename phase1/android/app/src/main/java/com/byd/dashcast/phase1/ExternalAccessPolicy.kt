package com.byd.dashcast.phase1

/** An existing window is consumed once; STOP must not immediately restart it. */
internal class ExternalAccessPolicy {
    private var consumed: Int? = null
    fun reset() { consumed = null }
    fun candidate(owners: Set<Int>, healthy: Boolean, enabled: Boolean, active: Boolean, busy: Boolean): Int? {
        if (!healthy) { return null }
        if (owners.isEmpty()) { consumed = null; return null }
        val uid = owners.singleOrNull() ?: return null
        // Seeing a request while another video is active is not admission.
        // Keep it pending; only a successful start or explicit suppression
        // consumes it. Otherwise a perfectly valid window is lost forever.
        if (!enabled || active || busy || consumed == uid) { return null }
        return uid
    }
    fun consume(uid: Int?) { if (uid != null) { consumed = uid } }
}
