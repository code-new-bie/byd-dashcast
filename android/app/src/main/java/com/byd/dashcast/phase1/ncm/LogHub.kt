package com.byd.dashcast.phase1.ncm

import java.util.ArrayDeque

/**
 * Bounded, synchronized diagnostic log for the vendored NCM core, usable in JVM tests.
 * @author Codex
 * @date 2026/10/10
 */
object LogHub {
    private const val CAPACITY = 800
    private val lines = ArrayDeque<String>(CAPACITY)

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun d(tag: String, message: String) {
        // Packet-level debug is disabled: no frame payloads or token values are logged.
    }

    @JvmStatic
    fun i(tag: String, message: String) {
        write("I", tag, message)
    }

    @JvmStatic
    fun w(tag: String, message: String) {
        write("W", tag, message)
    }

    @JvmStatic
    fun e(tag: String, message: String) {
        write("E", tag, message)
    }

    @JvmStatic
    fun banner(title: String) {
        write("I", "phase", title)
    }

    @JvmStatic
    fun throwable(tag: String, message: String, error: Throwable) {
        write("E", tag, message + " | " + error.javaClass.simpleName + ": " + error.message)
    }

    @JvmStatic
    @Synchronized
    fun snapshot(): String {
        val result = StringBuilder()
        for (line in lines) {
            result.append(line).append('\n')
        }
        return result.toString()
    }

    @JvmStatic
    @Synchronized
    fun clear() {
        lines.clear()
    }

    @Synchronized
    private fun write(level: String, tag: String, message: String) {
        if (lines.size >= CAPACITY) {
            lines.removeFirst()
        }
        lines.addLast(System.currentTimeMillis().toString() + " " + level + "/" + tag + " " + message)
    }
}
