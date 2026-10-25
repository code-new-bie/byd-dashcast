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
    private final ProjectionTarget target;
    private final boolean compositionTrial;
    private boolean productComposition;
    private final AtomicBoolean physicallyDetached = new AtomicBoolean();

    /** Called only for the OS detach broadcast matching this Service's owned device. */
    void notePhysicalDetach() {
        physicallyDetached.set(true);
    }

    ContinuousProjectionTask enableProductComposition() {
        productComposition = true;
        return this;
    }

    ContinuousProjectionTask(Context context, int seconds, Consumer<String> log) {
        this(context, seconds, log, CaptureOptions.of(CaptureOptions.Mode.STRETCH));
    }

    ContinuousProjectionTask(Context context, int seconds, Consumer<String> log, CaptureOptions options) {
        this(context, seconds, log, options, ProjectionTarget.primary());
    }

    ContinuousProjectionTask(Context context, int seconds, Consumer<String> log, CaptureOptions options,
            ProjectionTarget target) {
        this(context, seconds, log, options, target, false);
    }

    ContinuousProjectionTask(Context context, int seconds, Consumer<String> log, CaptureOptions options,
            ProjectionTarget target, boolean compositionTrial) {
        ContinuousSessionPolicy.requireCaptureSeconds(seconds);
        this.context = context.getApplicationContext();
        this.seconds = seconds;
        this.log = log;
        this.options = java.util.Objects.requireNonNull(options);
        this.target = java.util.Objects.requireNonNull(target);
        this.compositionTrial = compositionTrial;
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
        String mirrorTicket = null;
        IOException[] receiverFailure = {null};
        try {
            LogHub.banner("CONTINUOUS_VIDEO_ATTEMPT " + System.currentTimeMillis() + " seconds=" + seconds);
            if (stop.get()) {
                return;
            }
            session = UsbNcmSession.open(context, abort);
            session.observePhysicalDetach(physicallyDetached::get);
            client = new NoAuthVideoClient(session::connect, abort, log::accept, true);
            if (ContinuousSessionPolicy.unlimited(seconds)) { client.enableUnlimitedStream(); }
            if (productComposition) {
                client.enableProductComposition(new java.security.SecureRandom().nextInt(Integer.MAX_VALUE - 1) + 1,
                        options.centerWindow());
            } else if (compositionTrial) {
                client.enableCompositionTrial(new java.security.SecureRandom().nextInt(Integer.MAX_VALUE - 1) + 1,
                        () -> stop.set(true));
            }
            NoAuthVideoClient ownedClient = client;
            UsbNcmSession ownedSession = session;
            AvcReceiverContract contract = new AvcReceiverContract();
            boolean[] begun = {false};
            boolean[] receiverFinished = {false};
            Runnable finishReceiver = () -> {
                if (receiverFinished[0]) {
                    return;
                }
                receiverFinished[0] = true;
                cleaning.run();
                if (ownedSession.physicalLinkAbsent(context)) {
                    physicallyDetached.set(true);
                    stop.set(true);
                    abort.set(true);
                    log.accept("OWNED_USB_REMOVAL_CONFIRMED_BY_DEVICE_LIST");
                }
                if (physicallyDetached.get()) {
                    try {
                        ownedClient.closeAfterPhysicalDetach();
                    } catch (IOException | RuntimeException error) {
                        receiverFailure[0] = collect(receiverFailure[0], error);
                    }
                    return;
                }
                try {
                    if (!stop.get() && !abort.get() && begun[0]) {
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
                public void tick() throws IOException {
                    if (!stop.get()) {
                        ownedClient.maintain();
                    }
                }

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
                    contract.accept(data, log);
                    if (!begun[0]) {
                        ownedClient.begin();
                        begun[0] = true;
                    }
                    // Preserve real source PTS, but prevent encoder batches from bursting.
                    // Schedule each synchronous write on the
                    // monotonic 30 FPS clock; a slow ACK path is recorded as late, never hidden.
                    framePacer.awaitNextSlot();
                    ownedClient.frame(data, outputPts);
                    if (ownedClient.readyForStreaming()) { streaming.run(); }
                }
            };
            AdbShellClient adb = new AdbShellClient("127.0.0.1", 5555, 20000,
                    // USB abort must not cancel the independent localhost channel before its owned STOP/EXIT.
                    AndroidAdbIdentity.load(), log, new AtomicBoolean());
            source = new AdbScreenVideoSource(adb, frames, log);
            if (target.isRemote()) {
                ProjectionEntryRouter.Selection entry = ProjectionEntryRouter.resolve(context, target, log);
                mirrorTicket = entry.getNative() == null
                        ? MirrorBridge.begin(context, target.component().substring(0, target.component().indexOf('/')))
                        : DuplexTicket.create();
                source.setMirrorTicket(mirrorTicket);
            }
            captureAttempted = true;
            source.runContinuous(seconds, context.getApplicationInfo().sourceDir, stop, finishReceiver, options, target);
            if (receiverFailure[0] != null) {
                throw receiverFailure[0];
            }
            log.accept(client.statistics());
        } catch (IOException | RuntimeException error) {
            failure = new IOException("Continuous projection failed: " + error, error);
        } finally {
            MirrorBridge.end(mirrorTicket);
            log.accept(frameRateGate.statistics());
            log.accept(framePacer.statistics());
            cleaning.run();
            if (client != null) {
                try {
                    if (physicallyDetached.get()) { client.closeAfterPhysicalDetach(); }
                    else { client.close(); }
                } catch (IOException | RuntimeException error) {
                    failure = collect(failure, error);
                }
            }
            if (session != null) {
                try {
                    log.accept(session.statistics());
                } finally {
                    try {
                        if (physicallyDetached.get()) { session.closeDetached(); }
                        else { session.close(); }
                    } catch (IOException | RuntimeException error) {
                        usbClean = false;
                        failure = collect(failure, error);
                    }
                }
            }
        }
        boolean captureClean = !captureAttempted || source != null && source.exitConfirmed();
        boolean receiverClean = client == null || client.cleanupConfirmed();
        boolean localUsbClosed = usbClean && (session == null || session.localResourcesClosed());
        boolean partialOpenUnknown = session == null && openCleanupUnconfirmed(failure);
        if (physicallyDetached.get() && captureClean && localUsbClosed && !partialOpenUnknown) {
            log.accept("PHYSICAL_DETACH_LOCAL_CLEANUP_CONFIRMED; read-only peer recovery required after reconnect");
            throw new ProjectionSessionController.DetachedSessionException(failure);
        }
        if (receiverFailure[0] != null && failure == null) {
            failure = receiverFailure[0];
        }
        if (failure != null) {
            log.accept("CONTINUOUS_FAILURE " + failure);
            for (Throwable suppressed : failure.getSuppressed()) {
                log.accept("CONTINUOUS_CLEANUP_FAILURE " + suppressed);
            }
        }
        CleanupRecoveryPolicy.Outcome outcome = CleanupRecoveryPolicy.classify(
                captureClean, localUsbClosed, partialOpenUnknown, receiverClean);
        String proof = "capture_exit=" + captureClean + " receiver=" + receiverClean
                + " USB=" + usbClean + " local_usb_closed=" + localUsbClosed
                + " partial_open_unknown=" + partialOpenUnknown
                + "; causes=" + CleanupRecoveryPolicy.describe(failure);
        log.accept("CLEANUP_EVIDENCE outcome=" + outcome + " " + proof);
        if (outcome == CleanupRecoveryPolicy.Outcome.PEER_RECOVERY_REQUIRED) {
            throw new ProjectionSessionController.PeerCleanupPendingException(proof, failure);
        }
        if (outcome == CleanupRecoveryPolicy.Outcome.LOCAL_UNCONFIRMED) {
            throw new ProjectionSessionController.CleanupUnconfirmedException(
                    "CLEANUP_UNCONFIRMED " + proof, failure);
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
