package com.byd.dashcast.phase1;

/**
 * Fixed, binary-verified OEM navigation input. Feature and KEY_TYPE are not raw CAN arbitration IDs.
 * @author Codex
 * @date 2026/10/10
 */
public final class NavigationUsbProtocol {
    public static final String PACKAGE = "com.example.amapservice";
    public static final String SERVICE = PACKAGE + ".AmapService";
    public static final String ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";
    public static final int KEY_TYPE = 10001;
    public static final int NAVIGATION_TYPE = 0;
    public static final int NEXT_PATH_FEATURE = 1140461576;
    public static final String DEX_SHA256 = "0e842c90b4dc661042010c69a582c267a7e97c8c36b33f31b7b4f431babfc415";
    public static final String START_SERVICE = "am startservice --user current -n " + PACKAGE + "/" + SERVICE;
    public static final String RECEIVERS = "dumpsys activity broadcasts " + PACKAGE;

    private NavigationUsbProtocol() {}

    /** @param command enum-only command; @return one package-scoped OEM input, never an arbitrary shell/CAN payload */
    public static String broadcastCommand(InstrumentUsbCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("A fixed USB command is required");
        }
        return "am broadcast --user current -p " + PACKAGE + " -a " + ACTION
                + " --ei KEY_TYPE " + KEY_TYPE + " --ei TYPE " + NAVIGATION_TYPE
                + " --es NEXT_ROAD_NAME " + command.commandText();
    }

    /**
     * @param hash observed service code
     * @param feature observed framework field
     * @throws java.io.IOException on a mismatched profile
     */
    public static void verify(String hash, int feature) throws java.io.IOException {
        if (!DEX_SHA256.equals(hash) || feature != NEXT_PATH_FEATURE) {
            throw new java.io.IOException("原厂导航中继版本未匹配；未发送 USB 命令。dex=" + hash + " feature=" + feature);
        }
    }
}
