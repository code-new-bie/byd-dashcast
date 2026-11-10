package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test

/** Tests the exact continuation used by ProductController, with no Android permission bypass. */
class UsbPermissionContinuationTest {
    @Test fun matchingGrantResumesExactlyOnce() {
        val continuation = UsbPermissionContinuation<String>()
        continuation.begin("/dev/bus/usb/001/005", 4, "recover-then-start")
        val result = continuation.consume("/dev/bus/usb/001/005", 4, true)
        assertEquals(UsbPermissionContinuation.Result.GRANTED, result.result)
        assertEquals("recover-then-start", result.request)
        assertEquals(UsbPermissionContinuation.Result.UNRELATED,
            continuation.consume("/dev/bus/usb/001/005", 4, true).result)
    }

    @Test fun foreignOrOldEnumerationDoesNotConsumeTheCurrentRequest() {
        val continuation = UsbPermissionContinuation<String>()
        continuation.begin("new-address", 4, "current")
        assertEquals(UsbPermissionContinuation.Result.UNRELATED, continuation.consume("old-address", 4, false).result)
        assertEquals("current", continuation.consume("new-address", 4, true).request)
    }

    @Test fun cancelledOrNewGenerationCannotStartFromAnOldGrant() {
        val continuation = UsbPermissionContinuation<String>()
        continuation.begin("device", 4, "old")
        assertEquals(UsbPermissionContinuation.Result.STALE, continuation.consume("device", 5, true).result)
        continuation.begin("device", 5, "new")
        continuation.clear()
        assertEquals(UsbPermissionContinuation.Result.UNRELATED, continuation.consume("device", 5, true).result)
    }

    @Test fun denialIsDistinctFromWaitingAndCannotLaterAutoStart() {
        val continuation = UsbPermissionContinuation<String>()
        continuation.begin("device", 4, "request")
        val result = continuation.consume("device", 4, false)
        assertEquals(UsbPermissionContinuation.Result.DENIED, result.result)
        assertNull(result.request)
        assertEquals(UsbPermissionContinuation.Result.UNRELATED, continuation.consume("device", 4, true).result)
    }

    @Test fun explicitNewRequestCanReplaceCancelledWaitingOperation() {
        val continuation = UsbPermissionContinuation<String>()
        continuation.begin("old", 1, "old")
        continuation.begin("new", 2, "new")
        assertEquals(UsbPermissionContinuation.Result.UNRELATED, continuation.consume("old", 2, true).result)
        assertEquals("new", continuation.consume("new", 2, true).request)
    }

    @Test fun invalidIdentityCannotCreatePendingAuthorization() {
        val continuation = UsbPermissionContinuation<String>()
        assertThrows(IllegalArgumentException::class.java) { continuation.begin("", 1, "request") }
        assertThrows(IllegalArgumentException::class.java) { continuation.begin("device", 0, "request") }
    }
}
