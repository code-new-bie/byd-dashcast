package com.byd.dashcast.phase1

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

/**
 * User-started, non-exported foreground service. No boot start, sticky restart or role automation.
 * @author Codex
 * @date 2026/10/13
 */
class ProjectionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: ThreadPoolExecutor
    private lateinit var controller: ProjectionSessionController
    private var workspaceGeneration = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private val renewWakeLease: Runnable = object : Runnable {
        override fun run() {
            val lock = wakeLock
            if (destroyed || finishing || !::controller.isInitialized || !controller.active() || lock == null) {
                return
            }
            try {
                lock.acquire(WAKE_LEASE_MILLIS)
                main.postDelayed(this, WAKE_RENEW_MILLIS)
            } catch (error: RuntimeException) {
                JOURNAL.append("PRODUCT_WAKE_LEASE_FAILED $error")
                controller.abort("Foreground wake ownership lost")
            }
        }
    }
    private var ownedDevice: String? = null
    private var receiverRegistered = false

    @Volatile
    private var finishing = false

    @Volatile
    private var ownedLease: UsbOperationGate.Lease? = null

    @Volatile
    private var destroyed = false
    private var continuousTask: ContinuousProjectionTask? = null

    private val detach = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action && device != null &&
                device.deviceName == ownedDevice) {
                JOURNAL.append("OWNED_USB_PHYSICAL_DETACH device=$ownedDevice")
                continuousTask?.notePhysicalDetach()
                controller.abort("Owned instrument USB detached")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
            ThreadFactory { task -> Thread(task, "dashcast-continuous-session").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
        controller = ProjectionSessionController(worker, object : ProjectionSessionController.Observer {
            override fun state(state: ProjectionSessionController.State, detail: String) {
                sessionState(state, detail, ProjectionSessionController.CleanupScope.LOCAL)
            }

            override fun quarantined(detail: String, scope: ProjectionSessionController.CleanupScope) {
                sessionState(ProjectionSessionController.State.QUARANTINED, detail, scope)
            }
        })
        current = controller
    }

    private fun sessionState(state: ProjectionSessionController.State, detail: String,
        scope: ProjectionSessionController.CleanupScope) {
        JOURNAL.state(state, detail, scope)
        MirrorWorkspace.state(workspaceGeneration, state)
        try {
            RecoveryIncidentStore.record(this, state, detail)
        } catch (diagnosticError: RuntimeException) {
            JOURNAL.append("CLEANUP_DIAGNOSTIC_SAVE_FAILED $diagnosticError")
        }
        if (state == ProjectionSessionController.State.STOPPED || state == ProjectionSessionController.State.FAILED ||
            state == ProjectionSessionController.State.DISCONNECTED ||
            state == ProjectionSessionController.State.RECOVERY_REQUIRED ||
            state == ProjectionSessionController.State.QUARANTINED) {
            finishing = true
            // Publish quarantine BEFORE releasing the process gate; never let a new Service
            // instance race a previous owner's unconfirmed cleanup.
            val completedLease = ownedLease
            ownedLease = null
            completedLease?.close()
            main.post {
                if (destroyed) {
                    if (current === controller) {
                        current = null
                    }
                    return@post
                }
                if (controller.active()) {
                    return@post
                }
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        if (state == ProjectionSessionController.State.QUARANTINED) {
            quarantineListener?.let { main.post(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stopRequest = intent == null || STOP == intent.action
        if (!stopRequest) {
            // Every other intent comes through startForegroundService(). Android 10 kills the whole app when
            // such a service stops before startForeground(), so enter the foreground before any check and
            // leave it again on each early exit below.
            startForeground(NOTIFICATION_ID, notification())
        }
        if (finishing) {
            JOURNAL.append("START_IGNORED while Service is finishing; wait for stopped status before retry")
            leaveWithoutSession()
            return START_NOT_STICKY
        }
        if (stopRequest) {
            controller.stop()
            if (!controller.active()) {
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val request = intent!!
        val product = request.getBooleanExtra("product_session", false)
        if (product && request.getLongExtra("product_generation", -1L) != PRODUCT_GENERATION.get()) {
            JOURNAL.append("PRODUCT_START_CANCELLED before Service ownership")
            if (!controller.active()) {
                leaveWithoutSession()
            }
            return START_NOT_STICKY
        }
        // Product sessions come only from the user's own start action; there is no separate setup step.
        val confirmed = product || request.getBooleanExtra("parked_matching_firmware", false)
        if (START != request.action || !confirmed ||
            JOURNAL.quarantined() || JOURNAL.currentState() == ProjectionSessionController.State.RECOVERY_REQUIRED ||
            JOURNAL.currentState() == ProjectionSessionController.State.DISCONNECTED) {
            JOURNAL.append("START_BLOCKED: missing user confirmation or previous cleanup unconfirmed")
            if (!controller.active()) {
                leaveWithoutSession()
            }
            return START_NOT_STICKY
        }
        if (controller.active()) {
            JOURNAL.append("DUPLICATE_START_IGNORED: current session preserved")
            return START_NOT_STICKY
        }
        val lease = UsbOperationGate.acquire()
        if (lease == null) {
            JOURNAL.append("START_BLOCKED: another diagnostic/USB operation is still cleaning up")
            leaveWithoutSession()
            return START_NOT_STICKY
        }
        try {
            if (JOURNAL.quarantined()) {
                throw IOException("Previous owner's cleanup became unconfirmed; retry blocked")
            }
            val seconds = request.getIntExtra("seconds", 0)
            if (product) {
                ContinuousSessionPolicy.requireCaptureSeconds(seconds)
            } else {
                ProjectionLimits.requireSeconds(seconds)
            }
            val options = CaptureOptions.parse(request.getStringExtra("capture_selection"))
            val target = ProjectionTarget.parse(request.getStringExtra("projection_target"))
            workspaceGeneration = if (target.isWorkspace) MirrorWorkspace.starting(target) else 0
            val composition = request.getBooleanExtra("composition_trial", false)
            val probe = request.getBooleanExtra("capture_only_probe", false)
            val underlay = request.getBooleanExtra("product_underlay", false)
            if ((product && (probe || composition || !ContinuousSessionPolicy.unlimited(seconds) ||
                    underlay == options.centerWindow() || !options.contentViewport())) ||
                (!product && (options.centerWindow() || options.contentViewport()))) {
                throw IOException("Invalid product session profile")
            }
            if ((composition && (probe || seconds != 10)) || (probe && (seconds != 5 || !target.isRemote))) {
                throw IOException("Invalid fixed composition/probe profile")
            }
            val mode = if (product) {
                if (underlay) "product-renewable-underlay" else "product-small-window"
            } else if (probe) {
                "capture-only-probe"
            } else if (composition) {
                "composition-5s"
            } else {
                "small-window"
            }
            JOURNAL.append("PROJECTION_CONFIG=" + options + "; target=" + target +
                "; encoded=1920x720/30fps mode=" + mode + "; actual composition/FPS require vehicle observation")
            if (!probe) {
                val device = UsbNcmSession.findDevice(this)
                if (product && (device.vendorId != 0x1234 || device.productId != 0xfff1)) {
                    throw IOException("Product instrument identity changed before Service ownership")
                }
                val usb = getSystemService(USB_SERVICE) as UsbManager?
                if (usb == null || !usb.hasPermission(device)) {
                    throw IOException("Normal instrument USB permission required before starting")
                }
                ownedDevice = device.deviceName
            }
            if (!probe) {
                registerReceiver(detach, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
                receiverRegistered = true
            }
            val power = getSystemService(POWER_SERVICE) as PowerManager? ?: throw IOException("PowerManager unavailable")
            val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dashcast:continuous")
            wakeLock = lock
            lock.setReferenceCounted(false)
            lock.acquire(if (product) WAKE_LEASE_MILLIS else (seconds + 30L) * 1000)
            val journal = Consumer<String> { JOURNAL.append(it) }
            val continuous = if (probe) null else ContinuousProjectionTask(this, seconds, journal, options, target, composition)
            continuousTask = continuous
            if (product) {
                continuous!!.enableProductComposition()
            }
            val task: ProjectionSessionController.Task = if (probe) RemoteDisplayProbeTask(this, target, journal) else continuous!!
            ownedLease = lease
            val accepted = controller.start(ProjectionSessionController.Task { stop, abort, streaming, cleaning ->
                task.run(stop, abort, streaming, cleaning)
            })
            if (!accepted) {
                ownedLease = null
                lease.close()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else if (product) {
                main.postDelayed(renewWakeLease, WAKE_RENEW_MILLIS)
            }
        } catch (error: IOException) {
            startFailed(lease, error)
        } catch (error: RuntimeException) {
            startFailed(lease, error)
        }
        return START_NOT_STICKY
    }

    private fun startFailed(lease: UsbOperationGate.Lease, error: Exception) {
        ownedLease = null
        lease.close()
        JOURNAL.append("START_FAILED before capture: $error")
        MirrorWorkspace.state(workspaceGeneration, ProjectionSessionController.State.FAILED)
        if (!JOURNAL.quarantined()) {
            JOURNAL.state(ProjectionSessionController.State.FAILED, error.toString())
        }
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** A start intent that began no session: drop the notification shown on arrival and let the Service go. */
    private fun leaveWithoutSession() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager?
            ?: throw IllegalStateException("NotificationManager unavailable")
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.projection_channel),
            NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, ProductActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ProjectionService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.projection_notification_title))
            .setContentText(getString(R.string.projection_notification_text))
            .setOngoing(true).setContentIntent(open)
            .addAction(0, getString(R.string.projection_notification_stop), stop).build()
    }

    private fun releaseWakeLock() {
        main.removeCallbacks(renewWakeLease)
        val lock = wakeLock
        if (lock != null && lock.isHeld) {
            lock.release()
        }
        wakeLock = null
    }

    override fun onDestroy() {
        destroyed = true
        controller.abort("Foreground Service destroyed")
        worker.shutdown()
        if (receiverRegistered) {
            unregisterReceiver(detach)
            receiverRegistered = false
        }
        releaseWakeLock()
        if (current === controller && !controller.active()) {
            current = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Internal: these helpers take the module's internal session types.
    internal companion object {
        private const val CHANNEL = "dashcast-projection"
        private const val START = "com.byd.dashcast.START_CONTINUOUS"
        private const val STOP = "com.byd.dashcast.STOP_CONTINUOUS"
        private const val NOTIFICATION_ID = 50
        private const val WAKE_LEASE_MILLIS = 60_000L
        private const val WAKE_RENEW_MILLIS = 20_000L
        private val JOURNAL = ProjectionJournal()
        private val PRODUCT_GENERATION = AtomicLong()

        @Volatile
        private var current: ProjectionSessionController? = null

        @Volatile
        private var quarantineListener: Runnable? = null

        @JvmStatic
        fun start(context: Context, seconds: Int) {
            start(context, seconds, CaptureOptions.of(CaptureOptions.Mode.STRETCH))
        }

        @JvmStatic
        fun start(context: Context, seconds: Int, options: CaptureOptions?) {
            start(context, seconds, options, ProjectionTarget.primary())
        }

        @JvmStatic
        fun start(context: Context, seconds: Int, options: CaptureOptions?, target: ProjectionTarget?) {
            start(context, seconds, options, target, false, false)
        }

        @JvmStatic
        fun startComposition(context: Context, options: CaptureOptions?, target: ProjectionTarget?) {
            start(context, 10, options, target, true, false)
        }

        @JvmStatic
        fun startProbe(context: Context, target: ProjectionTarget?) {
            start(context, 5, CaptureOptions.of(CaptureOptions.Mode.FIT), target, false, true)
        }

        @JvmStatic
        fun startProduct(context: Context, options: CaptureOptions?, target: ProjectionTarget?, underlay: Boolean) {
            if (target == null || options == null) {
                throw IllegalArgumentException("Explicit capture selection required")
            }
            context.startForegroundService(Intent(context, ProjectionService::class.java).setAction(START)
                .putExtra("seconds", ContinuousSessionPolicy.UNLIMITED_SECONDS).putExtra("product_session", true)
                .putExtra("product_generation", PRODUCT_GENERATION.incrementAndGet())
                .putExtra("product_underlay", underlay).putExtra("capture_selection", options.token())
                .putExtra("projection_target", target.token()))
        }

        private fun start(context: Context, seconds: Int, options: CaptureOptions?, target: ProjectionTarget?,
            composition: Boolean, probe: Boolean) {
            ProjectionLimits.requireSeconds(seconds)
            if (options == null || target == null) {
                throw IllegalArgumentException("Capture selection and projection target required")
            }
            context.startForegroundService(Intent(context, ProjectionService::class.java).setAction(START)
                .putExtra("seconds", seconds).putExtra("parked_matching_firmware", true)
                .putExtra("capture_selection", options.token())
                .putExtra("composition_trial", composition).putExtra("capture_only_probe", probe)
                .putExtra("projection_target", target.token()))
        }

        @JvmStatic
        fun requestStop(context: Context) {
            // A user's STOP also invalidates an enqueued START not yet delivered to the Service.
            PRODUCT_GENERATION.incrementAndGet()
            val active = current
            if (active != null && active.active()) {
                context.startService(Intent(context, ProjectionService::class.java).setAction(STOP))
            }
        }

        @JvmStatic
        fun blocksOperations(): Boolean {
            val active = current
            return JOURNAL.quarantined() || (active != null && active.active())
        }

        @JvmStatic
        fun status(): String = JOURNAL.status()

        @JvmStatic
        fun state(): ProjectionSessionController.State = JOURNAL.currentState()

        @JvmStatic
        fun quarantined(): Boolean = JOURNAL.quarantined()

        @JvmStatic
        fun active(): Boolean {
            val owner = current
            return owner != null && owner.active()
        }

        @JvmStatic
        fun readyForNewService(): Boolean = current == null

        @JvmStatic
        fun quarantine(detail: String) {
            JOURNAL.state(ProjectionSessionController.State.QUARANTINED, detail)
            quarantineListener?.let { Handler(Looper.getMainLooper()).post(it) }
        }

        /** Called on the main thread whenever the session state becomes QUARANTINED. */
        @JvmStatic
        fun setQuarantineListener(listener: Runnable?) {
            quarantineListener = listener
        }

        /** Null unless QUARANTINED. */
        @JvmStatic
        fun cleanupScope(): ProjectionSessionController.CleanupScope? = JOURNAL.cleanupScope()

        @JvmStatic
        fun leftoverCaptureCleaned(evidence: String): Boolean =
            !active() && readyForNewService() && JOURNAL.leftoverCaptureCleaned(evidence)

        @JvmStatic
        fun restartedAfterLocalCleanup(reason: String): Boolean = !active() && JOURNAL.restartedAfterLocalCleanup(reason)

        @JvmStatic
        fun journal(): String = JOURNAL.snapshot()

        @JvmStatic
        @Throws(IOException::class)
        fun confirmDetachedRecovery() {
            confirmPeerRecovery(ProjectionSessionController.State.DISCONNECTED)
        }

        @JvmStatic
        fun notePeerRecoveryFailure(expected: ProjectionSessionController.State, detail: String) {
            if (!active() && JOURNAL.currentState() == expected &&
                (expected == ProjectionSessionController.State.RECOVERY_REQUIRED ||
                    expected == ProjectionSessionController.State.DISCONNECTED)) {
                JOURNAL.state(expected, detail)
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun confirmPeerRecovery(expected: ProjectionSessionController.State) {
            if (active() || JOURNAL.currentState() != expected ||
                (expected != ProjectionSessionController.State.DISCONNECTED &&
                    expected != ProjectionSessionController.State.RECOVERY_REQUIRED)) {
                throw IOException("DETACHED_RECOVERY_STATE_CHANGED")
            }
            JOURNAL.state(ProjectionSessionController.State.STOPPED,
                "PEER_RECOVERY_CONFIRMED; local resources closed and fresh peer idle snapshot verified")
        }
    }
}
