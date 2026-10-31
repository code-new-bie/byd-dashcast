package com.byd.dashcast.phase1

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent

/** UI request state only. Capture/service owners retain all resource and cleanup authority. */
internal object MirrorWorkspace {
    enum class Stage { PREPARING, STARTING, RUNNING, STOPPING, STOPPED, FAILED }
    data class Request(val generation: Long, val target: ProjectionTarget, val stage: Stage,
        val ticket: String? = null, val error: String? = null)

    @Volatile private var request: Request? = null
    fun current(): Request? = request

    @Synchronized fun begin(context: Context, target: ProjectionTarget, generation: Long) {
        require(target.isWorkspace && generation > 0)
        request = Request(generation, target, Stage.PREPARING)
        open(context)
    }

    fun open(context: Context) {
        context.startActivity(Intent(context, MirrorActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
    }

    @JvmStatic @Synchronized fun clearFinished() {
        if (request?.stage == Stage.STOPPED || request?.stage == Stage.FAILED) { request = null }
    }

    @JvmStatic @Synchronized fun starting(target: ProjectionTarget): Long {
        val old = request ?: return 0
        if (old.target != target || old.stage == Stage.STOPPING || old.stage == Stage.STOPPED) { return 0 }
        request = old.copy(stage = Stage.STARTING, error = null)
        return old.generation
    }

    @JvmStatic @Synchronized fun bind(target: ProjectionTarget, ticket: String) {
        val old = request ?: return
        if (old.target == target && old.stage == Stage.STARTING) { request = old.copy(ticket = ticket) }
    }

    @Synchronized fun fail(generation: Long, message: String) {
        val old = request ?: return
        if (generation == old.generation && old.stage != Stage.STOPPING && old.stage != Stage.STOPPED) {
            request = old.copy(stage = Stage.FAILED, error = message)
        }
    }

    @Synchronized fun stopping() {
        request = request?.copy(stage = Stage.STOPPING)
    }

    @Synchronized fun stopped() {
        request = request?.copy(stage = Stage.STOPPED, ticket = null)
    }

    @JvmStatic @Synchronized fun state(generation: Long, state: ProjectionSessionController.State) {
        val old = request ?: return
        if (generation == 0L || old.generation != generation) { return }
        request = when (state) {
            ProjectionSessionController.State.STREAMING -> old.copy(stage = Stage.RUNNING)
            ProjectionSessionController.State.STOPPING -> old.copy(stage = Stage.STOPPING)
            ProjectionSessionController.State.STOPPED -> old.copy(stage = Stage.STOPPED, ticket = null)
            ProjectionSessionController.State.FAILED, ProjectionSessionController.State.DISCONNECTED,
            ProjectionSessionController.State.RECOVERY_REQUIRED, ProjectionSessionController.State.QUARANTINED ->
                old.copy(stage = Stage.FAILED, error = "投屏暂时中断，请检查连接后重试，或返回首页导出日志。")
            else -> old
        }
    }
}
