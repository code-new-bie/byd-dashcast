package com.byd.dashcast.phase1;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** User-started, non-exported foreground service. No boot start, sticky restart or role automation.
 * @author Codex
 * @date 2026/10/13
 */
public final class ProjectionService extends Service {
    private static final String CHANNEL = "dashcast-projection";
    private static final String START = "com.byd.dashcast.START_CONTINUOUS";
    private static final String STOP = "com.byd.dashcast.STOP_CONTINUOUS";
    private static final int NOTIFICATION_ID = 50;
    private static final ProjectionJournal JOURNAL = new ProjectionJournal();
    private static volatile ProjectionSessionController current;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ThreadPoolExecutor worker;
    private ProjectionSessionController controller;
    private PowerManager.WakeLock wakeLock;
    private String ownedDevice;
    private boolean receiverRegistered;
    private volatile boolean finishing;
    private volatile UsbOperationGate.Lease ownedLease;
    private volatile boolean destroyed;

    private final BroadcastReceiver detach = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction()) && device != null
                    && device.getDeviceName().equals(ownedDevice)) {
                controller.abort("Owned instrument USB detached");
            }
        }
    };

    static void start(Context context, int seconds) {
        start(context, seconds, CaptureOptions.of(CaptureOptions.Mode.STRETCH));
    }

    static void start(Context context, int seconds, CaptureOptions options) {
        ProjectionLimits.requireSeconds(seconds);
        if (options == null) {
            throw new IllegalArgumentException("Capture selection required");
        }
        context.startForegroundService(new Intent(context, ProjectionService.class).setAction(START)
                .putExtra("seconds", seconds).putExtra("parked_matching_firmware", true)
                .putExtra("capture_selection", options.token()));
    }

    static void requestStop(Context context) {
        ProjectionSessionController active = current;
        if (active != null && active.active()) {
            context.startService(new Intent(context, ProjectionService.class).setAction(STOP));
        }
    }

    static boolean blocksOperations() {
        ProjectionSessionController active = current;
        return JOURNAL.quarantined() || active != null && active.active();
    }

    static String status() {
        return JOURNAL.status();
    }

    static String journal() {
        return JOURNAL.snapshot();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
            Thread thread = new Thread(task, "dashcast-continuous-session");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        controller = new ProjectionSessionController(worker, (state, detail) -> {
            JOURNAL.state(state, detail);
            if (state == ProjectionSessionController.State.STOPPED || state == ProjectionSessionController.State.FAILED
                    || state == ProjectionSessionController.State.QUARANTINED) {
                finishing = true;
                // Publish quarantine BEFORE releasing the process gate; never let a new Service
                // instance race a previous owner's unconfirmed cleanup.
                UsbOperationGate.Lease completedLease = ownedLease;
                ownedLease = null;
                if (completedLease != null) {
                    completedLease.close();
                }
                main.post(() -> {
                    if (destroyed) {
                        if (current == controller) {
                            current = null;
                        }
                        return;
                    }
                    if (controller.active()) {
                        return;
                    }
                    releaseWakeLock();
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                });
            }
        });
        current = controller;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (finishing) {
            JOURNAL.append("START_IGNORED while Service is finishing; wait for stopped status before retry");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || STOP.equals(intent.getAction())) {
            controller.stop();
            if (!controller.active()) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (!START.equals(intent.getAction()) || !intent.getBooleanExtra("parked_matching_firmware", false)
                || JOURNAL.quarantined()) {
            JOURNAL.append("START_BLOCKED: missing user confirmation or previous cleanup unconfirmed");
            if (!controller.active()) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (controller.active()) {
            JOURNAL.append("DUPLICATE_START_IGNORED: current session preserved");
            return START_NOT_STICKY;
        }
        UsbOperationGate.Lease lease = UsbOperationGate.acquire();
        if (lease == null) {
            JOURNAL.append("START_BLOCKED: another diagnostic/USB operation is still cleaning up");
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if (JOURNAL.quarantined()) {
                throw new IOException("Previous owner's cleanup became unconfirmed; retry blocked");
            }
            int seconds = intent.getIntExtra("seconds", 0);
            ProjectionLimits.requireSeconds(seconds);
            CaptureOptions options = CaptureOptions.parse(intent.getStringExtra("capture_selection"));
            JOURNAL.append("PROJECTION_CONFIG=" + options
                    + "; encoded=1920x720/30fps native_rect=[0,0,1920,720]; HMI occlusion and decoded FPS require vehicle validation");
            UsbDevice device = UsbNcmSession.findDevice(this);
            UsbManager usb = (UsbManager)getSystemService(USB_SERVICE);
            if (usb == null || !usb.hasPermission(device)) {
                throw new IOException("Normal instrument USB permission required before starting");
            }
            ownedDevice = device.getDeviceName();
            startForeground(NOTIFICATION_ID, notification());
            registerReceiver(detach, new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));
            receiverRegistered = true;
            PowerManager power = (PowerManager)getSystemService(POWER_SERVICE);
            if (power == null) {
                throw new IOException("PowerManager unavailable");
            }
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dashcast:continuous");
            wakeLock.acquire((seconds + 30L) * 1000);
            ContinuousProjectionTask task = new ContinuousProjectionTask(this, seconds, JOURNAL::append, options);
            ownedLease = lease;
            boolean accepted = controller.start((stop, abort, streaming, cleaning) -> {
                task.run(stop, abort, streaming, cleaning);
            });
            if (!accepted) {
                ownedLease = null;
                lease.close();
                releaseWakeLock();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        } catch (IOException | RuntimeException error) {
            ownedLease = null;
            lease.close();
            JOURNAL.append("START_FAILED before capture: " + error);
            releaseWakeLock();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("NotificationManager unavailable");
        }
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.projection_channel),
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ProjectionService.class).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.projection_notification_title))
                .setContentText(getString(R.string.projection_notification_text))
                .setOngoing(true).setContentIntent(open)
                .addAction(0, getString(R.string.projection_notification_stop), stop).build();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        controller.abort("Foreground Service destroyed");
        worker.shutdown();
        if (receiverRegistered) {
            unregisterReceiver(detach);
            receiverRegistered = false;
        }
        releaseWakeLock();
        if (current == controller && !controller.active()) {
            current = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
