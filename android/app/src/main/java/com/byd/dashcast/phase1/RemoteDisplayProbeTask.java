package com.byd.dashcast.phase1;

import android.content.Context;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** A foreground-service-owned, five-second virtual display probe; no instrument video or file.
 * @author Codex
 * @date 2026/10/20
 */
final class RemoteDisplayProbeTask implements ProjectionSessionController.Task {
    private final Context context;
    private final ProjectionTarget target;
    private final Consumer<String> log;

    RemoteDisplayProbeTask(Context context, ProjectionTarget target, Consumer<String> log) {
        this.context = context.getApplicationContext();
        this.target = java.util.Objects.requireNonNull(target);
        this.log = java.util.Objects.requireNonNull(log);
        if (!target.isRemote()) {
            throw new IllegalArgumentException("Remote probe requires a selected application");
        }
    }

    @Override
    public void run(AtomicBoolean stop, AtomicBoolean abort, Runnable streaming, Runnable cleaning) throws IOException {
        AvcReceiverContract contract = new AvcReceiverContract();
        int[] frames = {0};
        VideoPatternProbe.Observer observer = new VideoPatternProbe.Observer() {
            @Override public void log(String message) { log.accept(message); }
            @Override public void frame(byte[] bytes, long pts, boolean key) throws IOException {
                if (!stop.get()) {
                    contract.accept(bytes, log);
                    frames[0]++;
                    streaming.run();
                }
            }
        };
        AdbShellClient adb = new AdbShellClient("127.0.0.1", 5555, 20000,
                AndroidAdbIdentity.load(), log, abort);
        AdbScreenVideoSource source = new AdbScreenVideoSource(adb, observer, log);
        IOException failure = null;
        try {
            source.runContinuous(5, context.getApplicationInfo().sourceDir, stop, cleaning,
                    CaptureOptions.of(CaptureOptions.Mode.FIT), target);
        } catch (IOException error) {
            failure = error;
        } catch (RuntimeException error) {
            failure = new IOException("REMOTE_PROBE_RUNTIME_FAILURE", error);
        }
        RemoteProbeCompletion.require(source.exitConfirmed(), stop.get(), frames[0], failure);
        log.accept((stop.get() ? "REMOTE_DISPLAY_PROBE_STOPPED" : "REMOTE_DISPLAY_RUNTIME_PASS")
                + " target=" + target + " frames=" + frames[0]
                + "; owned EXIT/resources confirmed; NOT instrument picture/FPS evidence");
    }
}
