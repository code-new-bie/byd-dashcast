package com.byd.dashcast.phase1

import java.io.IOException

/**
 * Fixed, binary-verified OEM navigation input. Feature and KEY_TYPE are not raw CAN arbitration IDs.
 * @author Codex
 * @date 2026/10/10
 */
object NavigationUsbProtocol {
    const val PACKAGE = "com.example.amapservice"
    const val SERVICE = "$PACKAGE.AmapService"
    const val ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
    const val KEY_TYPE = 10001
    const val NAVIGATION_TYPE = 0
    const val NEXT_PATH_FEATURE = 1140461576
    const val DEX_SHA256 = "0e842c90b4dc661042010c69a582c267a7e97c8c36b33f31b7b4f431babfc415"
    const val START_SERVICE = "am startservice --user current -n $PACKAGE/$SERVICE"
    const val RECEIVERS = "dumpsys activity broadcasts $PACKAGE"

    /** @param command enum-only command; @return one package-scoped OEM input, never an arbitrary shell/CAN payload */
    @JvmStatic
    fun broadcastCommand(command: InstrumentUsbCommand?): String {
        if (command == null) {
            throw IllegalArgumentException("A fixed USB command is required")
        }
        return "am broadcast --user current -p " + PACKAGE + " -a " + ACTION +
            " --ei KEY_TYPE " + KEY_TYPE + " --ei TYPE " + NAVIGATION_TYPE +
            " --es NEXT_ROAD_NAME " + command.commandText()
    }

    /**
     * @param hash observed service code
     * @param feature observed framework field
     * @throws IOException on a mismatched profile
     */
    @JvmStatic
    @Throws(IOException::class)
    fun verify(hash: String?, feature: Int) {
        if (DEX_SHA256 != hash || feature != NEXT_PATH_FEATURE) {
            throw IOException("原厂导航中继版本未匹配；未发送 USB 命令。dex=$hash feature=$feature")
        }
    }
}
