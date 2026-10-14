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
        return command(seconds, false);
    }

    static String smallWindowCommand(int seconds) {
        return command(seconds, true);
    }

    private static String command(int seconds, boolean smallWindow) {
        if (seconds < 1 || seconds > 10) {
            throw new IllegalArgumentException("ADB screen verification must be 1..10 seconds");
        }
        return "screenrecord --output-format=h264 --size "
                + (smallWindow ? "640x240 --bit-rate 1000000" : "1920x720 --bit-rate 6000000")
                + " --time-limit " + seconds + " -";
    }

    void run(int seconds) throws IOException {
        run(seconds, false);
    }

    void runSmallWindow(int seconds) throws IOException {
        run(seconds, true);
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
        FramedCaptureReader reader = new FramedCaptureReader(observer, finishReceiver);
        log.accept("VIDEO_SOURCE=authorized_ADB_DCS1; timestamps=encoder_PTS; no video file recorded");
        String command = options == null ? ShellCaptureCommand.continuous(installedApk, seconds)
                : ShellCaptureCommand.continuous(installedApk, seconds, options);
        if (options != null) {
            log.accept("CAPTURE_SELECTION=" + options + "; immutable for this session; instrument rectangle unchanged");
        }
        client.executeShellV2Controlled(command, reader, log,
                Objects.requireNonNull(stopRequested), finishReceiver);
        exitConfirmed = true;
        reader.finish(stopRequested.get());
        log.accept(reader.statistics());
    }

    boolean exitConfirmed() {
        return exitConfirmed;
    }

    private void run(int seconds, boolean smallWindow) throws IOException {
        String command = command(seconds, smallWindow);
        runCommand(command);
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
