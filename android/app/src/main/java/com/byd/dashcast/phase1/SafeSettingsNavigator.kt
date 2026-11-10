package com.byd.dashcast.phase1

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import com.byd.dashcast.phase1.ncm.LogHub

/** Some OEM Settings packages omit or protect the accessibility activity.
 * Never grants permissions, writes secure settings or lets a missing external UI crash our page.
 * @author Codex
 * @date 2026/10/25
 */
internal object SafeSettingsNavigator {
    enum class Result { ACCESSIBILITY, GENERAL, UNAVAILABLE }
    fun choose(open: (String) -> Boolean): Result {
        if (open(Settings.ACTION_ACCESSIBILITY_SETTINGS)) { return Result.ACCESSIBILITY }
        if (open(Settings.ACTION_SETTINGS)) { return Result.GENERAL }
        return Result.UNAVAILABLE
    }

    fun open(activity: Activity) {
        val result = choose { action ->
            val intent = Intent(action)
            val info = activity.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            if (info == null || !info.enabled || !info.exported ||
                !info.permission.isNullOrEmpty() && activity.checkSelfPermission(info.permission) != PackageManager.PERMISSION_GRANTED) {
                false
            } else {
                try { activity.startActivity(intent); true }
                catch (error: ActivityNotFoundException) {
                    LogHub.w("settings", "SETTINGS_UI_UNAVAILABLE type=${error.javaClass.simpleName}"); false
                } catch (error: SecurityException) {
                    LogHub.w("settings", "SETTINGS_UI_DENIED type=${error.javaClass.simpleName}"); false
                }
            }
        }
        LogHub.i("settings", "ACCESSIBILITY_SETTINGS_RESULT=$result; no_secure_setting_written=true")
        if (result != Result.ACCESSIBILITY) {
            Toast.makeText(activity, if (result == Result.GENERAL)
                "已打开系统设置，请查找“无障碍”。找不到此选项也可继续使用投屏。" else
                "此车机未提供无障碍设置入口，可返回继续使用投屏。", Toast.LENGTH_LONG).show()
        }
    }
}
