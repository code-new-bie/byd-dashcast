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
    private val usbPermission = UsbPermissionContinuation<StartRequest>()
    private data class StartRequest(val target: ProjectionTarget, val options: CaptureOptions, val underlay: Boolean, val generation: Long)
    val busy: Boolean get() = occupied.get()

    init {
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Usb.ACTION_PERM) { return }
                val device = intent.getParcelableExtra<UsbDevice>(Usb.EXTRA_DEVICE)
                if (device == null || !target(device)) { return }
                val outcome = usbPermission.consume(device.deviceName, startGeneration.get(),
                    Usb.hasPermission(app, device))
                if (outcome.result == UsbPermissionContinuation.Result.DENIED) {
                    message = "USB 访问未获允许；可再次点开始授权，不需要视频授权"
                    log("PRODUCT_USB_PERMISSION_DENIED")
                } else if (outcome.result == UsbPermissionContinuation.Result.GRANTED) {
                    val next = outcome.request ?: return
                    // Queue behind the operation that opened the dialog; an already-granted
                    // permission broadcast can arrive before that operation's finally block.
                    try {
                        worker.execute {
                            if (!occupied.compareAndSet(false, true)) {
                                message = "USB 已授权；当前操作结束后可点开始"
                                return@execute
                            }
                            try {
                                if (next.generation == startGeneration.get()) { prepareStart(next) }
                            } catch (error: Exception) {
                                message = ProductErrors.explain(error)
                                log("PRODUCT_PERMISSION_RESUME_FAILED $error")
                            } finally { occupied.set(false) }
                        }
                    } catch (error: RejectedExecutionException) {
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
        operation("正在连接仪表并准备投屏") { prepareStart(request) }
    }

    private fun prepareStart(request: StartRequest) {
        if (request.generation != startGeneration.get()) { return }
        IndependentProjectionEntry.resolve(app, request.target)
        if (!recoverIfNecessary(request)) { return }
        requireClean()
        if (!adbReady) { authenticate() }
        if (request.generation != startGeneration.get()) { return }
        val result = transition(InstrumentUsbCommand.NCM)
        if (request.generation != startGeneration.get()) { return }
        log("PRODUCT_START_ROLE=$result")
        val device = targetDevice() ?: throw IOException("NCM_NOT_ENUMERATED: 尚未发现仪表连接，可保持数据线连接后重试")
        if (!Usb.hasPermission(app, device)) { requestStartPermission(request, device) }
        else { startReady(request) }
    }

    private fun requestStartPermission(request: StartRequest, device: UsbDevice) {
        if (request.generation != startGeneration.get()) { return }
        usbPermission.begin(device.deviceName, request.generation, request)
        message = "请允许访问仪表 USB；授权后会继续恢复并开始投屏"
        log("PRODUCT_USB_PERMISSION_WAIT generation=${request.generation}")
        Usb.requestPermission(app, device)
    }

    fun stop() {
        startGeneration.incrementAndGet()
        usbPermission.clear()
        val wasActive = ProjectionService.active()
        ProjectionService.requestStop(app)
        message = if (wasActive) "正在停止并恢复原生仪表背景" else "已取消待启动投屏；没有新的视频会话启动"
        log("PRODUCT_USER_STOP")
    }

    fun switchRole(command: InstrumentUsbCommand) {
        if (!settings.setupComplete) { message = "请先完成首次连接设置"; return }
        if (command == InstrumentUsbCommand.HOST) { startGeneration.incrementAndGet(); usbPermission.clear() }
        operation(if (command == InstrumentUsbCommand.HOST) "正在停止投屏、释放 USB 并恢复 U 盘模式" else "正在连接仪表模式") {
            if (command == InstrumentUsbCommand.NCM && !ProjectionService.active() &&
                ProjectionService.state() == ProjectionSessionController.State.DISCONNECTED && observe().allNcm == 0) {
                if (!adbReady) { authenticate() }
                // Explicit user role request only; the original instrument guard remains authoritative.
                // This does not clear pending peer recovery or start a media session.
                relay().request(command)
                message = "已提交一次连接模式请求；重新发现仪表后点开始，只读恢复确认通过才会投屏"
                log("PRODUCT_DETACHED_USER_NCM_REQUEST; recovery remains pending, no Host or media START")
                return@operation
            }
            recoverIfNecessary()
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
            "APK ${BuildConfig.VERSION_NAME}\n" + history.coordinatorSnapshot() + "\n" + ProjectionService.journal(),
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
        ProjectionService.status() + "\n\n" + history.coordinatorSnapshot() + "\n" + ProjectionService.journal()

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
        if (ProjectionService.state() == ProjectionSessionController.State.DISCONNECTED) {
            throw IOException("DETACHED_RECOVERY_REQUIRED")
        }
    }

    private fun recoverIfNecessary(request: StartRequest? = null): Boolean {
        if (ProjectionService.state() != ProjectionSessionController.State.DISCONNECTED) { return true }
        check(Looper.myLooper() != Looper.getMainLooper()) { "Recovery I/O must never run on the UI thread" }
        val device = targetDevice() ?: throw IOException("DETACHED_RECOVERY_RECONNECT_REQUIRED")
        if (!Usb.hasPermission(app, device)) {
            if (request != null) {
                requestStartPermission(request, device)
                return false
            }
            Usb.requestPermission(app, device)
            throw IOException("USB_PERMISSION_REQUIRED")
        }
        val gate = UsbOperationGate.acquire() ?: throw IOException("USB_BUSY: 本地清理尚未结束")
        gate.use {
            val cancellation = AtomicBoolean()
            val session = try { UsbNcmSession.open(app, cancellation) } catch (error: Exception) {
                if (DetachedPeerRecovery.localCleanupUnconfirmed(error)) {
                    ProjectionService.quarantine("CLEANUP_UNCONFIRMED recovery open: $error")
                }
                throw error
            }
            try {
                session.use {
                    session.connectRecovery(cancellation).use { channel ->
                        DetachedPeerRecovery.verify(channel) { Thread.sleep(100) }
                    }
                }
            } catch (error: Exception) {
                if (!session.localResourcesClosed()) {
                    ProjectionService.quarantine("CLEANUP_UNCONFIRMED recovery local resources: $error")
                }
                throw error
            }
            if (!session.localResourcesClosed()) {
                ProjectionService.quarantine("CLEANUP_UNCONFIRMED recovery local resources")
                throw IOException("CLEANUP_UNCONFIRMED recovery local resources")
            }
            ProjectionService.confirmDetachedRecovery()
            log("PRODUCT_DETACHED_RECOVERY_CONFIRMED read_only=true; no media START or CAN command sent by recovery")
        }
        return true
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
            text.contains("APP_SINGLE_INSTANCE_UNSUPPORTED") -> "此应用只提供单界面实例，不能独立投屏；原应用未移动，也未改用镜像"
            text.contains("APP_NATIVE_SECONDARY_VERSION_UNVERIFIED") -> "此版本的独立仪表入口尚未验证，未操作原应用；请导出诊断信息"
            text.contains("APP_NATIVE_SECONDARY_ALREADY_IN_USE") -> "应用自带的副屏界面已被使用，本次未接管或移动"
            text.contains("APP_INDEPENDENT") || text.contains("APP_PRIMARY_MIRROR_DISABLED") -> "应用未能建立独立画面，已停止；不会镜像主屏或搬移原任务"
            text.contains("AUTH", true) || text.contains("authentication", true) -> "ADB 尚未授权；请允许车机的 USB 调试弹窗后重试"
            text.contains("MATCHING_FIRMWARE") -> "仪表固件与导航布局不匹配；请使用本次 ${BuildConfig.VERSION_NAME} 配套仪表包"
            text.contains("NAVIGATION_LAYOUT_UNCONFIRMED") -> "原生导航布局未确认，已停止；请确认使用配套仪表包"
            text.contains("PRODUCT_SOURCE_NOT_READY") -> "地图画面尚未准备好，已停止本次启动；原仪表背景未关闭，可稍候重试"
            text.contains("DETACHED_RECOVERY_RECONNECT_REQUIRED") -> "数据线已断开，本地资源已释放；重新接线后点击开始或连接模式即可检查恢复，无需重启"
            text.contains("DETACHED_RECOVERY") -> "仪表正在恢复；本地操作已解锁，稍候重新接线并重试。恢复确认前不会叠加投屏或强切模式"
            text.contains("CLEANUP_UNCONFIRMED") -> "上次清理未确认，已停止重试；请重启仪表和应用恢复"
            text.contains("NCM_NOT_ENUMERATED") -> "暂未发现仪表连接；保持数据线连接并检查仪表模式提示"
            text.contains("USB_PERMISSION") -> "请允许访问仪表 USB 后再操作"
            text.contains("timeout", true) || text.contains("timed out", true) -> "连接或清理超时；未自动重复发送，请查看诊断详情"
            else -> "操作未完成：${text.take(180).ifEmpty { error.javaClass.simpleName }}"
        }
    }
}
