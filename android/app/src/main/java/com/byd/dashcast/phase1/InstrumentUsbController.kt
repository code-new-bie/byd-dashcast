package com.byd.dashcast.phase1

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Locale
import java.util.Objects
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import java.util.zip.ZipFile

/**
 * Runtime-verified OEM navigation relay, executed through normally authorized local ADB.
 * Never directly calls a denied BYDAuto setter or synthesizes raw CAN frames.
 * @author Codex
 * @date 2026/10/10
 */
class InstrumentUsbController(
    context: Context?,
    client: AdbShellClient?,
    cancelled: AtomicBoolean?,
    log: Consumer<String>?,
) : NavigationUsbRelay.Backend {
    private val context: Context = Objects.requireNonNull(context)!!
    private val client: AdbShellClient = Objects.requireNonNull(client)!!
    private val cancelled: AtomicBoolean = Objects.requireNonNull(cancelled)!!
    private val log: Consumer<String> = Objects.requireNonNull(log)!!

    /** @param command enum-only request; @throws IOException if safety/profile/auth/readiness checks fail */
    @Throws(IOException::class)
    fun request(command: InstrumentUsbCommand?) {
        NavigationUsbRelay(this, cancelled, log).request(command)
    }

    @Throws(IOException::class)
    override fun verifyProfile() {
        try {
            val manager = context.packageManager
            val component = ComponentName(NavigationUsbProtocol.PACKAGE, NavigationUsbProtocol.SERVICE)
            val service = manager.getServiceInfo(component, 0)
            val app: ApplicationInfo? = service.applicationInfo
            log.accept("NAV_SERVICE_LOOKUP enabled=" + service.enabled + " exported=" + service.exported +
                " version=" + manager.getPackageInfo(NavigationUsbProtocol.PACKAGE, 0).versionName)
            if (app == null || app.uid != 1000 || !app.enabled || !service.enabled || !service.exported ||
                app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                throw IOException("原厂导航服务身份/状态不匹配；未发送。")
            }
            val feature = Class.forName(FEATURE_CLASS).getField(FEATURE_FIELD).getInt(null)
            val hash = serviceDexHash(app.sourceDir)
            log.accept("NAV_PROFILE package=" + NavigationUsbProtocol.PACKAGE + " uid=" + app.uid +
                " classes2_sha256=" + hash + " next_path_feature=" + feature)
            NavigationUsbProtocol.verify(hash, feature)
        } catch (error: PackageManager.NameNotFoundException) {
            throw unavailable(error)
        } catch (error: ReflectiveOperationException) {
            throw unavailable(error)
        } catch (error: LinkageError) {
            throw unavailable(error)
        } catch (error: SecurityException) {
            throw unavailable(error)
        }
    }

    private fun unavailable(error: Throwable): IOException = IOException(
        "原厂导航中继不可用/被拒绝；未发送 USB 命令。" + error.javaClass.simpleName + ": " + error.message, error)

    @Throws(IOException::class)
    override fun executeChecked(command: String): String {
        val output = AdbTextProbe(96 * 1024)
        client.executeShell(AdbCommandOutput.wrap(command), output)
        if (output.isTruncated) {
            throw IOException("ADB output exceeded the bound; command result unconfirmed")
        }
        return AdbCommandOutput.requireSuccess(output.text)
    }

    @Throws(IOException::class)
    override fun receiverRegistered(): Boolean {
        val probe = NavigationReceiverProbe()
        client.executeShell(NavigationUsbProtocol.RECEIVERS, probe)
        val ready = probe.isRegistered()
        log.accept("NAV_RECEIVER_CHECK registered=$ready")
        return ready
    }

    @Throws(IOException::class)
    override fun pause() {
        if (cancelled.get()) {
            throw IOException("Navigation relay operation cancelled")
        }
        try {
            Thread.sleep(250)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Navigation relay wait interrupted", error)
        }
    }

    companion object {
        private const val MAX_DEX_BYTES = 16 * 1024 * 1024
        private const val FEATURE_CLASS = "android.hardware.bydauto.BYDAutoFeatureIds"
        private const val FEATURE_FIELD = "INSTRUMENT_TARGET_NEXT_PATHNAME_INFO_SET"
        private const val COMMON_PERMISSION = "android.permission.BYDAUTO_INSTRUMENT_COMMON"
        private const val SET_PERMISSION = "android.permission.BYDAUTO_INSTRUMENT_SET"

        /** @param context caller; @return diagnostic app grants; the OEM relay does not require these grants */
        @JvmStatic
        fun permissionSummary(context: Context): String =
            "common=" + (context.checkSelfPermission(COMMON_PERMISSION) == PackageManager.PERMISSION_GRANTED) +
                " set=" + (context.checkSelfPermission(SET_PERMISSION) == PackageManager.PERMISSION_GRANTED)

        @Throws(IOException::class)
        private fun serviceDexHash(path: String?): String {
            if (path == null) {
                throw IOException("Original navigation APK path unavailable")
            }
            try {
                ZipFile(path).use { archive ->
                    val entry = archive.getEntry("classes2.dex")
                    if (entry == null || entry.size > MAX_DEX_BYTES) {
                        throw IOException("Original navigation code profile is unavailable/too large")
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    archive.getInputStream(entry).use { input ->
                        val bytes = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(bytes)
                            if (count < 0) {
                                break
                            }
                            total += count
                            if (total > MAX_DEX_BYTES) {
                                throw IOException("Navigation code exceeded the bounded verification size")
                            }
                            digest.update(bytes, 0, count)
                        }
                    }
                    val result = StringBuilder(64)
                    for (value in digest.digest()) {
                        result.append(String.format(Locale.US, "%02x", value.toInt() and 0xff))
                    }
                    return result.toString()
                }
            } catch (error: GeneralSecurityException) {
                throw IOException("Navigation code verification unavailable", error)
            }
        }
    }
}
