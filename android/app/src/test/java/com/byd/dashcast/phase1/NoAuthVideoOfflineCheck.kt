package com.byd.dashcast.phase1

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.function.Consumer

/** Actual Java streaming AVC framing -> DCV1; independent Python + ARM checks consume this. */
object NoAuthVideoOfflineCheck {
    @JvmStatic
    fun main(arguments: Array<String>) {
        if (arguments.size != 2) {
            throw IllegalArgumentException("input.h264 output.dcv1")
        }
        val source = Files.readAllBytes(Path.of(arguments[0]))
        Files.newOutputStream(Path.of(arguments[1]), StandardOpenOption.CREATE_NEW).use { output ->
            output.write(NoAuthVideoProtocol.hello())
            output.write(NoAuthVideoProtocol.start())
            var sequence = 0
            val contract = AvcReceiverContract()
            val reader = AnnexBAccessUnitReader { frame, _ ->
                contract.accept(frame, Consumer {})
                val index = ++sequence
                output.write(NoAuthVideoProtocol.frame(frame, index, (index - 1) * 1000L / 30))
            }
            val sizes = intArrayOf(1, 2, 3, 7, 4096, 8191)
            var cursor = 0
            var chunkIndex = 0
            while (cursor < source.size) {
                val end = minOf(source.size, cursor + sizes[chunkIndex++ % sizes.size])
                val chunk = source.copyOfRange(cursor, end)
                reader.onOutput(chunk, chunk.size)
                cursor = end
            }
            reader.finish()
            if (reader.frameCount() != 30 || sequence != 30) {
                throw IllegalStateException("Expected all 30 independent fixture pictures")
            }
            output.write(NoAuthVideoProtocol.heartbeat(1))
            output.write(NoAuthVideoProtocol.stop())
        }
    }
}
