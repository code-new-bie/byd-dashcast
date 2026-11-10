package com.byd.dashcast.phase1.ncm

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Desktop integration adapter for descriptor/parameter bytes produced by unchanged QNX Thumb code. */
object NcmOfflineHandshakeCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        val configuration = Files.readAllBytes(Path.of(args[0]))
        val parameters = Files.readAllBytes(Path.of(args[1]))
        val trace = ArrayList<String>()
        val io = object : NcmInitialization.Io {
            override fun alternate(setting: Int) {
                trace.add("ALT" + setting)
            }

            override fun control(type: Int, request: Int, value: Int, index: Int, buffer: ByteArray?, timeoutMs: Int): Int {
                if (index != 0 || timeoutMs != 1000 || value != 0) {
                    throw IOException("Wrong interface or timeout/value")
                }
                if (request == 0x80 && type == 0xa1 && buffer!!.size == 28) {
                    System.arraycopy(parameters, 0, buffer, 0, parameters.size)
                    trace.add("GET80")
                    return 28
                }
                if (request == 0x86 && type == 0x21 && buffer!!.size == 4 &&
                    byteArrayOf(0, 64, 0, 0).contentEquals(buffer)) {
                    trace.add("SET86_4B")
                    return 4
                }
                throw IOException("Original QNX driver stalls this class request")
            }
        }
        NcmInitialization.initialize(io, NcmDescriptor.parse(configuration), AtomicBoolean())
        if (trace != listOf("ALT0", "GET80", "SET86_4B", "ALT1")) {
            throw AssertionError(trace)
        }
        println("PASS_ALT0_GET80_SET86_4B_ALT1")
    }
}
