package com.byd.dashcast.phase1

/** User-facing actions are separate from the full error and evidence recorded in the journal. */
internal object ProductErrors {
    fun explain(error: Exception): String {
        val text = error.message.orEmpty()
        return when {
            text.contains("EXTERNAL_ONE_APPLICATION_NOT_READY") || text.contains("EXTERNAL_APPLICATION_CHANGED_OR_CLOSED") ->
                "应用已结束外部投屏。可在原应用中重新发送，或从应用列表开始投屏。"
            text.contains("EXTERNAL_") || text.contains("NAMED_WINDOW") ->
                "应用接入暂不可用。请先关闭再开启接入，或从应用列表开始投屏。"
            text.contains("CLEANUP_UNCONFIRMED") || text.contains("HOT_RELEASE_UNCONFIRMED") ->
                "上次投屏尚未完全结束，已停止重试。请重启仪表和应用后再试。"
            text.contains("APP_NATIVE_SECONDARY_VERSION") ->
                "暂不支持此地图版本，请刷新应用列表；仍无法使用时可导出日志反馈。"
            text.contains("APP_NATIVE_SECONDARY_ALREADY_IN_USE") ->
                "地图的仪表画面已在使用，请先结束原投屏再试。"
            text.contains("APP_SINGLE_INSTANCE") || text.contains("APP_NATIVE_SECONDARY") ||
                text.contains("APP_INDEPENDENT") || text.contains("APP_PRIMARY_MIRROR_DISABLED") ->
                "此应用暂时无法建立投屏画面，请尝试其他应用或导出日志反馈。"
            text.contains("AUTH", true) || text.contains("authentication", true) ->
                "请在车机的“允许 USB 调试”弹窗中选择允许，然后重试。"
            text.contains("MATCHING_FIRMWARE") || text.contains("NAVIGATION_LAYOUT_UNCONFIRMED") ||
                text.contains("PEER_RECOVERY_INCOMPATIBLE") ->
                "仪表版本不匹配，请安装与当前应用配套的仪表包。"
            text.contains("PRODUCT_SOURCE_NOT_READY") ->
                "应用画面还未准备好，请先打开应用，稍候再试。"
            text.contains("PEER_RECOVERY_NATIVE_CLEANUP_FAULT") ->
                "仪表未能完成恢复，请重启仪表后再试。"
            text.contains("PEER_RECOVERY") ->
                "仪表正在恢复，稍候点击“重新连接”即可继续。"
            text.contains("DETACHED_RECOVERY_RECONNECT_REQUIRED") ->
                "请检查数据线，连接好后点击“重新连接”。"
            text.contains("DETACHED_RECOVERY") ->
                "连接已断开，仪表正在恢复；连接好后点击“重新连接”。"
            text.contains("NCM_NOT_ENUMERATED") ->
                "尚未连接到仪表，请检查数据线，并开启“仪表投屏连接”。"
            text.contains("USB_PERMISSION") -> "请允许访问仪表 USB 后再试。"
            text.contains("USB_BUSY") || text.contains("SERVICE_FINISHING") || text.contains("SESSION_ACTIVE") ->
                "上次操作还未结束，请稍候再试。"
            text.contains("timeout", true) || text.contains("timed out", true) ->
                "等待连接超时，请检查数据线后重试；仍失败时可导出日志反馈。"
            else -> "操作未完成，请重试；仍失败时可在帮助与反馈中导出日志。"
        }
    }
}
