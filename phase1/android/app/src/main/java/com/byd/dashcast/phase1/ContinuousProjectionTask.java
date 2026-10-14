package com.byd.dashcast.phase1;

import android.content.Context;

import com.byd.dashcast.phase1.ncm.LogHub;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Owns the existing NCM/DCV1/capture path, with no Activity reference or video disk recording.
 * @author Codex
 * @date 2026/10/13
 */
final class ContinuousProjectionTask implements ProjectionSessionController.Task {
    private final Context context;
    private final int seconds;
    private final Consumer<String> log;
    private final CaptureOptions options;

    ContinuousProjectionTask(Context context, int seconds, Consumer<String> log) {
        this(context, seconds, log, CaptureOptions.of(CaptureOptions.Mode.STRETCH));
    }

    ContinuousProjectionTask(Context context, int seconds, Consumer<String> log, CaptureOptions options) {
        ProjectionLimits.requireSeconds(seconds);
        this.context = context.getApplicationContext();
        this.seconds = seconds;
        this.log = log;
        this.options = java.util.Objects.requireNonNull(options);
    }

    @Override
    public void run(AtomicBoolean stop, AtomicBoolean abort, Runnable streaming, Runnable cleaning) throws IOException {
        UsbNcmSession session = null;
        NoAuthVideoClient client = null;
        AdbScreenVideoSource source = null;
        FrameRateGate frameRateGate = new FrameRateGate(NoAuthVideoProtocol.FPS);
        FramePacer framePacer = new FramePacer(NoAuthVideoProtocol.FPS);
        IOException failure = null;
        boolean usbClean = true;
        boolean captureAttempted = false;
        IOException[] receiverFailure = {null};
        try {
            LogHub.banner("CONTINUOUS_VIDEO_ATTEMPT " + System.currentTimeMillis() + " seconds=" + seconds);
            if (stop.get()) {
                return;
            }
            session = UsbNcmSession.open(context, abort);
            client = new NoAuthVideoClient(session::connect, abort, log::accept, true);
            NoAuthVideoClient ownedClient = client;
            AvcReceiverContract contract = new AvcReceiverContract();
            boolean[] begun = {false};
            boolean[] receiverFinished = {false};
            Runnable finishReceiver = () -> {
                if (receiverFinished[0]) {
                    return;
                }
                receiverFinished[0] = true;
                cleaning.run();
                try {
                    if (!stop.get() && begun[0]) {
                        ownedClient.requireWindowReport();
                    }
                } catch (IOException | RuntimeException error) {
                    receiverFailure[0] = collect(receiverFailure[0], error);
                } finally {
                    try {
                        ownedClient.close();
                    } catch (IOException | RuntimeException error) {
                        receiverFailure[0] = collect(receiverFailure[0], error);
                    }
                }
            };
            VideoPatternProbe.Observer frames = new VideoPatternProbe.Observer() {
                @Override
                public void log(String message) {
                    log.accept(message);
                }

                @Override
                public void frame(byte[] data, long pts, boolean key) throws IOException {
                    if (stop.get()) {
                        return;
                    }
                    long outputPts = frameRateGate.outputPts(pts);
                    if (outputPts < 0L) {
                        return;
                    }
                    contract.accept(data, log);
                    if (!begun[0]) {
                        ownedClient.begin();
                        begun[0] = true;
                    }
                    // PTS retiming alone does not prevent burst writes when the encoder releases
                    // several output units together. Schedule each synchronous write on the
                    // monotonic 30 FPS clock; a slow ACK path is recorded as late, never hidden.
                    framePacer.awaitNextSlot();
                    ownedClient.frame(data, outputPts);
                    streaming.run();
                }
            };
            AdbShellClient adb = new AdbShellClient("127.0.0.1", 5555, 20000,
                    AndroidAdbIdentity.load(), log, abort);
            source = new AdbScreenVideoSource(adb, frames, log);
            captureAttempted = true;
            source.runContinuous(seconds, context.getApplicationInfo().sourceDir, stop, finishReceiver, options);
            if (receiverFailure[0] != null) {
                throw receiverFailure[0];
            }
            log.accept(client.statistics());
        } catch (IOException | RuntimeException error) {
            failure = new IOException("Continuous projection failed: " + error, error);
        } finally {
            log.accept(frameRateGate.statistics());
            log.accept(framePacer.statistics());
            cleaning.run();
            if (client != null) {
                try {
                    client.close();
                } catch (IOException | RuntimeException error) {
                    failure = collect(failure, error);
                }
            }
            if (session != null) {
                try {
                    log.accept(session.statistics());
                } finally {
                    try {
                        session.close();
                    } catch (IOException | RuntimeException error) {
                        usbClean = false;
                        failure = collect(failure, error);
                    }
                }
            }
        }
        boolean captureClean = !captureAttempted || source != null && source.exitConfirmed();
        boolean receiverClean = client == null || client.cleanupConfirmed();
        if (receiverFailure[0] != null && failure == null) {
            failure = receiverFailure[0];
        }
        if (failure != null) {
            log.accept("CONTINUOUS_FAILURE " + failure);
            for (Throwable suppressed : failure.getSuppressed()) {
                log.accept("CONTINUOUS_CLEANUP_FAILURE " + suppressed);
            }
        }
        if (!captureClean || !receiverClean || !usbClean || openCleanupUnconfirmed(failure)) {
            throw new ProjectionSessionController.CleanupUnconfirmedException(
                    "CLEANUP_UNCONFIRMED capture_exit=" + captureClean + " receiver=" + receiverClean
                            + " USB=" + usbClean, failure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static IOException collect(IOException first, Exception next) {
        if (first == null) {
            return new IOException("Owned cleanup failed", next);
        }
        first.addSuppressed(next);
        return first;
    }

    private static boolean openCleanupUnconfirmed(Throwable error) {
        if (error == null) {
            return false;
        }
        String message = error.getMessage();
        if (message != null && message.contains("CLEANUP_UNCONFIRMED")) {
            return true;
        }
        for (Throwable suppressed : error.getSuppressed()) {
            if (openCleanupUnconfirmed(suppressed)) {
                return true;
            }
        }
        return openCleanupUnconfirmed(error.getCause());
    }
}
