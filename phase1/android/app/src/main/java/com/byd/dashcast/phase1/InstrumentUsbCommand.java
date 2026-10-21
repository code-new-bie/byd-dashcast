package com.byd.dashcast.phase1;

import java.nio.charset.StandardCharsets;

/**
 * Whitelisted commands implemented by the supplied reference USB receiver; not raw CAN IDs.
 *
 * @author Codex
 * @date 2026/10/10
 */
public enum InstrumentUsbCommand {
    /** Restore Host after owned video/USB cleanup; product supports ordered logical detach with cable attached. */
    HOST("SCMD_USBMODE1"),
    /** Switch instrument to the reference NCM Device stack. */
    NCM("SCMD_USBMODE2");

    private final String text;

    InstrumentUsbCommand(String text) {
        this.text = text;
    }

    /** @return UTF-16LE without BOM, as required by the framework next-road-name property */
    public byte[] payload() {
        return text.getBytes(StandardCharsets.UTF_16LE);
    }

    /** @return readable command name */
    public String commandText() {
        return text;
    }
}
