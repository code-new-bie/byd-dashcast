package com.byd.dashcast.phase1

import android.os.IBinder
import android.os.Parcel
import java.io.IOException

/** Private, per-process Binder rendezvous; the only public contract is the display name. */
internal object ExternalDisplayWire {
    const val DESCRIPTOR = "com.byd.dashcast.ExternalDisplay.v1"
    const val REGISTER = 1
    const val SNAPSHOT = 2
    const val ATTACH = 3
    const val DETACH = 4
    const val RELEASE = 5
    const val CAPTURE_READY = 6
    const val CAPTURE_STATUS = 7
    const val CAPTURE_DETACH = 8
    const val WIDTH = 1696
    const val HEIGHT = 456

    fun <T> call(endpoint: IBinder, ticket: String, code: Int,
            write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(DESCRIPTOR)
            request.writeString(ticket)
            write(request)
            if (!endpoint.transact(code, request, reply, 0)) { throw IOException("EXTERNAL_ENDPOINT_REJECTED") }
            reply.readException()
            return read(reply)
        } finally { request.recycle(); reply.recycle() }
    }

    fun command(endpoint: IBinder, ticket: String, code: Int, write: (Parcel) -> Unit = {}) =
        call(endpoint, ticket, code, write) { Unit }
}
