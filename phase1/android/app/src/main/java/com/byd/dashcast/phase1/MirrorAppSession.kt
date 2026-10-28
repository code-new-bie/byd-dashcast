package com.byd.dashcast.phase1

import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.os.Bundle
import android.view.Surface
import java.io.IOException

/** Hosts a business task, not a copy of its account or the physical primary display.
 * @author Codex
 * @date 2026/10/25
 */
internal class MirrorAppSession @JvmOverloads constructor(surface: Surface, entry: SingleAppEntry.Entry, width: Int, height: Int,
    ticket: String, private val renderer: CenterFrameRenderer,
    density: Int = ProjectionDisplayProfile.densityDpi(width, height)) : RemoteCaptureSession {
    private val context = RemoteDisplaySession.shellContext()
    private val access = DuplexTaskAccess()
    private val exact = AndroidTaskAccess()
    private val user = access.user()
    private val before = access.tasks()
    private var display: VirtualDisplay? = null
    private var displayId = -1
    private var policy: MirrorTaskPolicy? = null
    private var host: ShellMirrorHost? = null
    private var launches: MirrorLaunchObserver? = null
    private var pendingLaunch = false
    private var originalStack = -1
    private var closed = false
    private var available = false
    private var wasAvailable = false
    private var everAvailable = false
    private var sourceEpoch = System.nanoTime()
    private var missingSince = 0L
    private var lastRoute = 0L
    private val polls = TaskPollSchedule()
    private val fence = CaptureSourceFence()
    private var sourceWidth = width
    private var sourceHeight = height
    private val sourceDensity = density

    init {
        try {
            // PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | SUPPORTS_TOUCH. No AUTO_MIRROR or DESTROY.
            display = RemoteDisplaySession.createVirtualDisplay(context, surface, width, height,
                density, 1 or 2 or 8 or 64)
            displayId = display?.display?.displayId ?: -1
            if (displayId <= 0) { throw IOException("MIRROR_DISPLAY_MISSING") }
            val owner = MirrorTaskPolicy(entry.packageName, user, displayId, before.map { it.id }.toSet())
            policy = owner
            val existing = initialTask(before, entry)
            if (existing != null) {
                originalStack = existing.stack
                owner.adopt(existing, true)
                access.host(existing, displayId, entry.packageName, user)
            } else {
                RemoteDisplaySession.launch(listOf("/system/bin/am", "start-activity", "-W", "--display",
                    displayId.toString(), "--user", user.toString(), "-n", entry.component,
                    "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER"))
            }
            val deadline = System.nanoTime() + 8_000_000_000L
            val startup = MirrorStartupPolicy(entry.packageName, user, displayId, before.map { it.id }.toSet())
            var task: AppTaskPolicy.Task? = null
            while (System.nanoTime() < deadline) {
                val observed = access.tasks()
                task = if (owner.taskId >= 0) owner.find(observed) else startup.observe(observed, System.nanoTime())
                    ?.also {
                        // A forwarding launcher may finish and create its business task on display 0.
                        // Only a new unique, settled same-user/package successor can be adopted here.
                        owner.adopt(it)
                        if (it.display == 0) {
                            originalStack = it.stack
                            access.host(it, displayId, entry.packageName, user)
                            System.err.println("SHELL_MIRROR_COLD_FORWARD_HOSTED task=${it.id} user=$user; exact_new_task=true")
                        }
                    }
                if (task?.display == 0) { task = owner.find(access.tasks()) }
                if (task != null && task.display == displayId) { break }
                Thread.sleep(40)
            }
            if (task == null || task.display != displayId) { throw IOException("MIRROR_APP_START_UNCONFIRMED") }
            val appUid = context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid
            if (appUid / 100000 != user) { throw IOException("MIRROR_ACTIVE_USER_CHANGED") }
            host = ShellMirrorHost(ticket, appUid, displayId, width, height).also { it.announce(context) }
            launches = MirrorLaunchObserver(entry.packageName, user, appUid,
                context.packageManager.getApplicationInfo(entry.packageName, 0).uid)
            fence.changed(System.nanoTime())
            System.err.println("SHELL_MIRROR_STARTED ${SingleAppTaskPolicy.describe(task)} source=${width}x$height " +
                "same_instance=true no_destroy_flag=true original_stack=$originalStack")
        } catch (error: Exception) {
            try { close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    private fun initialTask(tasks: List<AppTaskPolicy.Task>, entry: SingleAppEntry.Entry): AppTaskPolicy.Task? {
        val own = tasks.filter { it.user == user && it.root.substringBefore('/') == entry.packageName }
        if (own.any { it.display != 0 }) { throw IOException("MIRROR_APP_ON_OTHER_DISPLAY") }
        val exactRoot = own.filter { it.root == entry.root }
        if (exactRoot.size == 1) { return exactRoot.single() }
        val visible = own.filter { it.visible && it.top.substringBefore('/') == entry.packageName }
        if (visible.size == 1) { return visible.single() }
        if (own.size > 1) { throw IOException("MIRROR_EXISTING_TASK_AMBIGUOUS") }
        return own.singleOrNull()
    }

    /** Display/control work is independent of any optional encoder or preview consumer. */
    fun pollControl() {
        check(!closed)
        val endpoint = host ?: throw IOException("MIRROR_HOST_MISSING")
        endpoint.requireOwner()
        endpoint.sinks.take()?.let { sink ->
            var handedOff = false
            try {
                // Match the actual app area, including this ROM's system-bar/IME insets.
                // The encoded carrier and native aperture remain fixed and independent.
                if (sink.surface != null && (sourceWidth != sink.width || sourceHeight != sink.height)) {
                    require(BusinessDisplayProfile.valid(sink.width,sink.height,sourceDensity))
                    display!!.resize(sink.width,sink.height,sourceDensity)
                    renderer.resizeBusinessSource(sink.width,sink.height)
                    sourceWidth=sink.width;sourceHeight=sink.height
                    sourceEpoch=System.nanoTime();fence.changed(sourceEpoch)
                    endpoint.geometry(sourceWidth,sourceHeight)
                    System.err.println("SHELL_MIRROR_BUSINESS_VIEWPORT size=${sourceWidth}x$sourceHeight; primary_client_area=true encoded_carrier_unchanged=true")
                }
                renderer.setMirrorSink(sink)
                handedOff = true
            } finally { if (!handedOff) { sink.surface?.release() } }
        }
        endpoint.rendered(renderer.renderedMirrorLease())
        val now = System.nanoTime()
        val routeHint = endpoint.takeRoute()
        if (polls.due(now) || routeHint) {
            if (access.user() != user) { throw IOException("MIRROR_ACTIVE_USER_CHANGED") }
            val owner = checkNotNull(policy)
            var tasks = access.tasks()
            var task = owner.find(tasks)
            val icon = tasks.firstOrNull { it.user == user && it.display == 0 && it.visible &&
                it.top.substringBefore('/') == owner.packageName }
            if (task != null && task.display == 0) {
                access.host(task, displayId, owner.packageName, user)
                sourceEpoch = System.nanoTime()
                fence.changed(sourceEpoch)
                tasks = access.tasks()
                task = owner.find(tasks)
            }
            pendingLaunch = launches?.take() == true || pendingLaunch
            if ((icon != null || pendingLaunch) && task != null && now - lastRoute >= 500_000_000L) {
                if (icon != null && owner.disposableIconTask(icon)) { exact.removeMirrorIconTask(icon, owner) }
                endpoint.notifyApp(MirrorWire.SHOW)
                // Shell-started, DUMP-protected activity also works when Android 10 blocks a
                // background application start. This is fixed to our own mirror component.
                RemoteDisplaySession.launch(listOf("/system/bin/am", "start-activity", "--display", "0",
                    "--user", user.toString(), "-n", BuildConfig.APPLICATION_ID + "/" + MirrorActivity::class.java.name,
                    "-f", "0x30020000"))
                lastRoute = now
                pendingLaunch = false
                System.err.println("SHELL_MIRROR_ICON_ROUTED source=verified_task_or_fresh_launcher; no_raw_intent_logged=true")
            }
            available = task != null && task.display == displayId &&
                task.top.substringBefore('/') == owner.packageName
            if (task == null) {
                if (missingSince == 0L) { missingSince = now }
                if (now - missingSince > 1_000_000_000L) { throw IOException("MIRROR_APP_FINISHED") }
            } else { missingSince = 0L }
            if (available && !wasAvailable) {
                val changed = System.nanoTime()
                fence.changed(changed)
                if (everAvailable) { sourceEpoch = changed }
                everAvailable = true
            }
            wasAvailable = available
        }
    }

    override fun update(codec: MediaCodec) {
        pollControl()
        if (available && fence.needsSync(System.nanoTime())) {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    override fun renderAllowed(): Boolean = available
    override fun renderSourceEpochNanos(): Long = sourceEpoch

    override fun acceptFrame(ptsMicros: Long, key: Boolean): Boolean = available &&
        fence.accept(ptsMicros, key, System.nanoTime())

    override fun close() {
        if (closed) { return }
        closed = true
        var failure: Exception? = null
        fun release(action: () -> Unit) {
            try { action() } catch (error: Exception) {
                if (failure == null) { failure = error } else { failure!!.addSuppressed(error) }
            }
        }
        release { host?.close() }; host = null
        release { launches?.close() }; launches = null
        val owner = policy
        if (owner != null) {
            release {
                val candidates = owner.returnCandidates(access.tasks())
                for (task in candidates) {
                    release { access.giveBack(task, displayId, originalStack, user) }
                }
                if (owner.returnCandidates(access.tasks()).isNotEmpty()) {
                    throw IOException("CLEANUP_UNCONFIRMED mirror business task not returned")
                }
            }
        }
        release { display?.release() }; display = null
        if (failure != null) { throw IOException("CLEANUP_UNCONFIRMED mirror task/display", failure) }
        System.err.println("SHELL_MIRROR_TASKS_RETURNED display=$displayId; no_business_task_removed=true")
    }
}
