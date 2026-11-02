package com.byd.dashcast.phase1

/** A first ACK, or the short cold-launcher animation, must not hide the OEM background.
 * This is source progress evidence, not a semantic map/decoder/display certification.
 * @author Codex
 * @date 2026/10/21
 */
internal object ProductVideoReadiness {
    @JvmStatic
    fun canActivate(acknowledged: Long, sourceMillis: Long): Boolean =
        acknowledged >= 30 && sourceMillis >= 1000
}
