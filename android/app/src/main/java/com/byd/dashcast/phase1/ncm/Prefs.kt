package com.byd.dashcast.phase1.ncm

/**
 * Fixed, inspected instrument NCM profile; not system network preferences.
 * @author Codex
 * @date 2026/10/10
 */
object Prefs {
    const val DEF_MAC = "02:00:00:00:07:02"
    const val DEF_PEER_MAC = "02:00:00:00:07:01"
    const val DEF_LOCAL_IP = "192.168.77.2"
    const val DEF_PEER_IP = "192.168.77.1"
    const val HARD_MAC = DEF_MAC
    const val HARD_IP = DEF_LOCAL_IP
}
