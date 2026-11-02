package com.byd.dashcast.phase1;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Explicitly authorized OEM relay workflow; one send, no raw frames or automatic write retries.
 * @author Codex
 * @date 2026/10/10
 */
public final class NavigationUsbRelay {
    private final Backend backend;
    private final AtomicBoolean cancelled;
    private final Consumer<String> log;

    public NavigationUsbRelay(Backend backend, AtomicBoolean cancelled, Consumer<String> log) {
        this.backend = Objects.requireNonNull(backend);
        this.cancelled = Objects.requireNonNull(cancelled);
        this.log = Objects.requireNonNull(log);
    }

    /** @param command only NCM/Host; @throws IOException if checks fail (submission never certifies USB role) */
    public void request(InstrumentUsbCommand command) throws IOException {
        Objects.requireNonNull(command);
        checkCancelled();
        backend.verifyProfile();
        checkCancelled();
        String uid = backend.executeChecked("id -u").trim();
        if (!"2000".equals(uid) && !"0".equals(uid)) {
            throw new IOException("ADB 执行身份不是 shell/root；未发送。uid=" + uid);
        }
        log.accept("ADB_IDENTITY uid=" + uid + "; original navigation relay, not a direct setBuffer call");
        checkCancelled();
        String user = backend.executeChecked("am get-current-user").trim();
        if (!"0".equals(user)) {
            throw new IOException("当前 Android 用户不是已核对的 user 0；未发送 USB 命令。请导出日志。");
        }
        log.accept("NAV_ANDROID_USER=0; matches OEM service UID=1000");
        checkCancelled();
        backend.executeChecked(NavigationUsbProtocol.START_SERVICE);
        log.accept("NAV_SERVICE_START_REQUESTED; waiting for the live receiver");
        awaitReceiver();
        checkCancelled();
        String output = backend.executeChecked(NavigationUsbProtocol.broadcastCommand(command));
        log.accept("NAV_BROADCAST_SUBMITTED " + command.commandText()
                + "; submission only, service processing / USB role NOT confirmed");
        if (!output.contains("Broadcast completed:")) {
            log.accept("NAV_BROADCAST_OUTPUT_UNEXPECTED; do not resend automatically");
        }
    }

    private void awaitReceiver() throws IOException {
        for (int attempt = 0; attempt < 6; attempt++) {
            checkCancelled();
            if (backend.receiverRegistered()) {
                log.accept("NAV_RECEIVER_REGISTERED package=" + NavigationUsbProtocol.PACKAGE);
                return;
            }
            backend.pause();
        }
        throw new IOException("原厂导航接收器未注册；未发送 USB 命令。请导出日志。");
    }

    private void checkCancelled() throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new IOException("操作已取消；不再发送后续命令（已发送的命令不能撤销）");
        }
    }

    /** Platform boundary suitable for deterministic offline tests. */
    public interface Backend {
        /** @throws IOException if runtime service code or framework profile is unverified */
        void verifyProfile() throws IOException;
        /** @param command fixed internal command; @return stdout; @throws IOException on nonzero/unknown exit */
        String executeChecked(String command) throws IOException;
        /** @return live package/action receiver; @throws IOException on failed probe */
        boolean receiverRegistered() throws IOException;
        /** @throws IOException if polling is cancelled */
        void pause() throws IOException;
    }
}
