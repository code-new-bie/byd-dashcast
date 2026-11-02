package com.byd.dashcast.phase1

/** Installed-entry assessment, not a claim of successful vehicle rendering.
 * @author Codex
 * @date 2026/10/24
 */
internal data class AppEntryCapability(val allowed: Boolean, val nativeSecondary: Boolean,
    val reason: String, val evidence: String) {
    val summary: String get() = when {
        !allowed -> "暂不具备独立投屏条件 · ${explanation(reason)}"
        nativeSecondary -> "具备专用副屏入口 · 画面及导航同步以设备结果为准"
        else -> "同实例双端显示 · 原图标进入可操作镜像页"
    }

    companion object {
        val UNASSESSED = AppEntryCapability(false, false, "APP_ENTRY_NOT_ASSESSED", "")

        fun explanation(reason: String): String = when {
            reason.contains("APP_SINGLE_INSTANCE_UNSUPPORTED") -> "当前任务含单实例入口，不能搬走主屏导航"
            reason.contains("APP_NATIVE_SECONDARY_AFFINITY_NOT_INDEPENDENT") -> "副屏与主屏任务未分离"
            reason.contains("APP_NATIVE_SECONDARY_VERSION_UNAVAILABLE") -> "无法读取已安装版本"
            reason.contains("APP_NATIVE_SECONDARY_MODE_UNSUPPORTED") -> "副屏入口的任务模式不支持"
            reason.contains("APP_NATIVE_SECONDARY_ENTRY_MISMATCH") -> "副屏入口与已知能力不符"
            reason.contains("APP_ENTRY_NOT_ASSESSED") -> "请刷新应用能力"
            else -> "入口不可用、受权限限制或尚未适配"
        }
    }
}
