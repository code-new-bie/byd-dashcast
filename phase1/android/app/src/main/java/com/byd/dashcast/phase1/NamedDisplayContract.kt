package com.byd.dashcast.phase1

import android.content.Context
import java.io.IOException

/** Public display-name contract; no Binder/HTTP control endpoint is exposed. */
internal object NamedDisplayContract {
    const val NAME = "fission_dashcast_meter"
    fun matchesMeter(name: String): Boolean = name.startsWith("fission_") || name == "叠加视图 #1"
    /** app_process has no Activity display Context; read structured DisplayInfo directly. */
    @JvmStatic fun names(): Map<Int, String> {
        val type = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val manager = type.getMethod("getInstance").invoke(null)
        val ids = type.getMethod("getDisplayIds").invoke(manager) as? IntArray
            ?: throw IOException("NAMED_DISPLAY_IDS_UNAVAILABLE")
        if (ids.size > 64 || ids.any { it < 0 } || ids.distinct().size != ids.size) {
            throw IOException("NAMED_DISPLAY_IDS_LIMIT")
        }
        val info = type.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
        return ids.asSequence().mapNotNull { id ->
            val value = info.invoke(manager, id) ?: return@mapNotNull null
            val name = value.javaClass.getField("name").get(value) as? String
                ?: throw IOException("NAMED_DISPLAY_NAME_MISSING")
            if (name.length > 256) { throw IOException("NAMED_DISPLAY_NAME_LIMIT") }
            id to name
        }.toMap()
    }
    @JvmStatic fun requireAvailable(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (names().values.any { matchesMeter(it) }) {
            throw IOException("METER_DISPLAY_CONFLICT: 已有仪表显示屏，请先结束其他投屏服务")
        }
    }
}

/** Frozen Android-10 dumpsys field contract. Titles, content and text are never retained/logged. */
internal object NamedWindowPolicy {
    const val MAX_BYTES = 262144
    private val display = Regex("\\bmDisplayId=(\\d+)")
    private val uid = Regex("\\bmOwnerUid=(\\d+)")
    private val visibility = Regex("\\bmViewVisibility=(0x[0-9a-fA-F]+|\\d+)")
    fun owners(text: String, target: Int, ownUid: Int, user: Int): Set<Int> {
        require(text.length <= MAX_BYTES && target > 0 && user >= 0)
        val windows = text.lineSequence().fold(mutableListOf<StringBuilder>()) { blocks, line ->
            if (line.trimStart().startsWith("Window #")) { blocks.add(StringBuilder()) }
            if (blocks.size > 256) { throw IOException("NAMED_WINDOW_LIMIT") }
            blocks.lastOrNull()?.append(line)?.append('\n')
            blocks
        }
        if (windows.isEmpty()) { throw IOException("NAMED_WINDOW_CONTRACT_UNAVAILABLE") }
        return windows.mapNotNull { raw ->
            val s = raw.toString()
            if (!s.contains("mHasSurface=true")) { return@mapNotNull null }
            val d = display.find(s)?.groupValues?.get(1)?.toIntOrNull()
                ?: throw IOException("NAMED_WINDOW_DISPLAY_MISSING")
            if (d != target) { return@mapNotNull null }
            val v = visibility.find(s)?.groupValues?.get(1)
                ?: throw IOException("NAMED_WINDOW_VISIBILITY_MISSING")
            if (v != "0x0" && v != "0") { return@mapNotNull null }
            val owner = uid.find(s)?.groupValues?.get(1)?.toIntOrNull()
                ?: throw IOException("NAMED_WINDOW_OWNER_MISSING")
            if (owner % 100000 < 10000) { return@mapNotNull null }
            if (owner / 100000 != user) { throw IOException("NAMED_WINDOW_FOREIGN_USER") }
            owner.takeIf { it != ownUid && it % 100000 >= 10000 }
        }.toSet()
    }
}
