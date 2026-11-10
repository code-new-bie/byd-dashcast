@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package com.byd.dashcast.phase1.ncm

/**
 * Object.wait/notifyAll for the NCM stack's monitor-based queues; Kotlin's Any does not expose them.
 * Call only while holding the receiver's monitor (synchronized block or @Synchronized method).
 */
@Throws(InterruptedException::class)
internal fun Any.monitorWait(millis: Long) {
    (this as java.lang.Object).wait(millis)
}

internal fun Any.monitorNotifyAll() {
    (this as java.lang.Object).notifyAll()
}
