package com.byd.dashcast.phase1

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** When "开机启动" is on, restores the saved connection switches after boot.
 * It never opens the UI and never starts a projection by itself.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) { return }
        val app = context.applicationContext
        if (!ProductSettings(app).bootStart) { return }
        val pending = goAsync()
        Thread({
            val controller = ProductController.get(app)
            try {
                controller.recordExternal("PRODUCT_BOOT_START restore=true")
                controller.restoreSavedConnections()
                // Keep the broadcast open while the connection switch runs, within the receiver's time budget.
                val deadline = System.nanoTime() + BootStart.HOLD_NANOS
                while (controller.busy && System.nanoTime() < deadline) { Thread.sleep(200) }
            } catch (error: Exception) {
                if (error is InterruptedException) { Thread.currentThread().interrupt() }
                controller.recordExternal("PRODUCT_BOOT_START_FAILED type=${error.javaClass.simpleName}")
            } finally { pending.finish() }
        }, "dashcast-boot").start()
    }
}

internal object BootStart {
    const val HOLD_NANOS = 8_000_000_000L
    // The head unit blocks third-party auto-start until the user allows it on this vendor page.
    private val vendor = ComponentName("com.byd.appstartmanagement", "com.byd.appstartmanagement.frame.AppStartManagement")

    fun openVendorSettings(activity: Activity): Boolean = try {
        activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(vendor))
        true
    } catch (error: ActivityNotFoundException) { false } catch (error: SecurityException) { false }
}
