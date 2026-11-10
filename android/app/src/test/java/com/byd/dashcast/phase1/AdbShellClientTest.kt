package com.byd.dashcast.phase1

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import java.util.function.LongSupplier

/**
 * In-memory ADB peer: authentication, strict framing/stream identity and no OPEN before trust.
 * @author Codex
 * @date 2026/10/10
 */
class AdbShellClientTest {
    @Test
    fun alreadyTrustedKeySignsOnceAndExecutesOnlyAfterCnxn() {
        val key = FakeIdentity()
        val challenge = ByteArray(20)
        challenge.fill(0x47)
        val harness = Harness(key)
        harness.run(concat(packet(AUTH, 1, 0, challenge), normalSession()))
        assertArrayEquals(challenge, key.token)
        assertEquals(1, key.signs)
        assertEquals(0, key.publications)
        assertEquals(listOf(CNXN, AUTH, OPEN, OKAY), harness.commands())
        assertEquals("2000\n", harness.collected.text)
        assertEquals(2, harness.sentPackets()[1].arg0)
    }

    @Test
    fun newKeyPublishesAfterRejectedSignatureAndWaitsForDeviceTrust() {
        val key = FakeIdentity()
        val harness = Harness(key)
        harness.run(concat(packet(AUTH, 1, 0, ByteArray(20)), packet(AUTH, 1, 0, ByteArray(20)), normalSession()))
        assertEquals(1, key.signs)
        assertEquals(1, key.publications)
        assertEquals(listOf(CNXN, AUTH, AUTH, OPEN, OKAY), harness.commands())
        val published = harness.sentPackets()[2]
        assertEquals(3, published.arg0)
        assertEquals(0, published.arg1)
        assertArrayEquals(key.publicKey(), published.payload)
        assertTrue(harness.log.any { it.startsWith("ADB_AUTH_CONFIRM") })
    }

    @Test
    fun unapprovedKeyAndEofNeverOpenACommand() {
        val harness = Harness(FakeIdentity())
        assertThrows(IOException::class.java) {
            harness.run(concat(packet(AUTH, 1, 0, ByteArray(20)), packet(AUTH, 1, 0, ByteArray(20))))
        }
        assertFalse(harness.commands().contains(OPEN))
    }

    @Test
    fun repeatedAuthAfterPublicationFailsWithoutUnlimitedRetries() {
        val key = FakeIdentity()
        val harness = Harness(key)
        assertThrows(IOException::class.java) {
            harness.run(concat(packet(AUTH, 1, 0, ByteArray(20)),
                packet(AUTH, 1, 0, ByteArray(20)), packet(AUTH, 1, 0, ByteArray(20))))
        }
        assertEquals(1, key.signs)
        assertEquals(1, key.publications)
        assertFalse(harness.commands().contains(OPEN))
    }

    @Test
    fun malformedChallengeNeverGetsSigned() {
        val key = FakeIdentity()
        val harness = Harness(key)
        assertThrows(IOException::class.java) { harness.run(packet(AUTH, 1, 0, ByteArray(19))) }
        assertEquals(0, key.signs)
        assertFalse(harness.commands().contains(OPEN))
    }

    @Test
    fun openRejectedIsAnErrorNotAnEmptySuccessfulCommand() {
        val harness = Harness(null)
        assertThrows(IOException::class.java) { harness.run(concat(connect(), packet(CLSE, 0, 1, EMPTY))) }
    }

    @Test
    fun rejectsWrongStreamAndDataBeforeOpenAck() {
        for (stream in arrayOf(
            concat(connect(), packet(OKAY, 7, 2, EMPTY)),
            concat(connect(), packet(WRTE, 7, 1, byteArrayOf(1))),
            concat(connect(), packet(OKAY, 7, 1, EMPTY), packet(WRTE, 8, 1, byteArrayOf(1))),
            concat(connect(), packet(OKAY, 7, 1, EMPTY), packet(CLSE, 8, 1, EMPTY)))) {
            val harness = Harness(null)
            assertThrows(IOException::class.java) { harness.run(stream) }
            assertEquals(0L, harness.collected.totalBytes)
        }
    }

    @Test
    fun corruptMagicChecksumAndOversizedPayloadFailBeforeOpen() {
        val magic = connect()
        magic[20] = (magic[20].toInt() xor 1).toByte()
        val checksum = connect()
        checksum[16] = (checksum[16].toInt() xor 1).toByte()
        for (stream in arrayOf(magic, checksum, packet(CNXN, 0x01000000, 4096, ByteArray(4097)))) {
            val harness = Harness(null)
            assertThrows(IOException::class.java) { harness.run(stream) }
            assertFalse(harness.commands().contains(OPEN))
        }
    }

    @Test
    fun handlesSingleByteReadsAndNoAuthServer() {
        val harness = Harness(FakeIdentity())
        val fragmented = object : FilterInputStream(ByteArrayInputStream(normalSession())) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, minOf(1, length))
        }
        harness.client.executeProtocol(fragmented, harness.sent, "id -u", harness.collected)
        assertEquals(listOf(CNXN, OPEN, OKAY), harness.commands())
        assertEquals("2000\n", harness.collected.text)
        assertEquals("exec:id -u\u0000", String(harness.sentPackets()[1].payload, Charsets.UTF_8))
    }

    @Test
    fun cancellationBeforeHandshakeWritesNothing() {
        val harness = Harness(FakeIdentity())
        harness.cancelled.set(true)
        assertThrows(IOException::class.java) { harness.run(normalSession()) }
        assertTrue(harness.commands().isEmpty())
    }

    @Test
    fun cancellationAfterConnectNeverExecutesPendingCommand() {
        val cancelled = AtomicBoolean()
        val sent = ByteArrayOutputStream()
        val client = AdbShellClient("127.0.0.1", 5555, 2000, null,
            Consumer { cancelled.set(true) }, cancelled)
        assertThrows(IOException::class.java) {
            client.executeProtocol(ByteArrayInputStream(normalSession()), sent, "id -u",
                AdbShellClient.ShellOutputHandler { _, _ -> })
        }
        assertEquals(24 + "host::\u0000".length, sent.size())
    }

    @Test
    fun consumerFailureIsPropagatedWithoutSuccess() {
        val harness = Harness(null)
        val error = assertThrows(IOException::class.java) {
            harness.client.executeProtocol(ByteArrayInputStream(normalSession()), harness.sent,
                "id -u", AdbShellClient.ShellOutputHandler { _, _ -> throw IOException("sink failed") })
        }
        assertEquals("sink failed", error.message)
        assertEquals(listOf(CNXN, OPEN), harness.commands())
    }

    @Test
    fun oversizedServiceAndIncompatiblePeerNeverGetOpen() {
        val harness = Harness(null)
        assertThrows(IOException::class.java) {
            harness.client.executeProtocol(ByteArrayInputStream(connect()),
                harness.sent, "x".repeat(4096), harness.collected)
        }
        assertFalse(harness.commands().contains(OPEN))
        val incompatible = Harness(null)
        assertThrows(IOException::class.java) { incompatible.run(packet(CNXN, 0x00010000, 4096, EMPTY)) }
        assertFalse(incompatible.commands().contains(OPEN))
    }

    @Test
    fun validatesInputsWithoutOpeningASocket() {
        assertThrows(IllegalArgumentException::class.java) { AdbShellClient("", 5555, 10) }
        assertThrows(IllegalArgumentException::class.java) { AdbShellClient("127.0.0.1", 0, 10) }
        assertThrows(IllegalArgumentException::class.java) { AdbShellClient("127.0.0.1", 5555, 0) }
        val client = AdbShellClient("127.0.0.1", 5555, 10)
        assertThrows(IllegalArgumentException::class.java) {
            client.executeShell("id\u0000reboot", AdbShellClient.ShellOutputHandler { _, _ -> })
        }
        assertThrows(IllegalArgumentException::class.java) { client.executeShell("id", null) }
    }

    @Test
    fun missingAuthenticationIdentityNeverOpensCommand() {
        val harness = Harness(null)
        assertThrows(IOException::class.java) { harness.run(packet(AUTH, 1, 0, ByteArray(20))) }
        assertEquals(listOf(CNXN), harness.commands())
    }

    @Test
    fun socketReadTimeoutIsBoundedByTheWholeHandshakeDeadline() {
        val client = AdbShellClient("127.0.0.1", 5555, 1)
        val stalled = object : InputStream() {
            override fun read(): Int {
                throw SocketTimeoutException("simulated socket poll")
            }
        }
        assertThrows(IOException::class.java) {
            client.executeProtocol(stalled, ByteArrayOutputStream(), "id -u", AdbShellClient.ShellOutputHandler { _, _ -> })
        }
    }

    @Test
    fun partialHeaderOrPayloadNeverCompletesTheCommand() {
        val header = Harness(null)
        assertThrows(IOException::class.java) { header.run(ByteArray(12)) }
        val data = Harness(null)
        val full = normalSession()
        assertThrows(IOException::class.java) { data.run(full.copyOf(full.size - 12)) }
    }

    @Test
    fun shellV2RequiresAdvertisedFeatureAndSeparatesExitFromStdout() {
        val harness = Harness(null)
        val stdout = byteArrayOf(1, 4, 0, 0, 0, 0, 0, 1, 0x65, 3, 1, 0, 0, 0, 0)
        val peer = concat(packet(CNXN, 0x01000000, 4096,
            "device::features=cmd,shell_v2,stat_v2\u0000".toByteArray(Charsets.US_ASCII)),
            packet(OKAY, 7, 1, EMPTY), packet(WRTE, 7, 1, stdout), packet(CLSE, 7, 1, EMPTY))
        val raw = ByteArrayOutputStream()
        harness.client.executeShellV2Protocol(ByteArrayInputStream(peer), harness.sent, "screenrecord -",
            AdbShellClient.ShellOutputHandler { data, length -> raw.write(data!!, 0, length) })
        assertArrayEquals(byteArrayOf(0, 0, 1, 0x65), raw.toByteArray())
        assertEquals("shell,v2,raw:screenrecord -\u0000",
            String(harness.sentPackets()[1].payload, Charsets.UTF_8))
    }

    @Test
    fun missingOrLookalikeShellV2FeatureNeverStartsCapture() {
        for (banner in arrayOf("device::\u0000", "device::features=shell_v2_unsafe\u0000",
            "device::notfeatures=shell_v2\u0000")) {
            val harness = Harness(null)
            val peer = packet(CNXN, 0x01000000, 4096, banner.toByteArray(Charsets.US_ASCII))
            assertThrows(IOException::class.java) {
                harness.client.executeShellV2Protocol(ByteArrayInputStream(peer), harness.sent, "screenrecord -",
                    harness.collected)
            }
            assertFalse(harness.commands().contains(OPEN))
        }
    }

    @Test
    fun shellV2TransportCloseWithoutExitCannotBecomeCaptureSuccess() {
        val harness = Harness(null)
        val peer = concat(packet(CNXN, 0x01000000, 4096,
            "device::features=shell_v2\u0000".toByteArray(Charsets.US_ASCII)),
            packet(OKAY, 7, 1, EMPTY), packet(CLSE, 7, 1, EMPTY))
        assertThrows(IOException::class.java) {
            harness.client.executeShellV2Protocol(ByteArrayInputStream(peer), harness.sent, "screenrecord -",
                harness.collected)
        }
    }

    @Test
    fun dataProgressRenewsIdleDeadlineBeyondOldAbsoluteTimeout() {
        val clock = AtomicLong()
        val client = AdbShellClient("127.0.0.1", 5555, 20000, null,
            Consumer {}, AtomicBoolean(), LongSupplier { clock.get() })
        val peer = ByteArrayOutputStream()
        peer.write(packet(CNXN, 0x01000000, 4096,
            "device::features=shell_v2\u0000".toByteArray(Charsets.US_ASCII)))
        peer.write(packet(OKAY, 7, 1, EMPTY))
        for (index in 0 until 360) {
            peer.write(packet(WRTE, 7, 1, byteArrayOf(1, 1, 0, 0, 0, 42)))
        }
        peer.write(packet(WRTE, 7, 1, byteArrayOf(3, 1, 0, 0, 0, 0)))
        peer.write(packet(CLSE, 7, 1, EMPTY))
        val packets = intArrayOf(0)
        client.executeControlledProtocol(ByteArrayInputStream(peer.toByteArray()), ByteArrayOutputStream(), "owned",
            AdbShellClient.ShellOutputHandler { _, _ ->
                packets[0]++
                clock.addAndGet(5_000_000_000L)
            }, AtomicBoolean())
        assertEquals(360, packets[0])
        assertEquals(1_800_000_000_000L, clock.get())
    }

    @Test
    fun legacyDiagnosticsKeepAbsoluteDeadlineEvenIfPeerKeepsSendingData() {
        val clock = AtomicLong()
        val client = AdbShellClient("127.0.0.1", 5555, 20000, null,
            Consumer {}, AtomicBoolean(), LongSupplier { clock.get() })
        val peer = concat(connect(), packet(OKAY, 7, 1, EMPTY),
            packet(WRTE, 7, 1, byteArrayOf(1)), packet(WRTE, 7, 1, byteArrayOf(1)),
            packet(WRTE, 7, 1, byteArrayOf(1)), packet(WRTE, 7, 1, byteArrayOf(1)),
            packet(WRTE, 7, 1, byteArrayOf(1)), packet(CLSE, 7, 1, EMPTY))
        val consumed = intArrayOf(0)
        assertThrows(IOException::class.java) {
            client.executeProtocol(ByteArrayInputStream(peer), ByteArrayOutputStream(), "diagnostic",
                AdbShellClient.ShellOutputHandler { _, _ ->
                    consumed[0]++
                    clock.addAndGet(5_000_000_000L)
                })
        }
        assertEquals(4, consumed[0])
    }

    @Test
    fun liveStopSendsOnlyFixedStdinOnceAndStillRequiresSuccessfulShellExit() {
        val stop = AtomicBoolean()
        val harness = Harness(null)
        val peer = concat(packet(CNXN, 0x01000000, 4096,
            "device::features=shell_v2\u0000".toByteArray(Charsets.US_ASCII)),
            packet(OKAY, 7, 1, EMPTY), packet(WRTE, 7, 1, byteArrayOf(1, 1, 0, 0, 0, 42)),
            packet(OKAY, 7, 1, EMPTY), packet(WRTE, 7, 1, byteArrayOf(3, 1, 0, 0, 0, 0)),
            packet(CLSE, 7, 1, EMPTY))
        harness.client.executeControlledProtocol(ByteArrayInputStream(peer), harness.sent, "owned",
            AdbShellClient.ShellOutputHandler { _, _ -> stop.set(true) }, stop)
        val stdin = harness.sentPackets().filter { it.command == WRTE }
        assertEquals(1, stdin.size)
        assertEquals(1, stdin[0].arg0)
        assertEquals(7, stdin[0].arg1)
        assertArrayEquals(byteArrayOf(0, 5, 0, 0, 0) + "STOP\n".toByteArray(Charsets.US_ASCII), stdin[0].payload)
    }

    @Test
    fun failedFrameConsumerRequestsOwnedStopAndDrainsTheSameAdbStream() {
        val harness = Harness(null)
        val userStop = AtomicBoolean()
        val consumerCalls = intArrayOf(0)
        val receiverStops = intArrayOf(0)
        val peer = concat(packet(CNXN, 0x01000000, 4096,
            "device::features=shell_v2\u0000".toByteArray(Charsets.US_ASCII)),
            packet(OKAY, 7, 1, EMPTY), packet(WRTE, 7, 1, byteArrayOf(1, 1, 0, 0, 0, 42)),
            packet(OKAY, 7, 1, EMPTY), packet(WRTE, 7, 1, byteArrayOf(1, 1, 0, 0, 0, 43)),
            packet(WRTE, 7, 1, byteArrayOf(2, 2, 0, 0, 0, 'o'.code.toByte(), 'k'.code.toByte())),
            packet(WRTE, 7, 1, byteArrayOf(3, 1, 0, 0, 0, 0)), packet(CLSE, 7, 1, EMPTY))
        val input = ByteArrayInputStream(peer)
        val error = assertThrows(IOException::class.java) {
            harness.client.executeControlledProtocol(input, harness.sent, "owned",
                AdbShellClient.ShellOutputHandler { _, _ ->
                    consumerCalls[0]++
                    throw IOException("MEDIA_SUBMIT rejected")
                }, userStop, Runnable { receiverStops[0]++ })
        }
        assertEquals("MEDIA_SUBMIT rejected", error.message)
        assertEquals(0, input.available())
        assertEquals(1, consumerCalls[0])
        assertEquals(1, receiverStops[0])
        assertFalse(userStop.get())
        assertEquals(1, harness.sentPackets().count { it.command == WRTE })
        assertTrue(harness.log.any { it.startsWith("CAPTURE_STOP_STDIN_SENT") })
    }

    @Test
    fun failedConsumerCannotExtendStopDeadlineWithEndlessOutputAndKeepsOriginalCause() {
        val clock = AtomicLong()
        val failed = AtomicBoolean()
        val client = AdbShellClient("127.0.0.1", 5555, 20000, null,
            Consumer {}, AtomicBoolean(), LongSupplier { clock.get() })
        val peer = ByteArrayOutputStream()
        peer.write(packet(CNXN, 0x01000000, 4096,
            "device::features=shell_v2\u0000".toByteArray(Charsets.US_ASCII)))
        peer.write(packet(OKAY, 7, 1, EMPTY))
        for (index in 0 until 100) {
            peer.write(packet(WRTE, 7, 1, byteArrayOf(1, 1, 0, 0, 0, 42)))
        }
        val input = object : FilterInputStream(ByteArrayInputStream(peer.toByteArray())) {
            override fun read(data: ByteArray, offset: Int, length: Int): Int {
                if (failed.get()) {
                    clock.addAndGet(1_000_000_000L)
                }
                return super.read(data, offset, length)
            }
        }
        val root = IOException("MEDIA_SUBMIT rejected")
        val result = assertThrows(IOException::class.java) {
            client.executeControlledProtocol(input, ByteArrayOutputStream(), "owned",
                AdbShellClient.ShellOutputHandler { _, _ ->
                    failed.set(true)
                    throw root
                }, AtomicBoolean())
        }
        assertEquals(root, result)
        assertTrue(result.suppressed.any { "CAPTURE_STOP_EXIT_UNCONFIRMED" == it.message })
        assertTrue(clock.get() < 20_000_000_000L)
    }

    @Test
    fun stopDrainCannotBeKeptAliveForeverByFurtherStdout() {
        val clock = AtomicLong()
        val stop = AtomicBoolean()
        val client = AdbShellClient("127.0.0.1", 5555, 20000, null,
            Consumer {}, AtomicBoolean(), LongSupplier { clock.get() })
        val peer = ByteArrayOutputStream()
        peer.write(packet(CNXN, 0x01000000, 4096,
            "device::features=shell_v2\u0000".toByteArray(Charsets.US_ASCII)))
        peer.write(packet(OKAY, 7, 1, EMPTY))
        for (index in 0 until 10) {
            peer.write(packet(WRTE, 7, 1, byteArrayOf(1, 1, 0, 0, 0, 42)))
        }
        val error = assertThrows(IOException::class.java) {
            client.executeControlledProtocol(ByteArrayInputStream(peer.toByteArray()), ByteArrayOutputStream(), "owned",
                AdbShellClient.ShellOutputHandler { _, _ -> stop.set(true); clock.addAndGet(5_000_000_000L) }, stop)
        }
        assertEquals("CAPTURE_STOP_EXIT_UNCONFIRMED", error.message)
    }

    @Test
    fun nativeStopHookRunsBeforeStdinAndItsCleanupDoesNotConsumeCaptureDrainBudget() {
        val stop = AtomicBoolean(true)
        val clock = AtomicLong()
        val sent = ByteArrayOutputStream()
        val client = AdbShellClient("127.0.0.1", 5555, 20000, null,
            Consumer {}, AtomicBoolean(), LongSupplier { clock.get() })
        val peer = concat(packet(CNXN, 0x01000000, 4096,
            "device::features=shell_v2\u0000".toByteArray(Charsets.US_ASCII)),
            packet(OKAY, 7, 1, EMPTY), packet(WRTE, 7, 1, byteArrayOf(3, 1, 0, 0, 0, 0)),
            packet(CLSE, 7, 1, EMPTY))
        val called = intArrayOf(0)
        client.executeControlledProtocol(ByteArrayInputStream(peer), sent, "owned",
            AdbShellClient.ShellOutputHandler { _, _ -> }, stop, Runnable {
                called[0]++
                assertFalse(String(sent.toByteArray(), Charsets.US_ASCII).contains("STOP\n"))
                clock.addAndGet(12_000_000_000L)
            })
        assertEquals(1, called[0])
        assertTrue(String(sent.toByteArray(), Charsets.US_ASCII).contains("STOP\n"))
    }

    private class Harness(identity: AdbAuthenticator?) {
        val sent = ByteArrayOutputStream()
        val collected = AdbTextProbe(4096)
        val cancelled = AtomicBoolean()
        val log = ArrayList<String>()
        val client = AdbShellClient("127.0.0.1", 5555, 2000, identity, Consumer { log.add(it) }, cancelled)

        fun run(input: ByteArray) {
            client.executeProtocol(ByteArrayInputStream(input), sent, "id -u", collected)
        }

        fun commands(): List<Int> = sentPackets().map { it.command }

        fun sentPackets(): List<WirePacket> {
            val input = ByteBuffer.wrap(sent.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            val packets = ArrayList<WirePacket>()
            while (input.hasRemaining()) {
                val command = input.getInt()
                val arg0 = input.getInt()
                val arg1 = input.getInt()
                val length = input.getInt()
                val checksum = input.getInt()
                assertEquals(command.inv(), input.getInt())
                val payload = ByteArray(length)
                input.get(payload)
                var actualChecksum = 0
                for (octet in payload) {
                    actualChecksum += octet.toInt() and 0xff
                }
                assertEquals(checksum, actualChecksum)
                packets.add(WirePacket(command, arg0, arg1, payload))
            }
            return packets
        }
    }

    private class WirePacket(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private class FakeIdentity : AdbAuthenticator {
        var signs = 0
        var publications = 0
        var token: ByteArray? = null

        override fun sign(token: ByteArray?): ByteArray {
            signs++
            this.token = token!!.clone()
            return ByteArray(256)
        }

        override fun publicKey(): ByteArray {
            publications++
            return "test-base64-key dashcast@headunit\u0000".toByteArray(Charsets.US_ASCII)
        }
    }

    private companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val WRTE = 0x45545257
        const val CLSE = 0x45534c43
        val EMPTY = ByteArray(0)

        fun connect(): ByteArray = packet(CNXN, 0x01000000, 4096, "device::\u0000".toByteArray(Charsets.US_ASCII))

        fun normalSession(): ByteArray =
            concat(connect(), packet(OKAY, 7, 1, EMPTY),
                packet(WRTE, 7, 1, "2000\n".toByteArray(Charsets.UTF_8)), packet(CLSE, 7, 1, EMPTY))

        fun packet(command: Int, arg0: Int, arg1: Int, payload: ByteArray): ByteArray {
            var checksum = 0
            for (octet in payload) {
                checksum += octet.toInt() and 0xff
            }
            return ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN).putInt(command)
                .putInt(arg0).putInt(arg1).putInt(payload.size).putInt(checksum).putInt(command.inv())
                .put(payload).array()
        }

        fun concat(vararg parts: ByteArray): ByteArray {
            val output = ByteArrayOutputStream()
            for (part in parts) {
                output.write(part, 0, part.size)
            }
            return output.toByteArray()
        }
    }
}
