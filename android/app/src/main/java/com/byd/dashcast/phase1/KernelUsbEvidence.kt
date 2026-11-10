package com.byd.dashcast.phase1

import java.io.IOException
import java.util.Locale
import java.util.regex.Pattern

/**
 * Bounded, read-only USB descriptor evidence via authorized ADB, without serials or USB writes.
 * Framework visibility, kernel enumeration and QNX role are different observations.
 *
 * @author Codex
 * @date 2026/10/11
 */
internal class KernelUsbEvidence : AdbShellClient.ShellOutputHandler {
    private val nodes = LinkedHashMap<String, MutableMap<String, String>>(MAX_NODES)
    private val line = StringBuilder(MAX_LINE_CHARS)
    private var currentNode: String? = null
    private var readable = false
    private var finished = false
    private var failed = false
    private var lineOverflow = false
    private var commandSuccess: Boolean? = null

    @Throws(IOException::class)
    override fun onOutput(data: ByteArray?, length: Int) {
        if (data == null || length < 0 || length > data.size) {
            throw IllegalArgumentException("Invalid USB sysfs output chunk")
        }
        for (index in 0 until length) {
            val value = data[index].toInt() and 0xff
            if (value == '\n'.code) {
                consumeLine()
            } else if (line.length < MAX_LINE_CHARS) {
                line.append(value.toChar())
            } else {
                lineOverflow = true
            }
        }
    }

    fun summary(frameworkDevices: Int, frameworkNcmDevices: Int): String {
        if (frameworkDevices < 0 || frameworkNcmDevices < 0) {
            throw IllegalArgumentException("Invalid framework USB device count")
        }
        consumeLine()
        var peripheralDevices = 0
        var rootHubs = 0
        var ncmInterfaces = 0
        var incompleteNode = false
        for ((name, fields) in nodes) {
            if (name.startsWith("usb")) {
                rootHubs++
            } else if (name.contains(":")) {
                incompleteNode = incompleteNode or
                    (!fields.containsKey("bInterfaceClass") || !fields.containsKey("bInterfaceSubClass"))
                if ("02" == fields["bInterfaceClass"] && "0d" == fields["bInterfaceSubClass"]) {
                    ncmInterfaces++
                }
            } else {
                peripheralDevices++
                incompleteNode = incompleteNode or (!fields.containsKey("idVendor") || !fields.containsKey("idProduct") ||
                    !fields.containsKey("bDeviceClass") || !fields.containsKey("devnum"))
            }
        }
        val complete = readable && finished && commandSuccess == true && !failed && !incompleteNode
        val layer = when {
            frameworkNcmDevices > 0 -> "FRAMEWORK_NCM_VISIBLE"
            !complete -> "KERNEL_ENUMERATION_UNKNOWN"
            ncmInterfaces > 0 -> "KERNEL_NCM_FRAMEWORK_NOT_VISIBLE"
            peripheralDevices > 0 -> "KERNEL_OTHER_USB_NO_NCM"
            else -> "KERNEL_NO_PERIPHERAL"
        }
        return "USB_ENUM_LAYER=" + layer + " framework_devices=" + frameworkDevices +
            " framework_ncm=" + frameworkNcmDevices + " kernel_scan_complete=" + complete +
            " kernel_root_hubs=" + rootHubs + " kernel_peripherals=" + peripheralDevices +
            " kernel_ncm_interfaces=" + ncmInterfaces
    }

    fun snapshot(frameworkDevices: Int, frameworkNcmDevices: Int): String {
        val result = StringBuilder(4096)
        result.append(summary(frameworkDevices, frameworkNcmDevices)).append('\n')
        result.append("Read-only head-unit sysfs descriptors; NOT QNX role or target identity proof.\n")
        for ((name, fields) in nodes) {
            result.append("USB_NODE=").append(name).append('\n')
            for ((field, value) in fields) {
                result.append(field).append('=').append(value).append('\n')
            }
        }
        return result.toString()
    }

    private fun consumeLine() {
        if (lineOverflow) {
            failed = true
        } else if (line.isNotEmpty()) {
            parseLine(line.toString().trim())
        }
        line.setLength(0)
        lineOverflow = false
    }

    private fun parseLine(text: String) {
        if (text.startsWith("DASHCAST_EXIT_CODE=")) {
            commandSuccess = "DASHCAST_EXIT_CODE=0" == text
        } else if ("SYSFS_STATUS=READABLE" == text) {
            readable = true
        } else if ("SYSFS_SCAN_DONE=1" == text) {
            finished = true
        } else if (text.startsWith("SYSFS_STATUS=") || text.startsWith("SYSFS_SCAN_TRUNCATED=") ||
            text.startsWith("READ_FAILED=")) {
            failed = true
        } else if (text.startsWith("USB_NODE=")) {
            val name = text.substring("USB_NODE=".length)
            currentNode = null
            if (NODE.matcher(name).matches() && nodes.size < MAX_NODES && !nodes.containsKey(name)) {
                currentNode = name
                nodes[name] = LinkedHashMap(12)
            } else {
                failed = true
            }
        } else {
            parseField(text)
        }
    }

    private fun parseField(text: String) {
        val delimiter = text.indexOf('=')
        val node = currentNode
        if (delimiter <= 0 || node == null) {
            return
        }
        val name = text.substring(0, delimiter)
        if (!FIELD.matcher(name).matches()) {
            return
        }
        val value = text.substring(delimiter + 1)
        val fields = nodes.getValue(node)
        if (!validValue(name, value) || fields.containsKey(name)) {
            failed = true
            return
        }
        fields[name] = value.lowercase(Locale.US)
    }

    companion object {
        const val MAX_NODES = 64

        // Shell variables are escaped (\$) so Kotlin does not read them as string templates.
        const val COMMAND =
            "if [ ! -d /sys/bus/usb/devices ] || [ ! -r /sys/bus/usb/devices ] " +
                "|| [ ! -x /sys/bus/usb/devices ]; then " +
                "printf 'SYSFS_STATUS=UNAVAILABLE\\n'; exit 0; fi; " +
                "printf 'SYSFS_STATUS=READABLE\\n'; dashcast_usb_count=0; " +
                "for dashcast_usb_node in /sys/bus/usb/devices/*; do " +
                "[ -d \"\$dashcast_usb_node\" ] || continue; " +
                "if [ \"\$dashcast_usb_count\" -ge 64 ]; then " +
                "printf 'SYSFS_SCAN_TRUNCATED=1\\n'; break; fi; " +
                "dashcast_usb_count=\$((dashcast_usb_count + 1)); " +
                "printf 'USB_NODE=%s\\n' \"\${dashcast_usb_node##*/}\"; " +
                "for dashcast_usb_field in busnum devnum idVendor idProduct bDeviceClass " +
                "bDeviceSubClass bDeviceProtocol speed bNumInterfaces " +
                "bInterfaceClass bInterfaceSubClass bInterfaceProtocol; do " +
                "[ -f \"\$dashcast_usb_node/\$dashcast_usb_field\" ] || continue; " +
                "if read -r dashcast_usb_value < \"\$dashcast_usb_node/\$dashcast_usb_field\"; then " +
                "printf '%s=%s\\n' \"\$dashcast_usb_field\" \"\$dashcast_usb_value\"; " +
                "else printf 'READ_FAILED=%s\\n' \"\$dashcast_usb_field\"; fi; done; done; " +
                "printf 'SYSFS_SCAN_DONE=1\\n'"
        private const val MAX_LINE_CHARS = 128
        private val NODE = Pattern.compile("(?:usb[0-9]+|[0-9]+-[0-9.]+(?::[0-9]+\\.[0-9]+)?)")
        private val ID_VALUE = Pattern.compile("[0-9a-fA-F]{4}")
        private val BYTE_VALUE = Pattern.compile("[0-9a-fA-F]{2}")
        private val INTEGER_VALUE = Pattern.compile("[0-9]{1,6}")
        private val SPEED_VALUE = Pattern.compile("[0-9]{1,6}(?:\\.[0-9]{1,3})?")
        private val FIELD = Pattern.compile(
            "busnum|devnum|idVendor|idProduct|bDeviceClass|bDeviceSubClass|bDeviceProtocol|speed" +
                "|bNumInterfaces|bInterfaceClass|bInterfaceSubClass|bInterfaceProtocol")

        private fun validValue(name: String, value: String): Boolean = when {
            "idVendor" == name || "idProduct" == name -> ID_VALUE.matcher(value).matches()
            name.startsWith("bDevice") || name.startsWith("bInterface") -> BYTE_VALUE.matcher(value).matches()
            "speed" == name -> SPEED_VALUE.matcher(value).matches()
            else -> INTEGER_VALUE.matcher(value).matches()
        }
    }
}
