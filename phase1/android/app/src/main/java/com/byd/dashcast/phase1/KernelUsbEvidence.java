package com.byd.dashcast.phase1;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bounded, read-only USB descriptor evidence via authorized ADB, without serials or USB writes.
 * Framework visibility, kernel enumeration and QNX role are different observations.
 *
 * @author Codex
 * @date 2026/10/11
 */
final class KernelUsbEvidence implements AdbShellClient.ShellOutputHandler {
    static final int MAX_NODES = 64;
    static final String COMMAND =
            "if [ ! -d /sys/bus/usb/devices ] || [ ! -r /sys/bus/usb/devices ] "
                    + "|| [ ! -x /sys/bus/usb/devices ]; then "
                    + "printf 'SYSFS_STATUS=UNAVAILABLE\\n'; exit 0; fi; "
                    + "printf 'SYSFS_STATUS=READABLE\\n'; dashcast_usb_count=0; "
                    + "for dashcast_usb_node in /sys/bus/usb/devices/*; do "
                    + "[ -d \"$dashcast_usb_node\" ] || continue; "
                    + "if [ \"$dashcast_usb_count\" -ge 64 ]; then "
                    + "printf 'SYSFS_SCAN_TRUNCATED=1\\n'; break; fi; "
                    + "dashcast_usb_count=$((dashcast_usb_count + 1)); "
                    + "printf 'USB_NODE=%s\\n' \"${dashcast_usb_node##*/}\"; "
                    + "for dashcast_usb_field in busnum devnum idVendor idProduct bDeviceClass "
                    + "bDeviceSubClass bDeviceProtocol speed bNumInterfaces "
                    + "bInterfaceClass bInterfaceSubClass bInterfaceProtocol; do "
                    + "[ -f \"$dashcast_usb_node/$dashcast_usb_field\" ] || continue; "
                    + "if read -r dashcast_usb_value < \"$dashcast_usb_node/$dashcast_usb_field\"; then "
                    + "printf '%s=%s\\n' \"$dashcast_usb_field\" \"$dashcast_usb_value\"; "
                    + "else printf 'READ_FAILED=%s\\n' \"$dashcast_usb_field\"; fi; done; done; "
                    + "printf 'SYSFS_SCAN_DONE=1\\n'";
    private static final int MAX_LINE_CHARS = 128;
    private static final Pattern NODE = Pattern.compile("(?:usb[0-9]+|[0-9]+-[0-9.]+(?::[0-9]+\\.[0-9]+)?)");
    private static final Pattern ID_VALUE = Pattern.compile("[0-9a-fA-F]{4}");
    private static final Pattern BYTE_VALUE = Pattern.compile("[0-9a-fA-F]{2}");
    private static final Pattern INTEGER_VALUE = Pattern.compile("[0-9]{1,6}");
    private static final Pattern SPEED_VALUE = Pattern.compile("[0-9]{1,6}(?:\\.[0-9]{1,3})?");
    private static final Pattern FIELD = Pattern.compile(
            "busnum|devnum|idVendor|idProduct|bDeviceClass|bDeviceSubClass|bDeviceProtocol|speed"
                    + "|bNumInterfaces|bInterfaceClass|bInterfaceSubClass|bInterfaceProtocol");
    private final Map<String, Map<String, String>> nodes = new LinkedHashMap<>(MAX_NODES);
    private final StringBuilder line = new StringBuilder(MAX_LINE_CHARS);
    private String currentNode;
    private boolean readable;
    private boolean finished;
    private boolean failed;
    private boolean lineOverflow;
    private Boolean commandSuccess;

    @Override
    public void onOutput(byte[] data, int length) throws IOException {
        if (data == null || length < 0 || length > data.length) {
            throw new IllegalArgumentException("Invalid USB sysfs output chunk");
        }
        for (int index = 0; index < length; index++) {
            int value = data[index] & 0xff;
            if (value == '\n') {
                consumeLine();
            } else if (line.length() < MAX_LINE_CHARS) {
                line.append((char) value);
            } else {
                lineOverflow = true;
            }
        }
    }

    String summary(int frameworkDevices, int frameworkNcmDevices) {
        if (frameworkDevices < 0 || frameworkNcmDevices < 0) {
            throw new IllegalArgumentException("Invalid framework USB device count");
        }
        consumeLine();
        int peripheralDevices = 0;
        int rootHubs = 0;
        int ncmInterfaces = 0;
        boolean incompleteNode = false;
        for (Map.Entry<String, Map<String, String>> entry : nodes.entrySet()) {
            String name = entry.getKey();
            Map<String, String> fields = entry.getValue();
            if (name.startsWith("usb")) {
                rootHubs++;
            } else if (name.contains(":")) {
                incompleteNode |= !fields.containsKey("bInterfaceClass")
                        || !fields.containsKey("bInterfaceSubClass");
                if ("02".equals(fields.get("bInterfaceClass"))
                        && "0d".equals(fields.get("bInterfaceSubClass"))) {
                    ncmInterfaces++;
                }
            } else {
                peripheralDevices++;
                incompleteNode |= !fields.containsKey("idVendor") || !fields.containsKey("idProduct")
                        || !fields.containsKey("bDeviceClass") || !fields.containsKey("devnum");
            }
        }
        boolean complete = readable && finished && Boolean.TRUE.equals(commandSuccess)
                && !failed && !incompleteNode;
        String layer;
        if (frameworkNcmDevices > 0) {
            layer = "FRAMEWORK_NCM_VISIBLE";
        } else if (!complete) {
            layer = "KERNEL_ENUMERATION_UNKNOWN";
        } else if (ncmInterfaces > 0) {
            layer = "KERNEL_NCM_FRAMEWORK_NOT_VISIBLE";
        } else if (peripheralDevices > 0) {
            layer = "KERNEL_OTHER_USB_NO_NCM";
        } else {
            layer = "KERNEL_NO_PERIPHERAL";
        }
        return "USB_ENUM_LAYER=" + layer + " framework_devices=" + frameworkDevices
                + " framework_ncm=" + frameworkNcmDevices + " kernel_scan_complete=" + complete
                + " kernel_root_hubs=" + rootHubs + " kernel_peripherals=" + peripheralDevices
                + " kernel_ncm_interfaces=" + ncmInterfaces;
    }

    String snapshot(int frameworkDevices, int frameworkNcmDevices) {
        StringBuilder result = new StringBuilder(4096);
        result.append(summary(frameworkDevices, frameworkNcmDevices)).append('\n');
        result.append("Read-only head-unit sysfs descriptors; NOT QNX role or target identity proof.\n");
        for (Map.Entry<String, Map<String, String>> entry : nodes.entrySet()) {
            result.append("USB_NODE=").append(entry.getKey()).append('\n');
            for (Map.Entry<String, String> field : entry.getValue().entrySet()) {
                result.append(field.getKey()).append('=').append(field.getValue()).append('\n');
            }
        }
        return result.toString();
    }

    private void consumeLine() {
        if (lineOverflow) {
            failed = true;
        } else if (line.length() > 0) {
            parseLine(line.toString().trim());
        }
        line.setLength(0);
        lineOverflow = false;
    }

    private void parseLine(String text) {
        if (text.startsWith("DASHCAST_EXIT_CODE=")) {
            commandSuccess = "DASHCAST_EXIT_CODE=0".equals(text);
        } else if ("SYSFS_STATUS=READABLE".equals(text)) {
            readable = true;
        } else if ("SYSFS_SCAN_DONE=1".equals(text)) {
            finished = true;
        } else if (text.startsWith("SYSFS_STATUS=") || text.startsWith("SYSFS_SCAN_TRUNCATED=")
                || text.startsWith("READ_FAILED=")) {
            failed = true;
        } else if (text.startsWith("USB_NODE=")) {
            String name = text.substring("USB_NODE=".length());
            currentNode = null;
            if (NODE.matcher(name).matches() && nodes.size() < MAX_NODES && !nodes.containsKey(name)) {
                currentNode = name;
                nodes.put(name, new LinkedHashMap<>(12));
            } else {
                failed = true;
            }
        } else {
            parseField(text);
        }
    }

    private void parseField(String text) {
        int delimiter = text.indexOf('=');
        if (delimiter <= 0 || currentNode == null) {
            return;
        }
        String name = text.substring(0, delimiter);
        if (!FIELD.matcher(name).matches()) {
            return;
        }
        String value = text.substring(delimiter + 1);
        Map<String, String> fields = nodes.get(currentNode);
        if (!validValue(name, value) || fields.containsKey(name)) {
            failed = true;
            return;
        }
        fields.put(name, value.toLowerCase(Locale.US));
    }

    private static boolean validValue(String name, String value) {
        if ("idVendor".equals(name) || "idProduct".equals(name)) {
            return ID_VALUE.matcher(value).matches();
        }
        if (name.startsWith("bDevice") || name.startsWith("bInterface")) {
            return BYTE_VALUE.matcher(value).matches();
        }
        if ("speed".equals(name)) {
            return SPEED_VALUE.matcher(value).matches();
        }
        return INTEGER_VALUE.matcher(value).matches();
    }
}
