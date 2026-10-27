package com.byd.dashcast.phase1

import android.content.BroadcastReceiver
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.view.MotionEvent
import android.view.Surface
import com.byd.dashcast.phase1.ncm.LogHub
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Application-owned rendezvous. A page is optional; it never owns the instrument stream.
 * @author Codex
 * @date 2026/10/25
 */
internal object MirrorBridge {
    internal class Session(val ticket: String, val packageName: String, val context: Context) {
        @Volatile var endpoint: IBinder? = null
        @Volatile var width = 0
        @Volatile var height = 0
        @Volatile var display = -1
        var death: IBinder.DeathRecipient? = null
    }
    @Volatile private var session: Session? = null
    @Volatile var message = "镜像尚未启动"
        private set
    private val leases = AtomicLong()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val worker by lazy {
        ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64),
            { job -> Thread(job, "dashcast-mirror-control").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
    }

    @JvmStatic @Synchronized fun begin(context: Context, packageName: String): String {
        check(session == null) { "MIRROR_SESSION_ALREADY_ACTIVE" }
        val next = Session(DuplexTicket.create(), packageName, context.applicationContext)
        session = next
        message = "正在准备同实例镜像"
        MirrorIconService.refresh()
        return next.ticket
    }

    @JvmStatic @Synchronized fun end(ticket: String?) {
        val old = session ?: return
        if (old.ticket != ticket) { return }
        session = null
        old.death?.let { old.endpoint?.unlinkToDeath(it, 0) }
        message = "投屏已结束，原图标恢复原应用"
        MirrorIconService.refresh()
    }

    fun current(): Session? = session
    fun nextLease(): Long = leases.incrementAndGet()

    fun accept(intent: Intent) {
        val owner = session ?: return
        if (intent.getStringExtra("ticket") != owner.ticket || owner.endpoint != null) { return }
        val endpoint = intent.getBundleExtra("control")?.getBinder("endpoint") ?: return
        val width = intent.getIntExtra("width", 0)
        val height = intent.getIntExtra("height", 0)
        val display = intent.getIntExtra("display", -1)
        if (!BusinessDisplayProfile.valid(width, height, 160) || display <= 0) { return }
        enqueue {
            if (session !== owner || owner.endpoint != null) { return@enqueue }
            val death = IBinder.DeathRecipient { end(owner.ticket) }
            endpoint.linkToDeath(death, 0)
            owner.death = death
            owner.endpoint = endpoint
            owner.width = width
            owner.height = height
            owner.display = display
            try {
                transact(owner, MirrorWire.REGISTER) { it.writeStrongBinder(callback(owner)) }
                message = "同实例镜像已连接；原图标可打开操作画面"
                LogHub.i("mirror", "MIRROR_CONTROL_READY display=$display source=${width}x$height; no_credential_logged=true")
            } catch (error: Exception) {
                end(owner.ticket)
                throw error
            }
        }
    }

    private fun callback(owner: Session) = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (getCallingUid() != 2000 || data.dataSize() > 512) { throw SecurityException("MIRROR_CALLBACK_CALLER") }
            data.enforceInterface(MirrorWire.CALLBACK)
            if (data.readString() != owner.ticket || session !== owner) { return false }
            when (code) {
                MirrorWire.SHOW -> main.post { if (session === owner) { open(owner.context) } }
                MirrorWire.CLOSED -> end(owner.ticket)
                MirrorWire.GEOMETRY -> {
                    val width = data.readInt()
                    val height = data.readInt()
                    if (!BusinessDisplayProfile.valid(width, height, 160)) {
                        throw SecurityException("MIRROR_GEOMETRY_INVALID")
                    }
                    owner.width = width
                    owner.height = height
                }
                else -> return false
            }
            return true
        }
    }

    fun open(context: Context) {
        if (session?.endpoint == null) { message = "请先开始一个普通应用的投屏会话"; return }
        try {
            context.startActivity(Intent(context, MirrorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP), ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
        } catch (error: RuntimeException) { message = "镜像页面未能打开，请查看诊断"; logFailure(error) }
    }

    fun route(packageName: String) {
        val owner = session ?: return
        if (owner.packageName != packageName || owner.endpoint == null) { return }
        enqueue { if (session === owner) { transact(owner, MirrorWire.ROUTE) {} } }
    }

    fun attach(lease: Long, surface: Surface, width: Int, height: Int) {
        val owner = session
        if (owner?.endpoint == null) { surface.release(); return }
        enqueue({ surface.release() }) {
            try {
                if (session === owner) {
                    transact(owner, MirrorWire.ATTACH) {
                        it.writeLong(lease); it.writeInt(width); it.writeInt(height); surface.writeToParcel(it, 0)
                    }
                }
            } finally { surface.release() }
        }
    }

    fun detach(lease: Long) {
        val owner = session ?: return
        enqueue { if (session === owner && owner.endpoint != null) {
            transact(owner, MirrorWire.DETACH) { it.writeLong(lease) }
        } }
    }

    fun touch(lease: Long, event: MotionEvent, sourceWidth: Int, sourceHeight: Int) {
        val owner = session
        if (owner?.endpoint == null) { event.recycle(); return }
        enqueue({ event.recycle() }) {
            try {
                if (session === owner) { transact(owner, MirrorWire.TOUCH) {
                    it.writeLong(lease); it.writeInt(sourceWidth); it.writeInt(sourceHeight)
                    event.writeToParcel(it, 0)
                } }
            } finally { event.recycle() }
        }
    }

    fun back(lease: Long) {
        val owner = session ?: return
        enqueue { if (session === owner && owner.endpoint != null) {
            transact(owner, MirrorWire.BACK) { it.writeLong(lease) }
        } }
    }

    private fun transact(owner: Session, code: Int, write: (Parcel) -> Unit) {
        val endpoint = owner.endpoint ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(MirrorWire.DESCRIPTOR)
            data.writeString(owner.ticket)
            write(data)
            check(endpoint.transact(code, data, reply, 0)) { "MIRROR_CONTROL_REJECTED" }
            reply.readException()
        } finally { data.recycle(); reply.recycle() }
    }

    private fun enqueue(rejected: () -> Unit = {}, action: () -> Unit) {
        try { worker.execute { try { action() } catch (error: Exception) { logFailure(error) } } }
        catch (error: RejectedExecutionException) { rejected(); logFailure(error) }
    }

    private fun logFailure(error: Exception) {
        message = "镜像控制未完成：${error.javaClass.simpleName}；仪表视频独立运行"
        // Do not log Parcel, token, input text, positions or arbitrary remote exception payloads.
        LogHub.w("mirror", "MIRROR_CONTROL_FAILURE type=${error.javaClass.simpleName}")
    }
}

/** Protected by android.permission.DUMP in the manifest; only shell/system may supply an endpoint. */
class MirrorHostReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { MirrorBridge.accept(intent) }
}
