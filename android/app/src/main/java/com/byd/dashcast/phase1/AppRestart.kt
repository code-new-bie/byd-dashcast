package com.byd.dashcast.phase1

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock

/** Restarts this app's process, the only way to release USB handles or threads whose cleanup was not
 * confirmed. An alarm survives the process and brings it back through [RestartReceiver] (Android 10: after >= 5 s). */
internal object AppRestart {
    const val EXTRA_SHOW = "show"
    const val DELAY_MILLIS = 1500L
    /** A second automatic restart this soon means restarting does not help; leave it to the user. */
    const val AUTOMATIC_GAP_MILLIS = 120_000L
    private const val STORE = "dashcast-restart"

    fun allowAutomatic(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        allowAutomatic(context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getLong("automatic", 0L), now)

    fun allowAutomatic(last: Long, now: Long): Boolean = last <= 0L || now < last || now - last >= AUTOMATIC_GAP_MILLIS

    /** Written synchronously: the process is killed right after. */
    @SuppressLint("ApplySharedPref")
    fun noteAutomatic(context: Context, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putLong("automatic", now).commit()
    }

    fun restart(context: Context, showUi: Boolean, log: (String) -> Unit) {
        val app = context.applicationContext
        val pending = PendingIntent.getBroadcast(app, 93,
            Intent(app, RestartReceiver::class.java).putExtra(EXTRA_SHOW, showUi),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        app.getSystemService(AlarmManager::class.java)
            .setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + DELAY_MILLIS, pending)
        log("PRODUCT_APP_RESTART show_ui=$showUi")
        Process.killProcess(Process.myPid())
    }
}

/** First thing in the restarted process: the old session's instrument side is still unconfirmed, so a new
 * start has to pass the instrument idle check first; then the saved switches are restored as after boot. */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        ProjectionService.restartedAfterLocalCleanup("APP_RESTARTED_AFTER_UNCONFIRMED_LOCAL_CLEANUP")
        val show = intent.getBooleanExtra(AppRestart.EXTRA_SHOW, false)
        val pending = goAsync()
        Thread({
            val controller = ProductController.get(app)
            try {
                controller.recordExternal("PRODUCT_APP_RESTARTED show_ui=$show")
                controller.restoreSavedConnections()
                if (show) { controller.reopenUi() }
            } catch (error: Exception) {
                if (error is InterruptedException) { Thread.currentThread().interrupt() }
                controller.recordExternal("PRODUCT_APP_RESTART_RESUME_FAILED type=${error.javaClass.simpleName}")
            } finally { pending.finish() }
        }, "dashcast-restart").start()
    }
}
