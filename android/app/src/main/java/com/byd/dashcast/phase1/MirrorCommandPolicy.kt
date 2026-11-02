package com.byd.dashcast.phase1

/** Validation shared by the real endpoint and deterministic boundary tests.
 * @author Codex
 * @date 2026/10/25
 */
internal object MirrorCommandPolicy {
    fun caller(uid: Int, expected: Int, size: Int, closed: Boolean): Boolean =
        !closed && expected >= 0 && uid == expected && size in 0..MirrorWire.MAX_PARCEL

    fun input(pointers: Int, history: Int, eventTime: Long, now: Long, index: Int): Boolean =
        pointers in 1..10 && history in 0..64 && eventTime >= 0 && eventTime <= now + 1000 &&
            index in 0 until pointers

    fun coordinate(x: Float, y: Float, width: Int, height: Int): Boolean =
        x.isFinite() && y.isFinite() && x >= 0 && y >= 0 && x < width && y < height
}
