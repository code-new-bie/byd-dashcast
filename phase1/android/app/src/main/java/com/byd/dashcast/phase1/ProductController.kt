package com.byd.dashcast.phase1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import com.byd.dashcast.phase1.ncm.LogHub
import com.byd.dashcast.phase1.ncm.Usb
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Application-owned product coordinator. Activities may disappear without losing cleanup ownership.
 * @author Codex
 * @date 2026/10/21
 */
internal class ProductController private constructor(context: Context) {
    private val app = context.applicationContext
    private val settings = ProductSettings(app)
    private val occupied = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val history = ProjectionJournal()
    private val main = Handler(Looper.getMainLooper())
    private val startGeneration = AtomicLong()
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { job -> Thread(job, "dashcast-product").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    @Volatile var message: String = "选择导航应用，点击开始即可连接仪表"
        private set
    @Volatile var adbReady: Boolean = false
        private set
    @Volatile private var pendingStart: StartRequest? = null
    private data class StartRequest(val target: ProjectionTarget, val options: CaptureOptions, val underlay: Boolean, val generation: Long)
    val busy: Boolean get() = occupied.get()

    init {
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Usb.ACTION_PERM) { return }
                val device = intent.getParcelableExtra<UsbDevice>(Usb.EXTRA_DEVICE)
                val next = pendingStart
                if (device == null || !target(device)) { return }
                pendingStart = null
                if (!Usb.hasPermission(app, device)) {
                    message = "USB 访问未获允许；可再次点开始授权，不需要视频授权"
                    log("PRODUCT_USB_PERMISSION_DENIED")
                } else if (next != null) {
                    // Queue behind the operation that opened the dialog; an already-granted
                    // permission broadcast can arrive before that operation's finally block.
                    pendingStart = next
                    try {
                        worker.execute {
                            if (!occupied.compareAndSet(false, true)) {
                                message = "USB 已授权；当前操作结束后可点开始"
                                return@execute
                            }
                            try {
                                if (pendingStart === next) { pendingStart = null; startReady(next) }
                            } catch (error: Exception) {
                                message = ProductErrors.explain(error)
                                log("PRODUCT_PERMISSION_RESUME_FAILED $error")
                            } finally { occupied.set(false) }
                        }
                    } catch (error: RejectedExecutionException) {
                        pendingStart = null
                        message = "USB 已授权，请再次点开始"
                        log("PRODUCT_PERMISSION_QUEUE_REJECTED $error")
                    }
                }
            }
        }, IntentFilter(Usb.ACTION_PERM))
    }

    fun connect() = operation("正在连接本机 ADB；如有调试弹窗请允许") { authenticate() }

    fun start(target: ProjectionTarget) {
        if (!settings.setupComplete) { message = "请先完成首次连接设置"; return }
        if (ProjectionService.active()) { message = "已有投屏会话，请先停止再切换应用"; return }
        if (busy) { message = "当前操作尚未完成，请稍候"; return }
        val request = StartRequest(target, settings.options(), settings.underlay, startGeneration.incrementAndGet())
        operation("正在连接仪表并准备投屏") {
            requireClean()
            if (!adbReady) { authenticate() }
            if (request.generation != startGeneration.get()) { return@operation }
            val result = transition(InstrumentUsbCommand.NCM)
            if (request.generation != startGeneration.get()) { return@operation }
            log("PRODUCT_START_ROLE=$result")
            val device = targetDevice() ?: throw IOException("NCM_NOT_ENUMERATED: 尚未发现仪表连接，可保持数据线连接后重试")
            if (!Usb.hasPermission(app, device)) {
                pendingStart = request
                message = "请允许访问仪表 USB；授权后会自动开始"
                Usb.requestPermission(app, device)
            } else { startReady(request) }
        }
    }

    fun stop() {
        startGeneration.incrementAndGet()
        pendingStart = null
        val wasActive = ProjectionService.active()
        ProjectionService.requestStop(app)
        message = if (wasActive) "正在停止并恢复原生仪表背景" else "已取消待启动投屏；没有新的视频会话启动"
        log("PRODUCT_USER_STOP")
    }

    fun switchRole(command: InstrumentUsbCommand) {
        if (!settings.setupComplete) { message = "请先完成首次连接设置"; return }
        if (command == InstrumentUsbCommand.HOST) { startGeneration.incrementAndGet(); pendingStart = null }
        operation(if (command == InstrumentUsbCommand.HOST) "正在停止投屏、释放 USB 并恢复 U 盘模式" else "正在连接仪表模式") {
            if (!adbReady) { authenticate() }
            val result = transition(command)
            message = when (result) {
                HotRoleTransition.Result.EXISTING_NCM -> "仪表已连接；重复请求不会重置当前投屏"
                HotRoleTransition.Result.NCM_ENUMERATED -> "已发现仪表 NCM 连接，可以开始投屏"
                HotRoleTransition.Result.HOST_DETACHED_NOT_ACKNOWLEDGED -> "USB 已逻辑断开；U 盘模式结果以仪表提示为准"
                HotRoleTransition.Result.REQUEST_SENT_PEER_UNKNOWN -> "已发送一次切换请求，尚未确认结果；请查看仪表提示"
            }
            log("PRODUCT_ROLE_RESULT=$result; physical Host acknowledgement not inferred")
        }
    }

    fun exportLogs() = operation("正在导出日志到下载目录") {
        val file = DownloadLogExporter.fileName(System.currentTimeMillis())
        DownloadLogExporter.export(MediaStoreDownloadStore(app.contentResolver), file,
            "APK ${BuildConfig.VERSION_NAME}\n" + history.snapshot() + "\n" + ProjectionService.journal(),
            LogHub.snapshot(), "Product relay evidence is recorded in report.txt; no new system probes.\n",
            "NCM observation=${observe()}\n${ProjectionService.status()}\n")
        message = "已导出到下载目录：$file"
        log("PRODUCT_LOG_EXPORTED file=$file")
    }

    fun usbSummary(): String = try {
        val seen = observe()
        when {
            seen.allNcm == 0 -> "未发现仪表 USB 连接"
            seen.targetNcm != 1 || seen.allNcm != 1 -> "存在非目标或多个 NCM 设备"
            Usb.hasPermission(app, targetDevice()) -> "仪表 USB 已连接 · 已授权"
            else -> "仪表 USB 已连接 · 待授权"
        }
    } catch (error: RuntimeException) { "USB 状态不可用：${error.javaClass.simpleName}" }

    fun diagnosticText(): String = "Dashcast ${BuildConfig.VERSION_NAME}\nADB=${adbReady}\n${usbSummary()}\n" +
        ProjectionService.status() + "\n\n" + history.snapshot() + "\n" + ProjectionService.journal()

    private fun startReady(request: StartRequest) {
        if (request.generation != startGeneration.get()) { return }
        requireClean()
        // Re-check after an asynchronous permission dialog. Never start against a stale device.
        val device = targetDevice() ?: throw IOException("NCM_NOT_ENUMERATED")
        if (!Usb.hasPermission(app, device)) { throw IOException("USB_PERMISSION_REQUIRED") }
        // Serialize the final enqueue with UI STOP; a stop during ADB/USB preparation
        // invalidates this generation and cannot turn into a delayed surprise START.
        main.post {
            if (request.generation != startGeneration.get()) { return@post }
            try {
                requireClean()
                ProjectionService.startProduct(app, request.options, request.target, request.underlay)
                message = "正在创建独立应用画面；切换车机应用不会改为主屏镜像"
                log("PRODUCT_SESSION_REQUEST target=${request.target} underlay=${request.underlay}")
            } catch (error: Exception) {
                message = ProductErrors.explain(error)
                log("PRODUCT_START_ENQUEUE_FAILED $error")
            }
        }
    }

    private fun authenticate() {
        adbReady = false
        val identity = AndroidAdbIdentity.load()
        log("ADB_KEY_FINGERPRINT=${identity.fingerprint()}; preserved app identity")
        val controller = relay(identity)
        val uid = controller.executeChecked("id -u").trim()
        if (uid != "2000" && uid != "0") { throw IOException("ADB_IDENTITY_MISMATCH uid=$uid") }
        controller.verifyProfile()
        adbReady = true
        message = "ADB 已连接；原厂导航发送接口已匹配"
        log("PRODUCT_ADB_READY uid=$uid")
    }

    private fun relay(identity: AdbRsaIdentity = AndroidAdbIdentity.load()): InstrumentUsbController =
        InstrumentUsbController(app, AdbShellClient("127.0.0.1", 5555, 20000, identity,
            { line -> log(line) }, cancelled), cancelled, { line -> log(line) })

    private fun transition(command: InstrumentUsbCommand): HotRoleTransition.Result =
        HotRoleTransition(object : HotRoleTransition.Backend {
            override fun observe() = this@ProductController.observe()
            override fun requireClean() = this@ProductController.requireClean()
            override fun stopAndAwaitClean() {
                ProjectionService.requestStop(app)
                repeat(300) {
                    if (!ProjectionService.active()) { requireClean(); return }
                    Thread.sleep(100)
                }
                throw IOException("PRODUCT_STOP_TIMEOUT: 投屏清理尚未完成；未发送模式切换")
            }
            override fun acquire(): AutoCloseable = UsbOperationGate.acquire()
                ?: throw IOException("USB_BUSY: 另一个操作正在清理；未发送切换")
            override fun releaseTargetData() {
                val device = targetDevice() ?: throw IOException("USB_OBSERVATION_CHANGED")
                if (!Usb.hasPermission(app, device)) {
                    // Request permission separately; do not send Host before the proof exists.
                    Usb.requestPermission(app, device)
                    throw IOException("USB_PERMISSION_REQUIRED: 请允许 USB 后再次恢复 U 盘模式")
                }
                try {
                    val owned = Usb.open(app, device)
                    if (!owned.close()) { throw IOException("USB_DATA_ALT0_CLEANUP_UNCONFIRMED") }
                    log("PRODUCT_HOT_HOST_ALT0_AND_BOTH_RELEASES_CONFIRMED")
                } catch (error: Exception) {
                    // A partial claim may exist. Conservative quarantine, never race a new command.
                    ProjectionService.quarantine("PRODUCT_HOT_RELEASE_UNCONFIRMED: $error")
                    throw IOException("USB_CLEANUP_UNCONFIRMED: USB 释放未确认；未发送模式切换", error)
                }
            }
            override fun send(command: InstrumentUsbCommand) { relay().request(command) }
            override fun awaitObservation(command: InstrumentUsbCommand): Boolean {
                repeat(160) {
                    val seen = observe().also { it.requireUnique() }
                    if (command == InstrumentUsbCommand.NCM && seen.targetNcm == 1 ||
                        command == InstrumentUsbCommand.HOST && seen.allNcm == 0) { return true }
                    Thread.sleep(100)
                }
                return false
            }
        }).execute(command)

    private fun observe(): HotRoleTransition.Observation {
        val manager = Usb.mgr(app) ?: throw IOException("USB_MANAGER_UNAVAILABLE")
        val ncm = manager.deviceList.values.filter { UsbNcmSession.isNcmDevice(it) }
        return HotRoleTransition.Observation(ncm.size, ncm.count { target(it) })
    }

    private fun targetDevice(): UsbDevice? {
        observe().requireUnique()
        return Usb.mgr(app)?.deviceList?.values?.singleOrNull { target(it) && UsbNcmSession.isNcmDevice(it) }
    }

    private fun requireClean() {
        if (ProjectionService.quarantined()) { throw IOException("CLEANUP_UNCONFIRMED: 上次清理未确认，禁止叠加启动或切换；请重启仪表和应用恢复") }
        if (ProjectionService.active()) { throw IOException("SESSION_ACTIVE: 请先停止当前投屏") }
    }

    private fun operation(status: String, action: () -> Unit) {
        if (!occupied.compareAndSet(false, true)) { message = "当前操作尚未完成，请稍候；停止投屏仍可使用"; return }
        message = status
        try {
            worker.execute {
                try { action() } catch (error: Exception) {
                    if (error is InterruptedException) { Thread.currentThread().interrupt() }
                    message = ProductErrors.explain(error)
                    log("PRODUCT_OPERATION_FAILED ${error.javaClass.simpleName}: ${error.message}")
                } finally { occupied.set(false) }
            }
        } catch (error: RejectedExecutionException) {
            occupied.set(false)
            message = "操作队列暂不可用，请稍后重试"
            log("PRODUCT_QUEUE_REJECTED $error")
        }
    }

    private fun log(line: String) { history.append(line) }

    companion object {
        @Volatile private var instance: ProductController? = null
        fun get(context: Context): ProductController = instance ?: synchronized(this) {
            instance ?: ProductController(context).also { instance = it }
        }
        private fun target(device: UsbDevice) = device.vendorId == 0x1234 && device.productId == 0xfff1
    }
}

internal object ProductErrors {
    fun explain(error: Exception): String {
        val text = error.message.orEmpty()
        return when {
            text.contains("AUTH", true) || text.contains("authentication", true) -> "ADB 尚未授权；请允许车机的 USB 调试弹窗后重试"
            text.contains("MATCHING_FIRMWARE") -> "仪表固件与导航布局不匹配；请使用本次 1.1.0 配套仪表包"
            text.contains("NAVIGATION_LAYOUT_UNCONFIRMED") -> "原生导航布局未确认，已停止；请确认使用配套仪表包"
            text.contains("CLEANUP_UNCONFIRMED") -> "上次清理未确认，已停止重试；请重启仪表和应用恢复"
            text.contains("NCM_NOT_ENUMERATED") -> "暂未发现仪表连接；保持数据线连接并检查仪表模式提示"
            text.contains("USB_PERMISSION") -> "请允许访问仪表 USB 后再操作"
            text.contains("timeout", true) || text.contains("timed out", true) -> "连接或清理超时；未自动重复发送，请查看诊断详情"
            else -> "操作未完成：${text.take(180).ifEmpty { error.javaClass.simpleName }}"
        }
    }
}
