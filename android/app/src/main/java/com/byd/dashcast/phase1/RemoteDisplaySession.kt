package com.byd.dashcast.phase1

import android.content.Context
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.os.Bundle
import android.os.Handler
import android.os.Process
import android.view.Surface
import java.io.IOException
import java.util.concurrent.locks.LockSupport
import java.util.function.Consumer

/**
 * Owns only a newly created application task and its direct-output virtual display.
 * Never adopts, moves, resizes, relaunches or captures an existing primary-screen task.
 */
internal class RemoteDisplaySession @JvmOverloads @Throws(Exception::class) constructor(
    surface: Surface?,
    target: ProjectionTarget?,
    width: Int = NoAuthVideoProtocol.WIDTH,
    height: Int = NoAuthVideoProtocol.HEIGHT,
    @Suppress("UNUSED_PARAMETER") options: CaptureOptions? = CaptureOptions.of(CaptureOptions.Mode.STRETCH),
    profile: ProjectionEntryRouter.NativeEntry? = null,
) : RemoteCaptureSession {
    private var display: VirtualDisplay? = null
    private var access: AndroidTaskAccess? = null
    private var policy: IndependentTaskPolicy? = null
    private var owned: AppTaskPolicy.Task? = null
    private val existing = HashSet<Int>()
    private val polls = TaskPollSchedule()
    private var user = 0
    private var displayId = -1
    private var component: String? = null

    @Volatile
    private var closed = false

    @Volatile
    private var observedTask: AppTaskPolicy.Task? = null

    @Volatile
    private var observedAt = 0L

    @Volatile
    private var observationFailure: Exception? = null
    private var observer: Thread? = null
    private var frameAvailable = false
    private var everAvailable = false
    private var sourceEpoch = System.nanoTime()
    private val frameFence = CaptureSourceFence()

    init {
        if (surface == null || target == null || !target.isRemote) {
            throw IllegalArgumentException("Independent application display required")
        }
        val densityDpi = ProjectionDisplayProfile.densityDpi(width, height)
        try {
            val context = shellContext()
            val plan = if (profile == null) {
                IndependentProjectionEntry.resolve(context, target, Consumer { line -> System.err.println("SHELL_CAPTURE_$line") })
            } else {
                IndependentAppPlan.Plan(profile.component, true, profile.component)
            }
            val root = plan.taskRoot
            component = root
            val taskAccess = AndroidTaskAccess()
            access = taskAccess
            user = taskAccess.currentUser()
            for (task in taskAccess.projectionTasks()) {
                existing.add(task.id)
                if (plan.nativeSecondary && task.user == user && task.root == root) {
                    throw IOException("APP_NATIVE_SECONDARY_ALREADY_IN_USE; existing task left untouched")
                }
            }
            var ownName: String? = null
            val created = if (profile != null) {
                // The SDK matches fission_ prefixes, so a distinct alias preserves it
                // while the canonical external standby may be toggled at any time.
                ownName = NamedDisplayContract.nativeNameFromSystem()
                createVirtualDisplay(context, surface, width, height, densityDpi, FLAGS, ownName)
            } else {
                createVirtualDisplay(context, surface, width, height, densityDpi)
            }
            display = created
            val info = created.display
            if (info == null || info.displayId <= 0) {
                throw IOException("APP_INDEPENDENT_DISPLAY_MISSING")
            }
            displayId = info.displayId
            System.err.println("SHELL_CAPTURE_REMOTE_DISPLAY_CREATED id=" + displayId +
                " size=" + width + "x" + height + " density=" + densityDpi +
                " name=" + (if (profile != null) ownName else "dashcast-independent") +
                " direct_surface=true own_content_only=true")
            val taskPolicy = IndependentTaskPolicy(existing, root, user, displayId)
            policy = taskPolicy
            val arguments = ArrayList(plan.arguments(displayId, user))
            if (profile != null) {
                for ((key, value) in profile.extras) {
                    arguments.add("--ei")
                    arguments.add(key)
                    arguments.add(value.toString())
                }
            }
            launch(arguments)
            val deadline = System.nanoTime() + 10_000_000_000L
            do {
                owned = taskPolicy.discover(taskAccess.projectionTasks())
                if (owned != null) {
                    break
                }
                if (Thread.currentThread().isInterrupted) {
                    throw IOException("APP_INDEPENDENT_START_INTERRUPTED")
                }
                LockSupport.parkNanos(50_000_000L)
            } while (System.nanoTime() < deadline)
            val task = owned ?: throw IOException("APP_INDEPENDENT_TASK_NOT_CREATED; no mirror fallback")
            System.err.println("SHELL_CAPTURE_APP_INDEPENDENT task=" + task.id + " display=" + displayId +
                " user=" + user + " entry=" + root + " size=" + width + "x" + height +
                " direct_surface=true source_display_zero=false same_user_data=true native_secondary=" +
                plan.nativeSecondary)
            observedTask = task
            observedAt = System.nanoTime()
            observer = Thread({
                try {
                    while (!closed) {
                        val next = taskPolicy.findOwned(taskAccess.projectionTasks())
                        observedTask = next
                        observedAt = System.nanoTime()
                        Thread.sleep(100)
                    }
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (error: Exception) {
                    if (!closed) {
                        observationFailure = error
                    }
                }
            }, "dashcast-native-task-observer").apply {
                isDaemon = true
                start()
            }
        } catch (failure: Exception) {
            try {
                close()
            } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    @Throws(Exception::class)
    override fun update(codec: MediaCodec) {
        checkTask()
        if (frameAvailable && frameFence.needsSync(System.nanoTime())) {
            val request = Bundle()
            request.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(request)
        }
    }

    @Throws(Exception::class)
    override fun acceptFrame(ptsMicros: Long, key: Boolean): Boolean {
        checkTask()
        return everAvailable && frameFence.accept(ptsMicros, key, System.nanoTime())
    }

    override fun renderAllowed(): Boolean = everAvailable

    override fun advanceSourceAllowed(): Boolean = frameAvailable

    override fun renderSourceEpochNanos(): Long = sourceEpoch

    @Throws(Exception::class)
    private fun checkTask() {
        if (closed) {
            throw IOException("APP_INDEPENDENT_CAPTURE_CLOSED")
        }
        if (!polls.due(System.nanoTime())) {
            return
        }
        observationFailure?.let { throw IOException("APP_NATIVE_TASK_OBSERVER_FAILED", it) }
        val age = System.nanoTime() - observedAt
        if (age < 0 || age >= 4_000_000_000L) {
            throw IOException("APP_NATIVE_TASK_OBSERVATION_STALE")
        }
        val current = observedTask
        if (current == null || current.id != owned!!.id) {
            throw IOException("APP_INDEPENDENT_TASK_LOST; original tasks left untouched")
        }
        val root = component!!
        val available = age < 500_000_000L && current.top.startsWith(root.substring(0, root.indexOf('/')) + "/")
        if (available && !frameAvailable) {
            val now = System.nanoTime()
            if (!everAvailable) {
                frameFence.changed(now)
            } else {
                sourceEpoch = now
            }
            everAvailable = true
        }
        if (!available && frameAvailable) {
            System.err.println("SHELL_CAPTURE_APP_TOP_WAIT task=" + current.id +
                " display=" + current.display + " user=" + current.user + " root=" + root)
        }
        frameAvailable = available
    }

    @Throws(Exception::class)
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        var observerCleanup: IOException? = null
        val thread = observer
        if (thread != null) {
            thread.interrupt()
            try {
                thread.join(4500)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                observerCleanup = IOException("CLEANUP_UNCONFIRMED native observer join interrupted", interrupted)
            }
            if (thread.isAlive) {
                observerCleanup = IOException("CLEANUP_UNCONFIRMED native task observer still running")
            }
        }
        try {
            IndependentTaskCleanup.close(policy, object : IndependentTaskCleanup.Backend {
                override fun tasks(): List<AppTaskPolicy.Task> = access!!.projectionTasks()

                override fun remove(task: AppTaskPolicy.Task) {
                    access!!.removeProjectionTask(task, displayId, existing)
                }

                override fun releaseDisplay() {
                    val ownDisplay = display
                    if (ownDisplay != null) {
                        display = null
                        ownDisplay.release()
                        System.err.println("SHELL_CAPTURE_REMOTE_DISPLAY_RELEASED")
                    }
                }
            })
        } finally {
            System.err.println("SHELL_CAPTURE_INDEPENDENT_CLEANUP task_queries=" + polls.queries +
                "; original_tasks_not_moved_or_removed")
        }
        if (observerCleanup != null) {
            throw observerCleanup
        }
    }

    companion object {
        // PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | DESTROY_CONTENT_ON_REMOVAL. No AUTO_MIRROR.
        // Failed cleanup must never migrate this display's remaining activities onto the primary display.
        private const val FLAGS = 1 or 2 or 8 or 256

        @JvmStatic
        @Throws(Exception::class)
        fun createVirtualDisplay(context: Context, surface: Surface, width: Int, height: Int, densityDpi: Int): VirtualDisplay =
            createVirtualDisplay(context, surface, width, height, densityDpi, FLAGS)

        @JvmStatic
        @Throws(Exception::class)
        fun createVirtualDisplay(context: Context, surface: Surface, width: Int, height: Int, densityDpi: Int,
            flags: Int): VirtualDisplay =
            createVirtualDisplay(context, surface, width, height, densityDpi, flags, "dashcast-independent-" + System.nanoTime())

        @JvmStatic
        @Throws(Exception::class)
        fun createVirtualDisplay(context: Context, surface: Surface, width: Int, height: Int, densityDpi: Int,
            flags: Int, name: String?): VirtualDisplay {
            val type = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val manager = type.getMethod("getInstance").invoke(null)
            val int = Int::class.javaPrimitiveType
            val create = type.getMethod("createVirtualDisplay", Context::class.java,
                Class.forName("android.media.projection.MediaProjection"), String::class.java,
                int, int, int, Surface::class.java, int,
                VirtualDisplay.Callback::class.java, Handler::class.java, String::class.java)
            val result = create.invoke(manager, context, null, name, width, height, densityDpi, surface, flags, null, null, null)
            return result as? VirtualDisplay ?: throw IOException("APP_INDEPENDENT_DISPLAY_CREATE_FAILED")
        }

        @JvmStatic
        @Throws(Exception::class)
        fun launch(arguments: List<String>) {
            val child = ProcessBuilder(arguments).redirectErrorStream(true).start()
            val result = BoundedProcessOutput.read(child, 10_000L).trim().replace('\n', ' ').replace('\r', ' ')
            // Do not retain arbitrary application/logcat output or Intent data.
            System.err.println("SHELL_CAPTURE_INDEPENDENT_LAUNCH exit=" + child.exitValue())
            if (child.exitValue() != 0 || result.contains("Error:") || result.contains("SecurityException")) {
                throw IOException("APP_INDEPENDENT_START_REJECTED")
            }
            System.err.println("SHELL_CAPTURE_REMOTE_AM exit=0 independent=true")
        }

        @JvmStatic
        @Throws(Exception::class)
        fun shellContext(): Context {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            var activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            if (activityThread == null) {
                val constructor = activityThreadClass.getDeclaredConstructor()
                constructor.isAccessible = true
                activityThread = constructor.newInstance()
            }
            val getSystemContext = activityThreadClass.getMethod("getSystemContext")
            val systemContext = getSystemContext.invoke(activityThread) as Context?
                ?: throw IOException("SHELL_CAPTURE_REMOTE_SYSTEM_CONTEXT_MISSING")
            val packages = systemContext.packageManager.getPackagesForUid(Process.myUid())
            if (packages != null) {
                for (packageName in packages) {
                    try {
                        return systemContext.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
                    } catch (ignored: Exception) {
                        // Try the next package associated with the shell UID, then use system context.
                    }
                }
            }
            return systemContext
        }
    }
}
