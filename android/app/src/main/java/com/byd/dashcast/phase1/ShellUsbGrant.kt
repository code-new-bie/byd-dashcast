package com.byd.dashcast.phase1

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Grants this app access to the instrument's USB device from the shell (which holds MANAGE_USB on the
 * head unit), so the system "allow access" dialog is not shown after every NCM re-enumeration.
 * The shell side only grants the exact device name and VID/PID the app already identified as the instrument.
 */
object ShellUsbGrant {
    private val NAME = Regex("/dev/bus/usb/\\d{3}/\\d{3}")
    private const val DONE = "USB_SHELL_GRANT completed=true"

    internal fun command(apk: String, device: UsbDevice, uid: Int): String =
        // Reuse the installed-path validation/quoting; only replace its fixed main entry point.
        ShellCaptureCommand.create(apk, 1).substringBefore(" com.byd.dashcast.phase1.ShellScreenCapture ") +
            " com.byd.dashcast.phase1.ShellUsbGrant ${device.deviceName} ${device.vendorId} ${device.productId} $uid"

    internal fun validArguments(args: Array<String>): Boolean = args.size == 4 && NAME.matches(args[0]) &&
        args[1].toIntOrNull() in 0..0xffff && args[2].toIntOrNull() in 0..0xffff && (args[3].toIntOrNull() ?: 0) >= 10000

    /** App side: true when the shell reported the grant. Callers still re-check UsbManager.hasPermission. */
    internal fun request(app: Context, device: UsbDevice, log: (String) -> Unit): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "USB grant runs ADB I/O" }
        if (!NAME.matches(device.deviceName)) { log("PRODUCT_USB_SHELL_GRANT completed=false reason=device_name"); return false }
        val output = AdbTextProbe(2048)
        return try {
            AdbShellClient("127.0.0.1", 5555, 8000, AndroidAdbIdentity.load(), { line -> log(line) }, AtomicBoolean())
                .executeShellV2(command(app.applicationInfo.sourceDir, device, app.applicationInfo.uid), output)
            val granted = output.text.contains(DONE)
            log("PRODUCT_USB_SHELL_GRANT completed=$granted")
            granted
        } catch (error: Exception) {
            if (error is InterruptedException) { Thread.currentThread().interrupt() }
            log("PRODUCT_USB_SHELL_GRANT completed=false type=${error.javaClass.simpleName}")
            false
        }
    }

    /** Shell side, started through app_process: name vendorId productId uid. */
    @JvmStatic fun main(args: Array<String>) {
        try {
            check(Process.myUid() == 2000) { "shell uid required" }
            if (!validArguments(args)) { throw IllegalArgumentException("arguments") }
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "usb") as? IBinder ?: throw IOException("USB_SERVICE_MISSING")
            val type = Class.forName("android.hardware.usb.IUsbManager")
            val usb = Class.forName("android.hardware.usb.IUsbManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val devices = Bundle()
            type.getMethod("getDeviceList", Bundle::class.java).invoke(usb, devices)
            @Suppress("DEPRECATION")
            val device = devices.getParcelable<UsbDevice>(args[0]) ?: throw IOException("USB_DEVICE_MISSING")
            if (device.vendorId != args[1].toInt() || device.productId != args[2].toInt()) {
                throw IOException("USB_DEVICE_CHANGED")
            }
            type.getMethod("grantDevicePermission", UsbDevice::class.java, Int::class.javaPrimitiveType)
                .invoke(usb, device, args[3].toInt())
            println(DONE)
        } catch (error: Throwable) {
            val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
            println("USB_SHELL_GRANT completed=false type=${cause.javaClass.simpleName}")
        }
    }
}
