package com.byd.dashcast.phase1

/** Presentation only. Never changes the session, recovery or USB state machine. */
internal enum class ProductOperation { NONE, CONNECT, START, MODE, EXPORT }

internal enum class ProductConnection {
    ABSENT, CONNECTED, PERMISSION, AMBIGUOUS, UNAVAILABLE;

    val observed: Boolean get() = this == CONNECTED || this == PERMISSION
    val description: String get() = when (this) {
        ABSENT -> "仪表未连接"
        CONNECTED -> "仪表已连接"
        PERMISSION -> "等待允许访问仪表"
        AMBIGUOUS -> "请只连接一台仪表"
        UNAVAILABLE -> "暂时无法读取连接状态"
    }
}

internal data class ProductPresentation(
    val title: String,
    val actionLabel: String,
    val action: Action,
    val enabled: Boolean,
    val modeEnabled: Boolean,
    val canChooseApp: Boolean
) {
    enum class Action { START, STOP, CHOOSE, SETUP, NONE }
}

internal object ProductUiModel {
    fun present(
        state: ProjectionSessionController.State,
        operation: ProductOperation,
        busy: Boolean,
        setup: Boolean,
        selected: Boolean,
        allowed: Boolean
    ): ProductPresentation {
        val locked = state == ProjectionSessionController.State.QUARANTINED
        val stopping = state == ProjectionSessionController.State.STOPPING
        val canChoose = !busy && !locked && !stopping
        val modeEnabled = !busy && !locked && !stopping
        fun view(title: String, label: String, action: ProductPresentation.Action, enabled: Boolean = true) =
            ProductPresentation(title, label, action, enabled, modeEnabled, canChoose)

        // An unknown cleanup result must never become a clickable retry after a wording change.
        if (locked) { return view("暂时无法投屏", "需要先恢复连接", ProductPresentation.Action.NONE, false) }
        if (stopping) { return view("正在停止投屏", "正在停止…", ProductPresentation.Action.NONE, false) }
        if (state == ProjectionSessionController.State.STARTING) {
            return view("正在启动投屏", "取消启动", ProductPresentation.Action.STOP)
        }
        if (state == ProjectionSessionController.State.STREAMING) {
            return view("正在投屏", "停止投屏", ProductPresentation.Action.STOP)
        }
        if (busy && operation == ProductOperation.START) {
            return view("正在准备投屏", "取消启动", ProductPresentation.Action.STOP)
        }
        if (busy) {
            val title = when (operation) {
                ProductOperation.CONNECT -> "正在连接车机"
                ProductOperation.MODE -> "正在切换连接方式"
                ProductOperation.EXPORT -> "正在导出日志"
                else -> "正在处理，请稍候"
            }
            return view(title, "请稍候…", ProductPresentation.Action.NONE, false)
        }
        if (!setup) { return view("欢迎使用 Dashcast", "开始连接", ProductPresentation.Action.SETUP) }
        if (!selected || !allowed) {
            return view("选择一个应用开始", "选择应用", ProductPresentation.Action.CHOOSE)
        }
        return when (state) {
            ProjectionSessionController.State.FAILED -> view("上次投屏未完成", "重试投屏", ProductPresentation.Action.START)
            ProjectionSessionController.State.DISCONNECTED,
            ProjectionSessionController.State.RECOVERY_REQUIRED ->
                view("等待重新连接", "重新连接", ProductPresentation.Action.START)
            else -> view("可以开始投屏", "开始投屏", ProductPresentation.Action.START)
        }
    }

    fun appSummary(capability: AppEntryCapability): String = when {
        !capability.allowed -> "暂不可投屏 · 可查看说明"
        capability.nativeSecondary -> "使用地图自带的仪表画面"
        else -> "投屏时，仍可从原应用图标打开操作"
    }

    fun appIssue(capability: AppEntryCapability): String = when {
        capability.allowed -> "可以尝试投屏，显示效果以应用实际支持情况为准。"
        capability.reason.contains("VERSION") -> "暂不支持此应用版本。请更新应用列表，或导出日志反馈。"
        capability.reason.contains("APP_ENTRY_NOT_ASSESSED") -> "尚未读取到此应用的信息，请刷新应用列表。"
        else -> "此应用暂时无法投屏。可以尝试其他应用，或在帮助与反馈中导出日志。"
    }
}
