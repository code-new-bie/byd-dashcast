package com.byd.dashcast.phase1

import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.view.Surface
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** User-owned standby display. No encoder, USB, instrument command or composition lease. */
object ShellExternalDisplay {
    @JvmStatic fun main(args: Array<String>) {
        ShellCaptureOutput.isolateFrameworkDiagnostics()
        var status = 1
        try {
            require(args.size == 1 && DuplexTicket.valid(args[0]))
            if (Process.myUid() != 2000 || Build.VERSION.SDK_INT != 29) {
                throw IOException("EXTERNAL_REQUIRES_AUTHORIZED_ANDROID10_SHELL")
            }
            if (Looper.myLooper() == null) { Looper.prepareMainLooper() }
            CaptureStopControl(System.`in`).use { stop ->
                stop.start()
                ExternalHostEndpoint(args[0]).use { host ->
                    host.announce()
                    var pulse = 0L
                    val output = ShellCaptureOutput.videoStream()
                    while (!stop.stopped() && !host.stopping.get()) {
                        host.requireRegistration()
                        host.observe()
                        val now = System.nanoTime()
                        if (now-pulse >= 1_000_000_000L) {
                            output.write(1); output.flush(); pulse=now
                        }
                        Thread.sleep(400)
                    }
                }
            }
            status = 0
        } catch (failure: Exception) {
            System.err.println("EXTERNAL_DISPLAY_FAILED type=${failure.javaClass.simpleName} detail=${failure.message}")
        } finally { System.err.flush() }
        // Framework Binder/Looper threads must not keep this owned helper alive.
        kotlin.system.exitProcess(status)
    }
}

internal class ExternalHostEndpoint(private val ticket: String) : Binder(), AutoCloseable {
    private val context = RemoteDisplaySession.shellContext()
    private val appUid = context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid
    private val user = appUid / 100000
    private var worker: HandlerThread? = null
    private var reader: ImageReader? = null
    private val createdAt = System.nanoTime()
    private var display: VirtualDisplay? = null
    private var client: IBinder? = null
    private var capture: IBinder? = null
    private var captureTicket: String? = null
    private var captureSurface: Surface? = null
    private var observed = 0L
    private var owners: Set<Int> = emptySet()
    private var mainMapFocused = false
    private var observationFailure: Exception? = null
    private var closed = false
    val stopping = AtomicBoolean()
    private val clientDeath = IBinder.DeathRecipient { stopping.set(true) }
    private val captureDeath = IBinder.DeathRecipient { synchronized(this) { detach() } }

    init {
        try {
            NamedDisplayContract.requireAvailable(context,allowNative=true)
            val frames = HandlerThread("dashcast-standby-frames").apply { start() }
            worker = frames
            val images = ImageReader.newInstance(ExternalDisplayWire.WIDTH, ExternalDisplayWire.HEIGHT,
                PixelFormat.RGBA_8888, 2)
            reader = images
            images.setOnImageAvailableListener({ source -> source.acquireLatestImage()?.close() }, Handler(frames.looper))
            display = RemoteDisplaySession.createVirtualDisplay(context, images.surface,
                ExternalDisplayWire.WIDTH, ExternalDisplayWire.HEIGHT, 160, 1 or 2 or 8,
                NamedDisplayContract.NAME)
            val id = display?.display?.displayId ?: -1
            if (id <= 0) { throw IOException("EXTERNAL_DISPLAY_NOT_CREATED") }
            System.err.println("EXTERNAL_DISPLAY_CREATED display=$id; name=${NamedDisplayContract.NAME}")
        } catch (error: Exception) {
            try { close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    @Synchronized fun requireRegistration() {
        // Only an unclaimed startup is bounded; registered standby/video sessions
        // have no duration limit. A lost rendezvous must not leave a named orphan.
        if (client == null && System.nanoTime() - createdAt >= 10_000_000_000L) {
            throw IOException("EXTERNAL_HOST_NEVER_REGISTERED")
        }
    }

    fun announce() {
        val control = Bundle().apply { putBinder("endpoint", this@ExternalHostEndpoint) }
        context.sendBroadcast(Intent().setClassName(BuildConfig.APPLICATION_ID, ExternalDisplayReceiver::class.java.name)
            .putExtra("kind", "host").putExtra("ticket", ticket).putExtra("control", control))
        System.err.println("EXTERNAL_DISPLAY_READY name=${NamedDisplayContract.NAME} standby_only=true")
    }

    fun observe() {
        try {
            val process = ProcessBuilder("/system/bin/dumpsys", "-t", "2", "window", "-a")
                .redirectErrorStream(true).start()
            val snapshot = try { BoundedProcessOutput.read(process, 2500, NamedWindowPolicy.MAX_BYTES, true) }
                finally { if (process.isAlive) { process.destroyForcibly() } }
            if (process.exitValue() != 0) { throw IOException("EXTERNAL_WINDOW_QUERY_REJECTED") }
            val id = synchronized(this) { display?.display?.displayId ?: -1 }
            if (id <= 0) { return }
            val next = NamedWindowPolicy.owners(snapshot, id, appUid, user)
            val mapFocused = NamedWindowPolicy.mainMapFocused(snapshot, user)
            synchronized(this) { owners = next; mainMapFocused = mapFocused; observed = System.nanoTime(); observationFailure = null }
        } catch (error: Exception) {
            synchronized(this) {
                if (observationFailure?.message != error.message) {
                    System.err.println("EXTERNAL_WINDOW_OBSERVATION_FAILED type=${error.javaClass.simpleName} detail=${error.message}")
                }
                observationFailure = error
            }
        }
    }

    @Synchronized override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (getCallingUid() != appUid || closed || data.dataSize() > 8192) {
            throw SecurityException("EXTERNAL_CALLER_REJECTED")
        }
        data.enforceInterface(ExternalDisplayWire.DESCRIPTOR)
        if (data.readString() != ticket) { throw SecurityException("EXTERNAL_TICKET_REJECTED") }
        when (code) {
            ExternalDisplayWire.REGISTER -> {
                val owner = data.readStrongBinder() ?: throw SecurityException("EXTERNAL_OWNER_MISSING")
                client?.unlinkToDeath(clientDeath, 0)
                owner.linkToDeath(clientDeath, 0); client = owner
            }
            ExternalDisplayWire.SNAPSHOT -> {
                reply?.writeNoException()
                reply?.writeInt(display?.display?.displayId ?: -1)
                val healthy = observed != 0L && observationFailure == null && System.nanoTime()-observed < 3_000_000_000L
                reply?.writeInt(if (healthy) 1 else 0)
                reply?.writeInt(owners.size)
                for (uid in owners.sorted()) { reply?.writeInt(uid) }
                reply?.writeInt(if (mainMapFocused && healthy) 1 else 0)
                return true
            }
            ExternalDisplayWire.ATTACH -> {
                val nextTicket = data.readString()
                val nextOwner = data.readStrongBinder() ?: throw SecurityException("EXTERNAL_CAPTURE_OWNER_MISSING")
                val surface = Surface.CREATOR.createFromParcel(data)
                try {
                    if (!DuplexTicket.valid(nextTicket) || captureTicket != null || owners.size != 1 ||
                        observationFailure != null || observed == 0L || System.nanoTime()-observed >= 3_000_000_000L) {
                        throw IllegalStateException("EXTERNAL_ONE_APPLICATION_REQUIRED")
                    }
                    nextOwner.linkToDeath(captureDeath, 0)
                    display!!.surface = surface
                    capture = nextOwner; captureTicket = nextTicket; captureSurface = surface
                } catch (error: Exception) { surface.release(); throw error }
            }
            ExternalDisplayWire.DETACH -> {
                if (data.readString() == captureTicket) { detach() }
            }
            ExternalDisplayWire.RELEASE -> { if (captureTicket == null) { stopping.set(true) } }
            else -> return false
        }
        reply?.writeNoException()
        return true
    }

    private fun detach() {
        if (captureTicket == null) { return }
        display?.surface = reader?.surface ?: throw IOException("EXTERNAL_READER_CLOSED")
        capture?.unlinkToDeath(captureDeath, 0)
        captureSurface?.release()
        captureSurface = null; capture = null; captureTicket = null
    }

    @Synchronized override fun close() {
        if (closed) { return }
        closed = true
        var failure: Exception? = null
        fun release(action: () -> Unit) {
            try { action() } catch (error: Exception) {
                if (failure == null) { failure = error } else { failure!!.addSuppressed(error) }
            }
        }
        release { detach() }
        release { display?.release() }; display = null
        release { reader?.setOnImageAvailableListener(null, null); reader?.close() }; reader = null
        release { worker?.let { frames ->
            frames.quitSafely(); frames.join(2000)
            if (frames.isAlive) { throw IOException("EXTERNAL_FRAME_WORKER_STILL_ALIVE") }
        } }; worker = null
        release { client?.unlinkToDeath(clientDeath, 0) }; client = null
        failure?.let { throw it }
        System.err.println("EXTERNAL_DISPLAY_RESOURCES_RELEASED")
    }
}
