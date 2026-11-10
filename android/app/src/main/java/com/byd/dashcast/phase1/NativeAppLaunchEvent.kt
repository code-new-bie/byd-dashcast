package com.byd.dashcast.phase1

/** OEM /system/etc/event-log-tags 30003 schema, not a generic command/log parser.
 * URI/MIME contents are never recorded. Only a matching native launcher intent is acted upon.
 * @author Codex
 * @date 2026/10/22
 */
internal object NativeAppLaunchEvent {
    private val prefix = Regex("^\\s*(\\d{9,12})\\.(\\d{1,6})\\s+.*?\\b(am_new_intent|am_create_activity)\\s*:\\s*\\[(.*)]\\s*$")

    @JvmStatic
    fun matches(line: String, packageName: String, task: Int, user: Int, beganMillis: Long,
        launcherComponent: String? = null): Boolean {
        if (line.length > 4096 || task < 0 || user < 0) { return false }
        val result = prefix.matchEntire(line) ?: return false
        val seconds = result.groupValues[1].toLongOrNull() ?: return false
        val millis = result.groupValues[2].padEnd(3, '0').take(3).toIntOrNull() ?: return false
        if (seconds * 1000 + millis < beganMillis) { return false }
        val fields = result.groupValues[4].split(',', limit = 9).map { it.trim() }
        if (fields.size != 8 || fields[0].toIntOrNull() != user || (fields[2].toIntOrNull() ?: -1) < 0 ||
            fields[3].substringBefore('/') != packageName || fields[4] != "android.intent.action.MAIN") {
            return false
        }
        // The OEM native icon creates a transient UsbFillActivity and then forwards an
        // action-less intent to MainMap. Recognize that exact verified launcher component,
        // not arbitrary new tasks in the package. Ownership of the map task is checked again
        // by AppTaskHandoff before any migration. No action-less/deep-link intent is accepted.
        val launcher = launcherComponent != null && canonical(fields[3]) == canonical(launcherComponent)
        val adopted = result.groupValues[3] == "am_new_intent" && fields[2].toIntOrNull() == task
        if (!launcher && !adopted) { return false }
        // Native application icons have no URI/MIME. A deep link or internal new-intent
        // delivery is not permission to bring this task onto the primary screen.
        if (fields[5].lowercase() !in setOf("", "null") || fields[6].lowercase() !in setOf("", "null")) { return false }
        val flags = fields[7].toLongOrNull() ?: return false
        return flags in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && flags and 0x10000000L != 0L
    }

    private fun canonical(component: String): String {
        val parts = component.split('/', limit = 2)
        if (parts.size != 2) { return "" }
        return parts[0] + "/" + if (parts[1].startsWith('.')) parts[0] + parts[1] else parts[1]
    }
}
