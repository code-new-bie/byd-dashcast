package com.byd.dashcast.phase1

import android.content.Context
import java.io.IOException

/** Public display-name contract; no Binder/HTTP control endpoint is exposed. */
internal object NamedDisplayContract {
    const val NAME = "fission_dashcast_meter"
    const val NATIVE_NAME = "fission_dashcast_native"
    /** The map SDK matches fission_ prefixes. Keep that contract without occupying standby's exact name. */
    @JvmStatic fun nativeName(existing: Collection<String>): String {
        if (existing.any { matchesMeter(it) && it != NAME }) {
            throw IOException("METER_DISPLAY_CONFLICT: 已有其他仪表显示屏，请先结束其他投屏服务")
        }
        return NATIVE_NAME
    }
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
    @JvmStatic @JvmOverloads fun requireAvailable(@Suppress("UNUSED_PARAMETER") context: Context,
            allowNative: Boolean = false) {
        if (!standbyAvailable(names().values,allowNative)) {
            throw IOException("METER_DISPLAY_CONFLICT: 已有仪表显示屏，请先结束其他投屏服务")
        }
    }
    fun standbyAvailable(existing: Collection<String>, allowNative: Boolean): Boolean =
        existing.none { matchesMeter(it) && !(allowNative && it == NATIVE_NAME) }
}

/** Frozen Android-10 dumpsys field contract. Titles, content and text are never retained/logged. */
internal object NamedWindowPolicy {
    const val MAX_BYTES = 262144
    private val display = Regex("\\bmDisplayId=(\\d+)")
    private val uid = Regex("\\bmOwnerUid=(\\d+)")
    private val visibility = Regex("\\bmViewVisibility=(0x[0-9a-fA-F]+|\\d+)")
    private val windowHeader = Regex("^\\s*Window #\\d+ Window\\{.*")
    private val displaySection = Regex("^\\s*Display: mDisplayId=(\\d+)\\s*$")
    /** Match focus inside the physical display section, not a MeterActivity on a secondary display. */
    fun mainMapFocused(text: String, user: Int): Boolean {
        require(text.length <= MAX_BYTES && user >= 0)
        var main = false
        val focusedMap=Regex("^\\s*mCurrentFocus=Window\\{[a-fA-F0-9]+ u" + user +
            " com\\.byd\\.automap/[a-zA-Z0-9_.$]+\\}\\s*$")
        for (line in text.lineSequence()) {
            val section = displaySection.matchEntire(line)
            if (section != null) { main = section.groupValues[1] == "0"; continue }
            if (line.startsWith("WINDOW MANAGER ")) { main = false }
            if (main && focusedMap.matches(line)) { return true }
        }
        return false
    }
    fun owners(text: String, target: Int, ownUid: Int, user: Int): Set<Int> {
        require(text.length <= MAX_BYTES && target > 0 && user >= 0)
        val windows = text.lineSequence().fold(mutableListOf<StringBuilder>()) { blocks, line ->
            if (windowHeader.matches(line)) { blocks.add(StringBuilder()) }
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
