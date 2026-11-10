package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test

/** Null surfaces exercise the actual mailbox's ownership state without calling Android Surface methods.
 * @author Codex
 * @date 2026/10/25
 */
class MirrorSinkMailboxTest {
    @Test fun staleDetachCannotUnbindNewPage() {
        val mailbox = MirrorSinkMailbox()
        mailbox.attach(MirrorSinkMailbox.Sink(1, null, 100, 100))
        mailbox.attach(MirrorSinkMailbox.Sink(2, null, 200, 100))
        mailbox.detach(1)
        assertEquals(2L, mailbox.take()!!.lease)
        assertNull(mailbox.take())
    }
    @Test fun matchingDetachTransfersANullSurfaceOnce() {
        val mailbox = MirrorSinkMailbox()
        mailbox.attach(MirrorSinkMailbox.Sink(2, null, 200, 100))
        mailbox.take()
        mailbox.detach(2)
        assertEquals(MirrorSinkMailbox.Sink(2, null, 0, 0), mailbox.take())
        assertNull(mailbox.take())
    }
    @Test fun duplicateOldOrInvalidGeometryCannotReplaceCurrentPage() {
        val mailbox = MirrorSinkMailbox()
        mailbox.attach(MirrorSinkMailbox.Sink(2, null, 200, 100))
        mailbox.attach(MirrorSinkMailbox.Sink(1, null, 100, 100))
        mailbox.attach(MirrorSinkMailbox.Sink(2, null, 100, 100))
        mailbox.attach(MirrorSinkMailbox.Sink(3, null, 0, 100))
        mailbox.attach(MirrorSinkMailbox.Sink(4, null, 100, 8193))
        assertEquals(200, mailbox.take()!!.width)
    }
    @Test fun closedMailboxDoesNotResurrect() {
        val mailbox = MirrorSinkMailbox()
        mailbox.attach(MirrorSinkMailbox.Sink(1, null, 100, 100))
        mailbox.close()
        mailbox.attach(MirrorSinkMailbox.Sink(2, null, 100, 100))
        mailbox.detach(1)
        mailbox.close()
        assertNull(mailbox.take())
    }
}
