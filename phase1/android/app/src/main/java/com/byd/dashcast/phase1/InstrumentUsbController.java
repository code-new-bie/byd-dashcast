package com.byd.dashcast.phase1;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Runtime-verified OEM navigation relay, executed through normally authorized local ADB.
 * Never directly calls a denied BYDAuto setter or synthesizes raw CAN frames.
 * @author Codex
 * @date 2026/10/10
 */
public final class InstrumentUsbController implements NavigationUsbRelay.Backend {
    private static final int MAX_DEX_BYTES = 16 * 1024 * 1024;
    private static final String FEATURE_CLASS = "android.hardware.bydauto.BYDAutoFeatureIds";
    private static final String FEATURE_FIELD = "INSTRUMENT_TARGET_NEXT_PATHNAME_INFO_SET";
    private static final String COMMON_PERMISSION = "android.permission.BYDAUTO_INSTRUMENT_COMMON";
    private static final String SET_PERMISSION = "android.permission.BYDAUTO_INSTRUMENT_SET";
    private final Context context;
    private final AdbShellClient client;
    private final AtomicBoolean cancelled;
    private final Consumer<String> log;

    public InstrumentUsbController(Context context, AdbShellClient client,
            AtomicBoolean cancelled, Consumer<String> log) {
        this.context = Objects.requireNonNull(context);
        this.client = Objects.requireNonNull(client);
        this.cancelled = Objects.requireNonNull(cancelled);
        this.log = Objects.requireNonNull(log);
    }

    /** @param command enum-only request; @throws IOException if safety/profile/auth/readiness checks fail */
    public void request(InstrumentUsbCommand command) throws IOException {
        new NavigationUsbRelay(this, cancelled, log).request(command);
    }

    @Override
    public void verifyProfile() throws IOException {
        try {
            PackageManager manager = context.getPackageManager();
            ComponentName component = new ComponentName(NavigationUsbProtocol.PACKAGE, NavigationUsbProtocol.SERVICE);
            ServiceInfo service = manager.getServiceInfo(component, 0);
            ApplicationInfo app = service.applicationInfo;
            log.accept("NAV_SERVICE_LOOKUP enabled=" + service.enabled + " exported=" + service.exported
                    + " version=" + manager.getPackageInfo(NavigationUsbProtocol.PACKAGE, 0).versionName);
            if (app == null || app.uid != 1000 || !app.enabled || !service.enabled || !service.exported
                    || (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) {
                throw new IOException("原厂导航服务身份/状态不匹配；未发送。");
            }
            int feature = Class.forName(FEATURE_CLASS).getField(FEATURE_FIELD).getInt(null);
            String hash = serviceDexHash(app.sourceDir);
            log.accept("NAV_PROFILE package=" + NavigationUsbProtocol.PACKAGE + " uid=" + app.uid
                    + " classes2_sha256=" + hash + " next_path_feature=" + feature);
            NavigationUsbProtocol.verify(hash, feature);
        } catch (PackageManager.NameNotFoundException | ReflectiveOperationException
                | LinkageError | SecurityException error) {
            throw new IOException("原厂导航中继不可用/被拒绝；未发送 USB 命令。"
                    + error.getClass().getSimpleName() + ": " + error.getMessage(), error);
        }
    }

    @Override
    public String executeChecked(String command) throws IOException {
        AdbTextProbe output = new AdbTextProbe(96 * 1024);
        client.executeShell(AdbCommandOutput.wrap(command), output);
        if (output.isTruncated()) {
            throw new IOException("ADB output exceeded the bound; command result unconfirmed");
        }
        return AdbCommandOutput.requireSuccess(output.getText());
    }

    @Override
    public boolean receiverRegistered() throws IOException {
        NavigationReceiverProbe probe = new NavigationReceiverProbe();
        client.executeShell(NavigationUsbProtocol.RECEIVERS, probe);
        boolean ready = probe.isRegistered();
        log.accept("NAV_RECEIVER_CHECK registered=" + ready);
        return ready;
    }

    @Override
    public void pause() throws IOException {
        if (cancelled.get()) {
            throw new IOException("Navigation relay operation cancelled");
        }
        try {
            Thread.sleep(250);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Navigation relay wait interrupted", error);
        }
    }

    /** @param context caller; @return diagnostic app grants; the OEM relay does not require these grants */
    public static String permissionSummary(Context context) {
        return "common=" + (context.checkSelfPermission(COMMON_PERMISSION) == PackageManager.PERMISSION_GRANTED)
                + " set=" + (context.checkSelfPermission(SET_PERMISSION) == PackageManager.PERMISSION_GRANTED);
    }

    private static String serviceDexHash(String path) throws IOException {
        if (path == null) {
            throw new IOException("Original navigation APK path unavailable");
        }
        try (ZipFile archive = new ZipFile(path)) {
            ZipEntry entry = archive.getEntry("classes2.dex");
            if (entry == null || entry.getSize() > MAX_DEX_BYTES) {
                throw new IOException("Original navigation code profile is unavailable/too large");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = archive.getInputStream(entry)) {
                byte[] bytes = new byte[8192];
                int total = 0;
                int count;
                while ((count = input.read(bytes)) >= 0) {
                    total += count;
                    if (total > MAX_DEX_BYTES) {
                        throw new IOException("Navigation code exceeded the bounded verification size");
                    }
                    digest.update(bytes, 0, count);
                }
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format(Locale.US, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (GeneralSecurityException error) {
            throw new IOException("Navigation code verification unavailable", error);
        }
    }
}
