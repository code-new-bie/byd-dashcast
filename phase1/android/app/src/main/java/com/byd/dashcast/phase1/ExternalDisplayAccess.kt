package com.byd.dashcast.phase1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.view.Surface
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps external standby independent from USB/video and reuses the product's one-session arbiter. */
internal object ExternalDisplayAccess {
    @Volatile private var desired = false
    @Volatile private var endpoint: IBinder? = null
    @Volatile private var hostTicket: String? = null
    @Volatile private var captureTicket: String? = null
    @Volatile private var captureUid: Int? = null
    @Volatile private var hostStop: AtomicBoolean? = null
    @Volatile private var owners: Set<Int> = emptySet()
    @Volatile private var healthy = false
    @Volatile private var mainMapFocused = false
    @Volatile private var observedDisplayId = -1
    @Volatile var message: String = "关闭后，可继续从应用列表投屏。"
        private set
    private var context: Context? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private val policy = ExternalAccessPolicy()
    private val owner = Binder()
    private var stopService: (() -> Unit)? = null
    private val poll = object : Runnable {
        override fun run() {
            try { inspect() } catch (error: Exception) {
                healthy = false
                message = "接入连接暂不可用，可关闭后重新开启。"
                record("EXTERNAL_STANDBY_POLL_FAILED type=${error.javaClass.simpleName}")
            } finally { if (hostStop?.get() == false) { handler?.postDelayed(this, 500) } }
        }
    }

    @JvmStatic fun enabled(): Boolean = desired
    @JvmStatic fun ready(): Boolean = endpoint?.isBinderAlive == true && healthy
    @JvmStatic fun name(): String = NamedDisplayContract.NAME
    @JvmStatic fun mapEntryVisible(): Boolean = desired && healthy && mainMapFocused
    @Synchronized fun serviceDestroyed() {
        desired = false
        healthy = false
        hostStop?.set(true)
        MirrorIconService.refresh()
    }
    fun providesPackage(app: Context, packageName: String): Boolean {
        if (!ready()) { return false }
        val uid = app.packageManager.getApplicationInfo(packageName,0).uid
        return owners == setOf(uid)
    }

    @Synchronized fun setEnabled(app: Context, enabled: Boolean, stop: () -> Unit) {
        context = app.applicationContext
        stopService = stop
        desired = enabled
        record("EXTERNAL_ACCESS_SWITCH enabled=$enabled; standby_only=true")
        MirrorIconService.refresh()
        if (!enabled) {
            policy.consume(owners.singleOrNull())
            message = if (captureTicket != null) "已关闭接入，当前投屏继续。" else "已关闭接入。"
            if (endpoint == null) { hostStop?.set(true); if (hostStop == null) { stop() } }
            return
        }
        if (hostStop != null) {
            if (hostStop!!.get()) { desired = false; message = "接入正在关闭，请稍后再开启。" }
            return
        }
        // A new display lifetime must not inherit a consumed UID from the
        // previous display, even if its first observation already has a window.
        policy.reset()
        val ownContext = app.applicationContext
        val ticket = DuplexTicket.create()
        val cancelled = AtomicBoolean()
        hostTicket = ticket; hostStop = cancelled
        message = "正在开启应用接入，仪表画面保持不变。"
        worker = HandlerThread("dashcast-external-observer").apply { start() }
        handler = Handler(worker!!.looper)
        val apk = ownContext.applicationInfo.sourceDir
        // Reuse the installed-path validation/quoting; only replace its fixed main entry point.
        val command = ShellCaptureCommand.create(apk, 1).substringBefore(" com.byd.dashcast.phase1.ShellScreenCapture ") +
            " com.byd.dashcast.phase1.ShellExternalDisplay " + ticket
        Thread({
            var clean = false
            var exit = false
            try {
                val adb = AdbShellClient("127.0.0.1", 5555, 20000, AndroidAdbIdentity.load(),
                    { line -> record(line) }, AtomicBoolean())
                adb.executeShellV2Controlled(command, { bytes, count ->
                    if (count !in 1..4096 || (0 until count).any { bytes[it].toInt()!=1 }) {
                        throw IOException("EXTERNAL_STANDBY_HEARTBEAT_INVALID")
                    }
                }, { line ->
                    if (line.contains("EXTERNAL_DISPLAY_RESOURCES_RELEASED")) { clean = true }
                    record(line)
                }, cancelled, {}, { ended -> exit = ended })
            } catch (error: Exception) {
                record("EXTERNAL_STANDBY_FAILED type=${error.javaClass.simpleName} detail=${error.message}")
            } finally {
                synchronized(this) {
                    if (hostTicket == ticket) {
                        desired = false; endpoint = null; healthy = false
                        mainMapFocused = false
                        hostTicket = null; hostStop = null; owners = emptySet()
                        handler?.removeCallbacksAndMessages(null)
                        worker?.quitSafely(); worker = null; handler = null
                        message = if (clean && exit) "应用接入已关闭。" else "应用接入已结束，可重新开启；普通投屏仍可使用。"
                    }
                }
                record("EXTERNAL_STANDBY_COMPLETION exit_confirmed=$exit resources_released=$clean")
                MirrorIconService.refresh()
                synchronized(this) { if (hostStop == null) { stopService?.invoke() } }
            }
        }, "dashcast-external-adb").apply { isDaemon = true; start() }
    }

    fun accept(app: Context, intent: Intent) {
        val ticket = intent.getStringExtra("ticket") ?: return
        val remote = intent.getBundleExtra("control")?.getBinder("endpoint") ?: return
        if (intent.getStringExtra("kind") == "host") {
            synchronized(this) {
                if (ticket != hostTicket || hostStop?.get() != false) { return }
                ExternalDisplayWire.command(remote,ticket,ExternalDisplayWire.REGISTER) { it.writeStrongBinder(owner) }
                endpoint = remote
                message = "接入已开启，等待应用发送画面。"
                record("EXTERNAL_DISPLAY_READY name=${NamedDisplayContract.NAME}; standby_only=true")
                handler?.post(poll)
            }
            return
        }
        val surface = intent.getParcelableExtra<Surface>("surface") ?: return
        try {
            synchronized(this) {
                if (ticket != captureTicket) { return }
                val host = endpoint ?: throw IOException("EXTERNAL_HOST_MISSING")
                ExternalDisplayWire.command(host,checkNotNull(hostTicket),ExternalDisplayWire.ATTACH) {
                    it.writeString(ticket); it.writeStrongBinder(remote); surface.writeToParcel(it, 0)
                }
                ExternalDisplayWire.command(remote,ticket,ExternalDisplayWire.CAPTURE_READY) {
                    it.writeInt(1); it.writeStrongBinder(captureControl)
                }
                record("EXTERNAL_CAPTURE_ATTACHED; single_application=true; display_lifetime=standby_service")
            }
        } catch (error: Exception) {
            record("EXTERNAL_CAPTURE_RENDEZVOUS_FAILED type=${error.javaClass.simpleName}")
            try {
                ExternalDisplayWire.command(remote,ticket,ExternalDisplayWire.CAPTURE_READY) {
                    it.writeInt(0); it.writeStrongBinder(null)
                }
            } catch (callback: Exception) { record("EXTERNAL_CAPTURE_REJECTION_FAILED type=${callback.javaClass.simpleName}") }
        } finally { surface.release() }
    }

    private val captureControl = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (getCallingUid() != 2000 || data.dataSize()>1024) { throw SecurityException("EXTERNAL_CONTROL_CALLER") }
            data.enforceInterface(ExternalDisplayWire.DESCRIPTOR)
            val ticket = data.readString()
            if (ticket != captureTicket) { throw SecurityException("EXTERNAL_CONTROL_TICKET") }
            when (code) {
                ExternalDisplayWire.CAPTURE_STATUS -> {
                    reply?.writeNoException()
                    reply?.writeInt(if (healthy && owners == setOf(captureUid)) 1 else 0)
                }
                ExternalDisplayWire.CAPTURE_DETACH -> {
                    endCapture(ticket)
                    reply?.writeNoException()
                }
                else -> return false
            }
            return true
        }
    }

    private fun inspect() {
        val host = endpoint ?: return
        val ticket = hostTicket ?: return
        val snapshot = ExternalDisplayWire.call(host,ticket,ExternalDisplayWire.SNAPSHOT) { data ->
            val id = data.readInt()
            val available = data.readInt() == 1
            val count = data.readInt()
            if (id <= 0 || count !in 0..64) { throw IOException("EXTERNAL_SNAPSHOT_INVALID") }
            observedDisplayId = id
            val windows = (0 until count).map { data.readInt() }.toSet()
            Triple(available,windows,data.readInt()==1)
        }
        val hadMapEntry = mapEntryVisible()
        val oldHealthy = healthy
        val oldOwners = owners
        healthy = snapshot.first; owners = snapshot.second; mainMapFocused = snapshot.third
        if (oldHealthy != healthy || oldOwners != owners) {
            record("EXTERNAL_DISPLAY_OBSERVATION display=$observedDisplayId healthy=$healthy applications=${owners.size} map_focused=$mainMapFocused")
        }
        if (hadMapEntry != mapEntryVisible()) {
            record("MAP_ENTRY_ELIGIBILITY focused=$mainMapFocused ready=$healthy enabled=$desired overlay_permission=${android.provider.Settings.canDrawOverlays(checkNotNull(context))} accessibility=${MirrorIconService.connected()}")
            MirrorIconService.refresh()
        }
        if (!desired && captureTicket == null && owners.isEmpty()) {
            ExternalDisplayWire.command(host,ticket,ExternalDisplayWire.RELEASE)
            hostStop?.set(true)
            return
        }
        val app = context ?: return
        val controller = ProductController.get(app)
        val next = synchronized(this) {
            policy.candidate(owners,healthy,desired,ProjectionService.active(),controller.busy)
        }
        if (next != null && ProjectionService.readyForNewService() && !ProjectionService.blocksOperations()) {
            Handler(app.mainLooper).post { if (desired && ready() && owners == setOf(next) &&
                ProjectionService.readyForNewService() && !ProjectionService.blocksOperations() && !controller.busy) {
                if (controller.namedDisplay()) {
                    synchronized(this) { policy.consume(next) }
                    record("EXTERNAL_APPLICATION_READY uid=$next; routed_to_single_product_session=true")
                }
            } }
        }
        if (desired && captureTicket == null) {
            message = when {
                !healthy -> "接入已开启，正在检查应用画面。"
                owners.size>1 -> "请先结束其他应用的外部投屏，一次只能显示一个应用。"
                owners.size==1 && ProjectionService.active() -> "已收到应用画面，当前投屏结束后接入。"
                else -> "接入已开启，等待应用发送画面。"
            }
        }
        if (mapEntryVisible()) { MirrorIconService.refresh() }
    }

    @JvmStatic @Synchronized fun beginCapture(): String {
        if (!ready() || owners.size != 1 || captureTicket != null) {
            throw IOException("EXTERNAL_ONE_APPLICATION_NOT_READY")
        }
        captureUid = owners.single()
        return DuplexTicket.create().also { captureTicket = it }
    }

    @JvmStatic @Synchronized fun endCapture(ticket: String?) {
        if (ticket == null || ticket != captureTicket) { return }
        endpoint?.let { host -> ExternalDisplayWire.command(host,checkNotNull(hostTicket),ExternalDisplayWire.DETACH) {
            it.writeString(ticket)
        } }
        policy.consume(captureUid)
        captureTicket = null; captureUid = null
        record("EXTERNAL_VIDEO_ENDED; standby_display_preserved=true")
    }

    @JvmStatic @JvmOverloads @Synchronized fun suppressCurrent(captureOnly: Boolean = false) {
        policy.consume(if (captureOnly) captureUid else owners.singleOrNull())
    }

    @JvmStatic fun diagnosticText(): String =
        "EXTERNAL_ACCESS_STATE enabled=$desired ready=${ready()} endpoint_alive=${endpoint?.isBinderAlive == true}" +
        " healthy=$healthy display=$observedDisplayId name=${NamedDisplayContract.NAME}" +
        " applications=${owners.size} capture_active=${captureTicket != null}\n"

    private fun record(line: String) {
        context?.let { ProductController.get(it).recordExternal(line) }
    }
}

/** Only shell/system can rendezvous; tickets and Binder UIDs are checked again by each endpoint. */
class ExternalDisplayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try { ExternalDisplayAccess.accept(context,intent) }
        catch (error: Exception) { ProductController.get(context).recordExternal("EXTERNAL_ENDPOINT_FAILED type=${error.javaClass.simpleName}") }
    }
}
