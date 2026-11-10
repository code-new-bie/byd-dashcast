package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test

class DuplexTicketTest {
    @Test fun scopedRandomTicketsAreDifferentAndValid() {
        val tickets = (1..1000).map { DuplexTicket.create() }
        assertEquals(1000, tickets.toSet().size)
        assertTrue(tickets.all { DuplexTicket.valid(it) })
    }
    @Test fun suffixIsRemovedBeforeExistingGeometryParsing() {
        val key = "a".repeat(48)
        val base = arrayOf("0", "live", "remote", "app/Main", "viewport-full-fit")
        val args = base + DuplexTicket.argument(key)
        assertEquals(key, DuplexTicket.read(args))
        assertArrayEquals(base, DuplexTicket.without(args))
        assertEquals("viewport-full-fit", CaptureOptions.fromShellArguments(DuplexTicket.without(args)).token())
    }
    @Test fun legacyArgumentsRemainIdentical() {
        val args = arrayOf("0", "live", "fit")
        assertNull(DuplexTicket.read(args))
        assertArrayEquals(args, DuplexTicket.without(args))
    }
    @Test fun malformedOrShellSpecialTokensAreRejected() {
        listOf("", "A".repeat(48), "a".repeat(47), "a".repeat(49), "a".repeat(47) + ";",
            "a".repeat(47) + "\n").forEach { token ->
            assertFalse(DuplexTicket.valid(token))
            assertThrows(IllegalArgumentException::class.java) { DuplexTicket.argument(token) }
        }
    }
    @Test fun malformedReservedSuffixIsNotSilentlyTreatedAsLegacy() {
        assertThrows(IllegalArgumentException::class.java) { DuplexTicket.read(arrayOf("0", "live", "duplex:bad")) }
    }
}
