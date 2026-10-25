package com.byd.dashcast.phase1

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Shell-only input/Surface endpoint. No arbitrary shell command, text, package or display supplied by clients.
 * @author Codex
 * @date 2026/10/25
 */
internal class ShellMirrorHost(private val ticket: String, private val appUid: Int, private val displayId: Int,
    private val width: Int, private val height: Int) : Binder(), AutoCloseable {
    val sinks = MirrorSinkMailbox()
    private var callback: IBinder? = null
    private val closed = AtomicBoolean()
    private val route = AtomicBoolean()
    private val ownerDied = AtomicBoolean()
    private var inputLease = 0L
    private var inputAttached = false
    private var renderedLease = -1L
    private var touchDown = false
    private var lastTouch = 0L
    private val death = IBinder.DeathRecipient { ownerDied.set(true) }
    private val inputManager = Class.forName("android.hardware.input.InputManager")
        .getMethod("getInstance").invoke(null)
    private val inject = inputManager.javaClass.getMethod("injectInputEvent", InputEvent::class.java,
        Int::class.javaPrimitiveType)
    private val setDisplay = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)

    init { require(Process.myUid() == 2000 && DuplexTicket.valid(ticket) && displayId > 0 &&
        appUid / 100000 == Process.myUid() / 100000) }

    fun announce(context: Context) {
        val bundle = Bundle().apply { putBinder("endpoint", this@ShellMirrorHost) }
        context.sendBroadcast(Intent().setClassName(BuildConfig.APPLICATION_ID, MirrorHostReceiver::class.java.name)
            .putExtra("ticket", ticket).putExtra("control", bundle).putExtra("width", width)
            .putExtra("height", height).putExtra("display", displayId))
        System.err.println("SHELL_MIRROR_ENDPOINT_ANNOUNCED display=$displayId; credential_not_logged=true")
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = try {
        dispatch(code, data, reply)
    } catch (error: RuntimeException) { throw error }
    catch (error: Exception) { throw IllegalStateException("MIRROR_CONTROL_SYSTEM_REJECTED", error) }

    @Synchronized private fun dispatch(code: Int, data: Parcel, reply: Parcel?): Boolean {
        if (!MirrorCommandPolicy.caller(getCallingUid(), appUid, data.dataSize(), closed.get())) {
            throw SecurityException("MIRROR_CALLER_REJECTED")
        }
        data.enforceInterface(MirrorWire.DESCRIPTOR)
        if (data.readString() != ticket) { throw SecurityException("MIRROR_SESSION_REJECTED") }
        when (code) {
            MirrorWire.REGISTER -> {
                val next = data.readStrongBinder() ?: throw SecurityException("MIRROR_CALLBACK_MISSING")
                callback?.unlinkToDeath(death, 0)
                next.linkToDeath(death, 0)
                callback = next
            }
            MirrorWire.ATTACH -> {
                val lease = data.readLong()
                val w = data.readInt()
                val h = data.readInt()
                val surface = Surface.CREATOR.createFromParcel(data)
                sinks.attach(MirrorSinkMailbox.Sink(lease, surface, w, h))
                if (lease > inputLease && w in 1..8192 && h in 1..8192) {
                    cancelTouch()
                    inputLease = lease
                    inputAttached = true
                }
            }
            MirrorWire.DETACH -> {
                val lease = data.readLong()
                if (lease == inputLease) { cancelTouch(); inputAttached = false }
                sinks.detach(lease)
            }
            MirrorWire.TOUCH -> {
                val lease = data.readLong()
                val event = MotionEvent.CREATOR.createFromParcel(data)
                try {
                    if (lease == inputLease && inputAttached && renderedLease == lease) { touch(event) }
                } finally { event.recycle() }
            }
            MirrorWire.BACK -> {
                if (data.readLong() == inputLease && inputAttached && renderedLease == inputLease) {
                    val now = SystemClock.uptimeMillis()
                    for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                        inject(KeyEvent(now, now, action, KeyEvent.KEYCODE_BACK, 0))
                    }
                }
            }
            MirrorWire.ROUTE -> route.set(true)
            else -> return false
        }
        reply?.writeNoException()
        return true
    }

    @Synchronized private fun touch(event: MotionEvent) {
        if (!MirrorCommandPolicy.input(event.pointerCount, event.historySize, event.eventTime,
                SystemClock.uptimeMillis(), event.actionIndex)) {
            throw SecurityException("MIRROR_INPUT_LIMIT")
        }
        for (index in 0 until event.pointerCount) {
            val x = event.getX(index)
            val y = event.getY(index)
            if (!MirrorCommandPolicy.coordinate(x, y, width, height)) {
                cancelTouch()
                return
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { touchDown = true }
        if (!touchDown) { return }
        if (event.actionMasked !in setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP)) { return }
        inject(event)
        lastTouch = SystemClock.uptimeMillis()
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touchDown = false
        }
    }

    private fun inject(event: InputEvent) {
        val identity = clearCallingIdentity()
        try {
            setDisplay.invoke(event, displayId)
            if (inject.invoke(inputManager, event, 0) != true) { throw IOException("MIRROR_INPUT_REJECTED") }
        } finally { restoreCallingIdentity(identity) }
    }

    @Synchronized private fun cancelTouch() {
        if (!touchDown) { return }
        touchDown = false
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        try { inject(event) } finally { event.recycle() }
    }

    fun takeRoute(): Boolean = route.getAndSet(false)
    @Synchronized fun rendered(lease: Long) {
        if (renderedLease != lease) { cancelTouch() }
        renderedLease = lease
    }
    @Synchronized fun requireOwner() {
        if (ownerDied.get()) { throw IOException("MIRROR_APP_PROCESS_DIED") }
        if (touchDown && SystemClock.uptimeMillis() - lastTouch > 1500) { cancelTouch() }
    }

    @Synchronized fun notifyApp(action: Int) {
        val client = callback ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(MirrorWire.CALLBACK)
            data.writeString(ticket)
            client.transact(action, data, null, IBinder.FLAG_ONEWAY)
        } catch (error: android.os.RemoteException) {
            ownerDied.set(true)
        } finally { data.recycle() }
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) { return }
        try { cancelTouch() } finally {
            notifyApp(MirrorWire.CLOSED)
            callback?.unlinkToDeath(death, 0)
            callback = null
            sinks.close()
        }
    }
}
