package com.byd.dashcast.phase1

/**
 * Whitelisted commands understood by dashcast-usb on the instrument; not raw CAN IDs.
 *
 * @author Codex
 * @date 2026/10/10
 */
enum class InstrumentUsbCommand(private val text: String) {
    /** Restore Host after owned video/USB cleanup; product supports ordered logical detach with cable attached. */
    HOST("SCMD_USBMODE1"),

    /** Switch the instrument to its NCM device stack. */
    NCM("SCMD_USBMODE2");

    /** @return UTF-16LE without BOM, as required by the framework next-road-name property */
    fun payload(): ByteArray = text.toByteArray(Charsets.UTF_16LE)

    /** @return readable command name */
    fun commandText(): String = text
}
