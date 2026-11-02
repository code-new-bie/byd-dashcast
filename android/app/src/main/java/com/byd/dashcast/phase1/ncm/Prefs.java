package com.byd.dashcast.phase1.ncm;

/**
 * Fixed, inspected reference NCM profile; not system network preferences.
 * @author Codex
 * @date 2026/10/10
 */
public final class Prefs {
    public static final String DEF_MAC = "02:00:00:00:07:02";
    public static final String DEF_PEER_MAC = "02:00:00:00:07:01";
    public static final String DEF_LOCAL_IP = "192.168.77.2";
    public static final String DEF_PEER_IP = "192.168.77.1";
    public static final String HARD_MAC = DEF_MAC;
    public static final String HARD_IP = DEF_LOCAL_IP;

    private Prefs() {
    }
}
