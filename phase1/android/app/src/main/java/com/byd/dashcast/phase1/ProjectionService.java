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
    private static final java.util.concurrent.atomic.AtomicLong PRODUCT_GENERATION =
            new java.util.concurrent.atomic.AtomicLong();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ThreadPoolExecutor worker;
    private ProjectionSessionController controller;
    private PowerManager.WakeLock wakeLock;
    private static final long WAKE_LEASE_MILLIS = 60_000L;
    private static final long WAKE_RENEW_MILLIS = 20_000L;
    private final Runnable renewWakeLease = new Runnable() {
        @Override public void run() {
            if (destroyed || finishing || controller == null || !controller.active() || wakeLock == null) { return; }
            try {
                wakeLock.acquire(WAKE_LEASE_MILLIS);
                main.postDelayed(this, WAKE_RENEW_MILLIS);
            } catch (RuntimeException error) {
                JOURNAL.append("PRODUCT_WAKE_LEASE_FAILED " + error);
                controller.abort("Foreground wake ownership lost");
            }
        }
    };
    private String ownedDevice;
    private boolean receiverRegistered;
    private volatile boolean finishing;
    private volatile UsbOperationGate.Lease ownedLease;
    private volatile boolean destroyed;
    private ContinuousProjectionTask continuousTask;

    private final BroadcastReceiver detach = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction()) && device != null
                    && device.getDeviceName().equals(ownedDevice)) {
                JOURNAL.append("OWNED_USB_PHYSICAL_DETACH device=" + ownedDevice);
                if (continuousTask != null) { continuousTask.notePhysicalDetach(); }
                controller.abort("Owned instrument USB detached");
            }
        }
    };

    static void start(Context context, int seconds) {
        start(context, seconds, CaptureOptions.of(CaptureOptions.Mode.STRETCH));
    }

    static void start(Context context, int seconds, CaptureOptions options) {
        start(context, seconds, options, ProjectionTarget.primary());
    }

    static void start(Context context, int seconds, CaptureOptions options, ProjectionTarget target) {
        start(context, seconds, options, target, false, false);
    }

    static void startComposition(Context context, CaptureOptions options, ProjectionTarget target) {
        start(context, 10, options, target, true, false);
    }

    static void startProbe(Context context, ProjectionTarget target) {
        start(context, 5, CaptureOptions.of(CaptureOptions.Mode.FIT), target, false, true);
    }

    static void startProduct(Context context, CaptureOptions options, ProjectionTarget target, boolean underlay) {
        if (!new ProductSettings(context).getSetupComplete() || target == null || options == null) {
            throw new IllegalArgumentException("Product setup and explicit capture selection required");
        }
        context.startForegroundService(new Intent(context, ProjectionService.class).setAction(START)
                .putExtra("seconds", ContinuousSessionPolicy.UNLIMITED_SECONDS).putExtra("product_session", true)
                .putExtra("product_generation", PRODUCT_GENERATION.incrementAndGet())
                .putExtra("product_underlay", underlay).putExtra("capture_selection", options.token())
                .putExtra("projection_target", target.token()));
    }

    private static void start(Context context, int seconds, CaptureOptions options, ProjectionTarget target,
            boolean composition, boolean probe) {
        ProjectionLimits.requireSeconds(seconds);
        if (options == null || target == null) {
            throw new IllegalArgumentException("Capture selection and projection target required");
        }
        context.startForegroundService(new Intent(context, ProjectionService.class).setAction(START)
                .putExtra("seconds", seconds).putExtra("parked_matching_firmware", true)
                .putExtra("capture_selection", options.token())
                .putExtra("composition_trial", composition).putExtra("capture_only_probe", probe)
                .putExtra("projection_target", target.token()));
    }

    static void requestStop(Context context) {
        // A user's STOP also invalidates an enqueued START not yet delivered to the Service.
        PRODUCT_GENERATION.incrementAndGet();
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

    static ProjectionSessionController.State state() { return JOURNAL.currentState(); }

    static boolean quarantined() { return JOURNAL.quarantined(); }

    static boolean active() {
        ProjectionSessionController owner = current;
        return owner != null && owner.active();
    }

    static void quarantine(String detail) {
        JOURNAL.state(ProjectionSessionController.State.QUARANTINED, detail);
    }

    static String journal() {
        return JOURNAL.snapshot();
    }

    static void confirmDetachedRecovery() throws IOException {
        if (active() || JOURNAL.currentState() != ProjectionSessionController.State.DISCONNECTED) {
            throw new IOException("DETACHED_RECOVERY_STATE_CHANGED");
        }
        JOURNAL.state(ProjectionSessionController.State.STOPPED,
                "DETACHED_PEER_RECOVERY_CONFIRMED; local resources closed and fresh peer idle snapshot verified");
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
                    || state == ProjectionSessionController.State.DISCONNECTED
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
        boolean product = intent.getBooleanExtra("product_session", false);
        if (product && intent.getLongExtra("product_generation", -1L) != PRODUCT_GENERATION.get()) {
            JOURNAL.append("PRODUCT_START_CANCELLED before Service ownership");
            if (!controller.active()) { stopSelf(); }
            return START_NOT_STICKY;
        }
        boolean confirmed = product ? new ProductSettings(this).getSetupComplete()
                : intent.getBooleanExtra("parked_matching_firmware", false);
        if (!START.equals(intent.getAction()) || !confirmed
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
            if (product) { ContinuousSessionPolicy.requireCaptureSeconds(seconds); }
            else { ProjectionLimits.requireSeconds(seconds); }
            CaptureOptions options = CaptureOptions.parse(intent.getStringExtra("capture_selection"));
            ProjectionTarget target = ProjectionTarget.parse(intent.getStringExtra("projection_target"));
            boolean composition = intent.getBooleanExtra("composition_trial", false);
            boolean probe = intent.getBooleanExtra("capture_only_probe", false);
            boolean underlay = intent.getBooleanExtra("product_underlay", false);
            if (product && (probe || composition || !ContinuousSessionPolicy.unlimited(seconds)
                    || underlay == options.centerWindow()) || !product && options.centerWindow()) {
                throw new IOException("Invalid product session profile");
            }
            if (composition && (probe || seconds != 10) || probe && (seconds != 5 || !target.isRemote())) {
                throw new IOException("Invalid fixed composition/probe profile");
            }
            JOURNAL.append("PROJECTION_CONFIG=" + options
                    + "; target=" + target
                    + "; encoded=1920x720/30fps mode=" + (product ? (underlay ? "product-renewable-underlay" : "product-small-window") : probe ? "capture-only-probe" : composition
                    ? "composition-5s" : "small-window") + "; actual composition/FPS require vehicle observation");
            if (!probe) {
                UsbDevice device = UsbNcmSession.findDevice(this);
                if (product && (device.getVendorId() != 0x1234 || device.getProductId() != 0xfff1)) {
                    throw new IOException("Product instrument identity changed before Service ownership");
                }
                UsbManager usb = (UsbManager)getSystemService(USB_SERVICE);
                if (usb == null || !usb.hasPermission(device)) {
                    throw new IOException("Normal instrument USB permission required before starting");
                }
                ownedDevice = device.getDeviceName();
            }
            startForeground(NOTIFICATION_ID, notification());
            if (!probe) {
                registerReceiver(detach, new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));
                receiverRegistered = true;
            }
            PowerManager power = (PowerManager)getSystemService(POWER_SERVICE);
            if (power == null) {
                throw new IOException("PowerManager unavailable");
            }
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dashcast:continuous");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(product ? WAKE_LEASE_MILLIS : (seconds + 30L) * 1000);
            ContinuousProjectionTask continuous = probe ? null
                    : new ContinuousProjectionTask(this, seconds, JOURNAL::append, options, target, composition);
            continuousTask = continuous;
            if (product) {
                continuous.enableProductComposition();
            }
            ProjectionSessionController.Task task = probe
                    ? new RemoteDisplayProbeTask(this, target, JOURNAL::append)
                    : continuous;
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
            } else if (product) {
                main.postDelayed(renewWakeLease, WAKE_RENEW_MILLIS);
            }
        } catch (IOException | RuntimeException error) {
            ownedLease = null;
            lease.close();
            JOURNAL.append("START_FAILED before capture: " + error);
            if (!JOURNAL.quarantined()) { JOURNAL.state(ProjectionSessionController.State.FAILED, error.toString()); }
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
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, ProductActivity.class),
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
        main.removeCallbacks(renewWakeLease);
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
