package com.byd.dashcast.phase1

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** When "开机启动" is on, restores the saved connection switches after boot; with "上电后恢复上次投屏" also starts
 * again the app a switch-off interrupted, once the instrument is connected. Otherwise it never opens the UI.
 *
 * A BYD head unit's switch-off is a quick-boot shutdown: every app is killed and the unit suspends; power-on sends
 * BOOT_COMPLETED again with from_quickboot=true. So this runs at every power-on, not only after a real boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) { return }
        val app = context.applicationContext
        if (!ProductSettings(app).bootStart) { return }
        val quickBoot = intent.getBooleanExtra(BootStart.EXTRA_FROM_QUICKBOOT, false)
        val pending = goAsync()
        Thread({
            val controller = ProductController.get(app)
            try {
                controller.recordExternal("PRODUCT_BOOT_START restore=true from_quickboot=$quickBoot")
                controller.restoreSavedConnections()
                // Keep the broadcast open while the connection switch runs, within the receiver's time budget.
                val deadline = System.nanoTime() + BootStart.HOLD_NANOS
                while (controller.busy && System.nanoTime() < deadline) { Thread.sleep(200) }
                controller.afterPowerOn(if (quickBoot) "power_on" else "boot", false)
            } catch (error: Exception) {
                if (error is InterruptedException) { Thread.currentThread().interrupt() }
                controller.recordExternal("PRODUCT_BOOT_START_FAILED type=${error.javaClass.simpleName}")
            } finally { pending.finish() }
        }, "dashcast-boot").start()
    }
}

internal object BootStart {
    const val HOLD_NANOS = 8_000_000_000L
    const val EXTRA_FROM_QUICKBOOT = "from_quickboot"
    // The head unit blocks third-party auto-start until the user allows it on this vendor page.
    private val vendor = ComponentName("com.byd.appstartmanagement", "com.byd.appstartmanagement.frame.AppStartManagement")

    fun openVendorSettings(activity: Activity): Boolean = try {
        activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(vendor))
        true
    } catch (error: ActivityNotFoundException) { false } catch (error: SecurityException) { false }
}
