package com.byd.dashcast.phase1;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded live AVC stdout through authorized local ADB, framed as complete pictures.
 * The instrument path selects the shell-owned Baseline encoder, not stock screenrecord defaults.
 * Receipt timestamps are synthetic transport timestamps, not source frame PTS/latency measurements.
 *
 * @author Codex
 * @date 2026/10/11
 */
final class AdbScreenVideoSource {
    private final AdbShellClient client;
    private final VideoPatternProbe.Observer observer;
    private final Consumer<String> log;
    private boolean exitConfirmed;

    AdbScreenVideoSource(AdbShellClient client, VideoPatternProbe.Observer observer, Consumer<String> log) {
        this.client = Objects.requireNonNull(client);
        this.observer = Objects.requireNonNull(observer);
        this.log = Objects.requireNonNull(log);
    }

    static String command(int seconds) {
        if (seconds < 1 || seconds > 10) {
            throw new IllegalArgumentException("ADB screen verification must be 1..10 seconds");
        }
        return "screenrecord --output-format=h264 --size 1920x720 --bit-rate 6000000"
                + " --time-limit " + seconds + " -";
    }

    static String smallWindowCommand(int seconds) {
        return command(seconds);
    }

    void run(int seconds) throws IOException {
        runCommand(command(seconds));
    }

    void runSmallWindow(int seconds) throws IOException {
        runCommand(command(seconds));
    }

    void runSmallWindow(int seconds, String installedApk) throws IOException {
        runCommand(ShellCaptureCommand.create(installedApk, seconds));
    }

    void runContinuous(int seconds, String installedApk, AtomicBoolean stopRequested, Runnable finishReceiver)
            throws IOException {
        runContinuous(seconds, installedApk, stopRequested, finishReceiver, null);
    }

    void runContinuous(int seconds, String installedApk, AtomicBoolean stopRequested, Runnable finishReceiver,
            CaptureOptions options) throws IOException {
        runContinuous(seconds, installedApk, stopRequested, finishReceiver, options, ProjectionTarget.primary());
    }

    void runContinuous(int seconds, String installedApk, AtomicBoolean stopRequested, Runnable finishReceiver,
            CaptureOptions options, ProjectionTarget target) throws IOException {
        FramedCaptureReader reader = new FramedCaptureReader(observer, finishReceiver);
        ProjectionTarget selectedTarget = Objects.requireNonNull(target, "Projection target required");
        log.accept("VIDEO_SOURCE=authorized_ADB_DCS1; timestamps=encoder_PTS; no video file recorded"
                + "; target=" + selectedTarget);
        CaptureOptions selectedOptions = options == null
                ? CaptureOptions.of(CaptureOptions.Mode.STRETCH) : options;
        String command = ShellCaptureCommand.continuous(installedApk, seconds, selectedOptions, selectedTarget);
        log.accept("CAPTURE_SELECTION=" + selectedOptions
                + "; immutable for this session; instrument rectangle unchanged");
        log.accept("PROJECTION_TARGET=" + selectedTarget + "; virtual display is owned by the ADB shell helper");
        client.executeShellV2Controlled(command, reader, log,
                Objects.requireNonNull(stopRequested), finishReceiver);
        exitConfirmed = true;
        reader.finish(stopRequested.get());
        log.accept(reader.statistics());
    }

    boolean exitConfirmed() {
        return exitConfirmed;
    }

    private void runCommand(String command) throws IOException {
        long start = System.nanoTime();
        long[] lastPts = {-1};
        AnnexBAccessUnitReader reader = new AnnexBAccessUnitReader((data, key) -> {
            long pts = Math.max(lastPts[0] + 1, 1_000_000L + (System.nanoTime() - start) / 1000);
            observer.frame(data, pts, key);
            lastPts[0] = pts;
        });
        log.accept("VIDEO_SOURCE=authorized_ADB; nominal_receiver_fps=30; timestamps=receipt_NOT_source_PTS");
        log.accept("ADB_VIDEO_COMMAND " + command);
        client.executeShellV2(command, reader, log);
        reader.finish();
        if (reader.frameCount() < 2) {
            throw new IOException("ADB capture produced fewer than two complete pictures; moving video unverified");
        }
        log.accept("ADB_SCREEN_VIDEO bytes=" + reader.byteCount() + " complete_frames=" + reader.frameCount()
                + "; encoder FPS/latency and instrument display NOT certified by these counts");
    }
}
