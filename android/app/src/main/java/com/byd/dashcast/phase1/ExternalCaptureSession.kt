package com.byd.dashcast.phase1

import android.content.Intent
import android.media.MediaCodec
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.view.Surface
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Borrows only the existing named display's output surface, not its lifecycle. */
internal class ExternalCaptureSession(surface: Surface, private val ticket: String) : Binder(), RemoteCaptureSession {
    private val context = RemoteDisplaySession.shellContext()
    private val appUid = context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid
    private val ready = CountDownLatch(1)
    @Volatile private var control: IBinder? = null
    @Volatile private var rejected = false
    private var closed = false
    private var observedAt = 0L

    init {
        require(DuplexTicket.valid(ticket))
        val bundle = Bundle().apply { putBinder("endpoint", this@ExternalCaptureSession) }
        context.sendBroadcast(Intent().setClassName(BuildConfig.APPLICATION_ID, ExternalDisplayReceiver::class.java.name)
            .putExtra("kind", "capture").putExtra("ticket", ticket).putExtra("control", bundle)
            .putExtra("surface", surface))
        if (!ready.await(4, TimeUnit.SECONDS) || rejected || control == null) {
            throw IOException("EXTERNAL_CAPTURE_ATTACH_UNCONFIRMED")
        }
        System.err.println("SHELL_EXTERNAL_CAPTURE_ATTACHED name=${NamedDisplayContract.NAME}; display_lifetime=standby_service")
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (getCallingUid() != appUid || code != ExternalDisplayWire.CAPTURE_READY || data.dataSize()>1024) {
            throw SecurityException("EXTERNAL_CAPTURE_CALLER_REJECTED")
        }
        data.enforceInterface(ExternalDisplayWire.DESCRIPTOR)
        if (data.readString() != ticket) { throw SecurityException("EXTERNAL_CAPTURE_TICKET_REJECTED") }
        rejected = data.readInt() != 1
        control = data.readStrongBinder()
        ready.countDown()
        reply?.writeNoException()
        return true
    }

    override fun update(codec: MediaCodec) {
        val endpoint = control ?: throw IOException("EXTERNAL_CAPTURE_CONTROL_MISSING")
        val now = System.nanoTime()
        if (now-observedAt < 200_000_000L) { return }
        val healthy = ExternalDisplayWire.call(endpoint,ticket,ExternalDisplayWire.CAPTURE_STATUS) { it.readInt()==1 }
        if (!healthy) { throw IOException("EXTERNAL_APPLICATION_CHANGED_OR_CLOSED") }
        observedAt = now
    }
    override fun acceptFrame(ptsMicros: Long, key: Boolean): Boolean = !closed
    override fun close() {
        if (closed) { return }
        closed = true
        control?.let { ExternalDisplayWire.command(it,ticket,ExternalDisplayWire.CAPTURE_DETACH) }
        control = null
        System.err.println("SHELL_EXTERNAL_CAPTURE_DETACHED; named_display_preserved=true")
    }
}
