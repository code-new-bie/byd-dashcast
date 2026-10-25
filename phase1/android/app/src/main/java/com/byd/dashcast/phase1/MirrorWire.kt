package com.byd.dashcast.phase1

/** Private, bounded local Binder control plane. Never mixed with DCS1 video or DCV1.
 * @author Codex
 * @date 2026/10/25
 */
internal object MirrorWire {
    const val DESCRIPTOR = "com.byd.dashcast.phase1.MirrorSession.v1"
    const val CALLBACK = "com.byd.dashcast.phase1.MirrorCallback.v1"
    const val REGISTER = 1
    const val ATTACH = 2
    const val DETACH = 3
    const val TOUCH = 4
    const val BACK = 5
    const val ROUTE = 6
    const val SHOW = 1
    const val CLOSED = 2
    const val MAX_PARCEL = 32768
}
