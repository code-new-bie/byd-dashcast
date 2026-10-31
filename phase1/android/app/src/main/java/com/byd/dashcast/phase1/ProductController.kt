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
    @Volatile private var requestedTarget: ProjectionTarget? = null
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { job -> Thread(job, "dashcast-product").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    @Volatile var message: String = "选择应用后，点击“开始投屏”即可。"
        private set
    @Volatile var adbReady: Boolean = false
        private set
    @Volatile var operation: ProductOperation = ProductOperation.NONE
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
                    message = "尚未允许访问仪表 USB，可再次点击开始并选择允许。"
                    MirrorWorkspace.fail(startGeneration.get(), message)
                    log("PRODUCT_USB_PERMISSION_DENIED")
                } else if (outcome.result == UsbPermissionContinuation.Result.GRANTED) {
                    val next = outcome.request ?: return
                    // Queue behind the operation that opened the dialog; an already-granted
                    // permission broadcast can arrive before that operation's finally block.
                    try {
                        worker.execute {
                            if (!occupied.compareAndSet(false, true)) {
                                message = "已允许访问仪表，请等当前操作结束后再点开始。"
                                MirrorWorkspace.fail(next.generation, message)
                                return@execute
                            }
                            operation = ProductOperation.START
                            try {
                                if (next.generation == startGeneration.get()) { prepareStart(next) }
                            } catch (error: Exception) {
                                message = ProductErrors.explain(error)
                                MirrorWorkspace.fail(next.generation, message)
                                log("PRODUCT_PERMISSION_RESUME_FAILED $error")
                            } finally { operation = ProductOperation.NONE; occupied.set(false) }
                        }
                    } catch (error: RejectedExecutionException) {
                        message = "已允许访问仪表，请再次点击开始。"
                        MirrorWorkspace.fail(next.generation, message)
                        log("PRODUCT_PERMISSION_QUEUE_REJECTED $error")
                    }
                }
            }
        }, IntentFilter(Usb.ACTION_PERM))
    }

    fun connect() = operation("正在连接车机，如出现调试弹窗请选择允许。", ProductOperation.CONNECT) { authenticate() }

    fun startWorkspace(target: ProjectionTarget) {
        val next = ProjectionTarget.workspaceComponent(target.component(), target.displayLabel())
        start(next, true)
    }

    fun start(target: ProjectionTarget) = start(target, false)

    private fun start(target: ProjectionTarget, showWorkspace: Boolean) {
        if (!settings.setupComplete) { message = "请先完成首次连接设置"; return }
        if (busy) { message = "当前操作尚未完成，请稍候"; return }
        if (ProjectionService.active()) {
            if (showWorkspace) { replace(target, true) }
            else { message = "已有投屏会话，请先停止再切换应用" }
            return
        }
        if (!target.isNamed) { ExternalDisplayAccess.suppressCurrent() }
        val request = StartRequest(target, settings.options(), settings.underlay, startGeneration.incrementAndGet())
        requestedTarget = target
        if (showWorkspace) { MirrorWorkspace.begin(app, target, request.generation) }
        operation("正在连接仪表并准备画面。", ProductOperation.START) { prepareStart(request) }
    }

    fun toggleMap() {
        if (busy) { message = "当前操作尚未完成，请稍候"; return }
        val maps = ProductAppCatalog.load(app).filter { it.navigation && it.capability.allowed }
        val map = maps.firstOrNull { it.component.startsWith("com.byd.automap/") }
            ?: maps.firstOrNull { it.component.startsWith("com.autonavi.amapauto/") }
            ?: maps.firstOrNull()
        if (map == null) { message = "未找到可用地图，请到应用页选择。"; return }
        val next = map.target()
        if (ProjectionService.active() && requestedTarget == next) { stop(); return }
        replace(next)
    }

    fun namedDisplay(): Boolean = replace(ProjectionTarget.namedDisplay())

    fun recordExternal(line: String) = log(line)

    private fun replace(target: ProjectionTarget, showWorkspace: Boolean = false): Boolean {
        if (!settings.setupComplete) { message = "请先完成首次连接设置"; return false }
        if (busy) { message = "当前操作尚未完成，请稍候"; return false }
        if (!target.isNamed) { ExternalDisplayAccess.suppressCurrent() }
        stop()
        val request = StartRequest(target, settings.options(), settings.underlay, startGeneration.incrementAndGet())
        requestedTarget = target
        if (showWorkspace) { MirrorWorkspace.begin(app, target, request.generation) }
        return operation("正在结束上次投屏并准备新画面。", ProductOperation.START) {
            val deadline = System.nanoTime() + 15_000_000_000L
            while (!ProjectionService.readyForNewService()) {
                if (request.generation != startGeneration.get()) { return@operation }
                if (System.nanoTime() >= deadline) { throw IOException("SERVICE_FINISHING") }
                Thread.sleep(50)
            }
            prepareStart(request)
        }
    }

    private fun prepareStart(request: StartRequest) {
        if (request.generation != startGeneration.get()) { return }
        log("PRODUCT_START_REQUEST target=${request.target} generation=${request.generation}")
        ProjectionEntryRouter.resolve(app, request.target, java.util.function.Consumer { log(it) })
        if (!recoverIfNecessary(request)) { return }
        // A terminal worker state is published before Android destroys its
        // finishing Service. Do not enqueue START into that old instance.
        awaitPreviousServiceExit()
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
        message = "请在系统弹窗中允许访问仪表 USB，随后会继续投屏。"
        log("PRODUCT_USB_PERMISSION_WAIT generation=${request.generation}")
        Usb.requestPermission(app, device)
    }

    fun stop() {
        MirrorWorkspace.stopping()
        ExternalDisplayAccess.suppressCurrent(captureOnly = true)
        startGeneration.incrementAndGet()
        usbPermission.clear()
        val wasActive = ProjectionService.active()
        ProjectionService.requestStop(app)
        message = if (wasActive) "正在停止投屏并恢复仪表画面。" else "已取消启动，可以重新选择应用。"
        log("PRODUCT_USER_STOP")
    }

    fun changingTarget() {
        ExternalDisplayAccess.suppressCurrent()
        stop()
        message = "已更换应用，原投屏结束后点击开始即可。"
        log("PRODUCT_TARGET_CHANGED old_session_stop_requested")
    }

    fun recordAppCapability(selected: ProductApp) {
        log("PRODUCT_APP_CAPABILITY_SELECTED component=${IndependentAppPlan.safe(selected.component)} " +
            "allowed=${selected.capability.allowed}")
        log(selected.capability.evidence.take(2200))
    }

    fun switchRole(command: InstrumentUsbCommand) {
        if (!settings.setupComplete) { message = "请先完成首次连接设置"; return }
        if (command == InstrumentUsbCommand.HOST) {
            ExternalDisplayAccess.suppressCurrent()
            startGeneration.incrementAndGet()
            usbPermission.clear()
        }
        operation(if (command == InstrumentUsbCommand.HOST) "正在停止投屏并恢复 U盘使用。" else "正在连接仪表。",
            ProductOperation.MODE) {
            if (command == InstrumentUsbCommand.NCM && !ProjectionService.active() &&
                ProjectionService.state() == ProjectionSessionController.State.DISCONNECTED && observe().allNcm == 0) {
                if (!adbReady) { authenticate() }
                // Explicit user role request only; the original instrument guard remains authoritative.
                // This does not clear pending peer recovery or start a media session.
                relay().request(command)
                message = "已发送连接请求，请查看仪表提示；重新连接后可点击开始。"
                log("PRODUCT_DETACHED_USER_NCM_REQUEST; recovery remains pending, no Host or media START")
                return@operation
            }
            recoverIfNecessary()
            if (!adbReady) { authenticate() }
            val result = transition(command)
            message = when (result) {
                HotRoleTransition.Result.EXISTING_NCM -> "仪表已连接，可以开始投屏。"
                HotRoleTransition.Result.NCM_ENUMERATED -> "仪表已连接，可以开始投屏。"
                HotRoleTransition.Result.HOST_DETACHED_NOT_ACKNOWLEDGED -> "投屏连接已断开，U盘是否恢复请查看仪表提示。"
                HotRoleTransition.Result.REQUEST_SENT_PEER_UNKNOWN -> "已发送切换请求，请查看仪表提示确认结果。"
            }
            log("PRODUCT_ROLE_RESULT=$result; physical Host acknowledgement not inferred")
        }
    }

    fun exportLogs() = operation("正在导出日志到下载目录。", ProductOperation.EXPORT) {
        val file = DownloadLogExporter.fileName(System.currentTimeMillis())
        val entryDiagnostics = ProductAppCatalog.diagnostics(ProductAppCatalog.load(app))
        DownloadLogExporter.export(MediaStoreDownloadStore(app.contentResolver), file,
            "APK ${BuildConfig.VERSION_NAME}\n" + ExternalDisplayAccess.diagnosticText() +
                history.coordinatorSnapshot() + "\n" + ProjectionService.journal() +
                "\n" + RecoveryIncidentStore.snapshot(app) + "\n" + entryDiagnostics,
            LogHub.snapshot(), "Product relay evidence is recorded in report.txt; no new system probes.\n",
            "NCM observation=${observe()}\n${ProjectionService.status()}\n" + RecoveryIncidentStore.snapshot(app))
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

    fun ncmObserved(): Boolean = try { observe().let { it.allNcm == 1 && it.targetNcm == 1 } }
        catch (error: RuntimeException) { false }

    fun connectionState(): ProductConnection = try {
        val seen = observe()
        when {
            seen.allNcm == 0 -> ProductConnection.ABSENT
            seen.allNcm != 1 || seen.targetNcm != 1 -> ProductConnection.AMBIGUOUS
            Usb.hasPermission(app, targetDevice()) -> ProductConnection.CONNECTED
            else -> ProductConnection.PERMISSION
        }
    } catch (error: Exception) { ProductConnection.UNAVAILABLE }

    fun activeSourceLabel(): String? = requestedTarget?.let {
        if (it.isNamed) "应用内投屏" else it.displayLabel()
    }

    fun diagnosticText(): String = "Dashcast ${BuildConfig.VERSION_NAME}\nADB=${adbReady}\n${usbSummary()}\n" +
        ExternalDisplayAccess.diagnosticText() +
        ProjectionService.status() + "\n\n" + history.coordinatorSnapshot() + "\n" + ProjectionService.journal() +
        "\n" + RecoveryIncidentStore.snapshot(app)

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
                val selection = ProjectionEntryRouter.resolve(app,request.target,java.util.function.Consumer { log(it) })
                val displayTarget = if (request.target.isRemote && selection.native != null &&
                    ExternalDisplayAccess.providesPackage(app,request.target.component().substringBefore('/'))) {
                    log("PRODUCT_NATIVE_ENTRY_REUSES_OWN_EXTERNAL_DISPLAY; original_application_task_preserved=true")
                    ProjectionTarget.namedDisplay()
                } else { request.target }
                ProjectionService.startProduct(app, request.options, displayTarget, request.underlay)
                message = "正在准备应用画面，连接好后会显示在仪表上。"
                log("PRODUCT_SESSION_REQUEST target=${request.target} underlay=${request.underlay}")
            } catch (error: Exception) {
                message = ProductErrors.explain(error)
                MirrorWorkspace.fail(request.generation, message)
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
        message = "车机已连接，可以开始投屏。"
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
                    quarantine("PRODUCT_HOT_RELEASE_UNCONFIRMED: $error")
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
        if (ProjectionService.state() == ProjectionSessionController.State.RECOVERY_REQUIRED) {
            throw IOException("PEER_RECOVERY_REQUIRED")
        }
    }

    private fun recoverIfNecessary(request: StartRequest? = null): Boolean {
        val expected = ProjectionService.state()
        if (expected != ProjectionSessionController.State.DISCONNECTED &&
            expected != ProjectionSessionController.State.RECOVERY_REQUIRED) { return true }
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
                    quarantine("CLEANUP_UNCONFIRMED recovery open: $error")
                }
                throw error
            }
            try {
                session.use {
                    session.connectRecovery(cancellation).use { channel ->
                        DetachedPeerRecovery.verifyAwaitIdle(channel, { Thread.sleep(100) }, System::nanoTime)
                    }
                }
            } catch (error: Exception) {
                if (!session.localResourcesClosed()) {
                    quarantine("CLEANUP_UNCONFIRMED recovery local resources: $error")
                } else {
                    ProjectionService.notePeerRecoveryFailure(expected, "PEER_RECOVERY_NOT_CONFIRMED: ${error.message}")
                    rememberIncident(expected, "PEER_RECOVERY_NOT_CONFIRMED: ${error.message}")
                }
                throw error
            }
            if (!session.localResourcesClosed()) {
                quarantine("CLEANUP_UNCONFIRMED recovery local resources")
                throw IOException("CLEANUP_UNCONFIRMED recovery local resources")
            }
            ProjectionService.confirmPeerRecovery(expected)
            log("PRODUCT_DETACHED_RECOVERY_CONFIRMED read_only=true; no media START or CAN command sent by recovery")
        }
        return true
    }

    private fun awaitPreviousServiceExit() {
        repeat(100) {
            if (ProjectionService.readyForNewService()) { return }
            if (ProjectionService.active()) { throw IOException("SESSION_ACTIVE") }
            Thread.sleep(20)
        }
        throw IOException("SERVICE_FINISHING: 正在完成上次退出，请稍后点开始")
    }

    private fun quarantine(detail: String) {
        ProjectionService.quarantine(detail)
        rememberIncident(ProjectionSessionController.State.QUARANTINED, detail)
    }

    private fun rememberIncident(state: ProjectionSessionController.State, detail: String) {
        try { RecoveryIncidentStore.record(app, state, detail) }
        catch (error: RuntimeException) { log("CLEANUP_DIAGNOSTIC_SAVE_FAILED $error") }
    }

    private fun operation(status: String, kind: ProductOperation, action: () -> Unit): Boolean {
        if (!occupied.compareAndSet(false, true)) {
            message = "当前操作尚未完成，请稍候；停止投屏仍可使用"
            return false
        }
        operation = kind
        message = status
        val generation = startGeneration.get()
        try {
            worker.execute {
                try { action() } catch (error: Exception) {
                    if (error is InterruptedException) { Thread.currentThread().interrupt() }
                    message = ProductErrors.explain(error)
                    if (kind == ProductOperation.START) { MirrorWorkspace.fail(generation, message) }
                    log("PRODUCT_OPERATION_FAILED ${error.javaClass.simpleName}: ${error.message}")
                } finally { operation = ProductOperation.NONE; occupied.set(false) }
            }
        } catch (error: RejectedExecutionException) {
            occupied.set(false)
            operation = ProductOperation.NONE
            message = "操作队列暂不可用，请稍后重试"
            if (kind == ProductOperation.START) { MirrorWorkspace.fail(generation, message) }
            log("PRODUCT_QUEUE_REJECTED $error")
            return false
        }
        return true
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
