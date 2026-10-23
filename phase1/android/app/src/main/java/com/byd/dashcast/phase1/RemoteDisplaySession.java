package com.byd.dashcast.phase1;

import android.content.Context;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.os.Handler;
import android.os.Process;
import android.view.Display;
import android.view.Surface;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Owns only a newly created application task and its direct-output virtual display.
 * Never adopts, moves, resizes, relaunches or captures an existing primary-screen task.
 */
final class RemoteDisplaySession implements AutoCloseable {
    // PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | DESTROY_CONTENT_ON_REMOVAL. No AUTO_MIRROR.
    // Failed cleanup must never migrate this display's remaining activities onto the primary display.
    private static final int FLAGS = 1 | 2 | 8 | 256;
    private VirtualDisplay display;
    private AndroidTaskAccess access;
    private IndependentTaskPolicy policy;
    private AppTaskPolicy.Task owned;
    private final Set<Integer> existing = new HashSet<>();
    private final TaskPollSchedule polls = new TaskPollSchedule();
    private int user;
    private int displayId = -1;
    private String component;
    private boolean closed;

    RemoteDisplaySession(Surface surface, ProjectionTarget target) throws Exception {
        this(surface, target, NoAuthVideoProtocol.WIDTH, NoAuthVideoProtocol.HEIGHT);
    }

    RemoteDisplaySession(Surface surface, ProjectionTarget target, int width, int height) throws Exception {
        this(surface, target, width, height, CaptureOptions.of(CaptureOptions.Mode.STRETCH));
    }

    RemoteDisplaySession(Surface surface, ProjectionTarget target, int width, int height,
            CaptureOptions options) throws Exception {
        if (surface == null || target == null || !target.isRemote()) {
            throw new IllegalArgumentException("Independent application display required");
        }
        int densityDpi = ProjectionDisplayProfile.densityDpi(width, height);
        try {
            Context context = shellContext();
            IndependentAppPlan.Plan plan = IndependentProjectionEntry.resolve(context, target);
            component = plan.getTaskRoot();
            access = new AndroidTaskAccess();
            user = access.currentUser();
            for (AppTaskPolicy.Task task : access.projectionTasks()) {
                existing.add(task.id);
                if (plan.getNativeSecondary() && task.user == user && task.root.equals(component)) {
                    throw new IOException("APP_NATIVE_SECONDARY_ALREADY_IN_USE; existing task left untouched");
                }
            }
            display = createVirtualDisplay(context, surface, width, height, densityDpi);
            Display info = display.getDisplay();
            if (info == null || info.getDisplayId() <= 0) {
                throw new IOException("APP_INDEPENDENT_DISPLAY_MISSING");
            }
            displayId = info.getDisplayId();
            System.err.println("SHELL_CAPTURE_REMOTE_DISPLAY_CREATED id=" + displayId
                    + " size=" + width + "x" + height + " density=" + densityDpi
                    + " direct_surface=true own_content_only=true");
            policy = new IndependentTaskPolicy(existing, component, user, displayId);
            launch(plan.arguments(displayId, user));
            long deadline = System.nanoTime() + 10_000_000_000L;
            do {
                owned = policy.discover(access.projectionTasks());
                if (owned != null) { break; }
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("APP_INDEPENDENT_START_INTERRUPTED");
                }
                java.util.concurrent.locks.LockSupport.parkNanos(50_000_000L);
            } while (System.nanoTime() < deadline);
            if (owned == null) { throw new IOException("APP_INDEPENDENT_TASK_NOT_CREATED; no mirror fallback"); }
            System.err.println("SHELL_CAPTURE_APP_INDEPENDENT task=" + owned.id + " display=" + displayId
                    + " user=" + user + " entry=" + component + " size=" + width + "x" + height
                    + " direct_surface=true source_display_zero=false same_user_data=true native_secondary="
                    + plan.getNativeSecondary());
        } catch (Exception failure) {
            try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    void update(MediaCodec codec) throws Exception { checkTask(); }

    boolean acceptFrame(long ptsMicros, boolean key) throws Exception {
        checkTask();
        return true; // One fixed display/surface/geometry for the whole session; no source switching.
    }

    private void checkTask() throws Exception {
        if (closed) { throw new IOException("APP_INDEPENDENT_CAPTURE_CLOSED"); }
        if (!polls.due(System.nanoTime())) { return; }
        AppTaskPolicy.Task current = policy.find(access.projectionTasks());
        if (current == null || current.id != owned.id) {
            throw new IOException("APP_INDEPENDENT_TASK_LOST; original tasks left untouched");
        }
    }

    private static VirtualDisplay createVirtualDisplay(Context context, Surface surface,
            int width, int height, int densityDpi) throws Exception {
        Class<?> type = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object manager = type.getMethod("getInstance").invoke(null);
        Method create = type.getMethod("createVirtualDisplay", Context.class,
                Class.forName("android.media.projection.MediaProjection"), String.class,
                int.class, int.class, int.class, Surface.class, int.class,
                VirtualDisplay.Callback.class, Handler.class, String.class);
        Object result = create.invoke(manager, context, null, "dashcast-independent-" + System.nanoTime(),
                width, height, densityDpi, surface, FLAGS, null, null, null);
        if (!(result instanceof VirtualDisplay)) { throw new IOException("APP_INDEPENDENT_DISPLAY_CREATE_FAILED"); }
        return (VirtualDisplay) result;
    }

    private static void launch(List<String> arguments) throws Exception {
        java.lang.Process child = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        String result = BoundedProcessOutput.read(child, 10_000L).trim().replace('\n', ' ').replace('\r', ' ');
        // Do not retain arbitrary application/logcat output or Intent data.
        System.err.println("SHELL_CAPTURE_INDEPENDENT_LAUNCH exit=" + child.exitValue());
        if (child.exitValue() != 0 || result.contains("Error:") || result.contains("SecurityException")) {
            throw new IOException("APP_INDEPENDENT_START_REJECTED");
        }
        System.err.println("SHELL_CAPTURE_REMOTE_AM exit=0 independent=true");
    }

    private static Context shellContext() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
        if (activityThread == null) {
            Constructor<?> constructor = activityThreadClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            activityThread = constructor.newInstance();
        }
        Method getSystemContext = activityThreadClass.getMethod("getSystemContext");
        Context systemContext = (Context) getSystemContext.invoke(activityThread);
        if (systemContext == null) {
            throw new IOException("SHELL_CAPTURE_REMOTE_SYSTEM_CONTEXT_MISSING");
        }
        String[] packages = systemContext.getPackageManager().getPackagesForUid(Process.myUid());
        if (packages != null) {
            for (String packageName : packages) {
                try {
                    return systemContext.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY);
                } catch (Exception ignored) {
                    // Try the next package associated with the shell UID, then use system context.
                }
            }
        }
        return systemContext;
    }

    @Override
    public void close() throws Exception {
        if (closed) { return; }
        closed = true;
        try {
            IndependentTaskCleanup.close(policy, new IndependentTaskCleanup.Backend() {
                @Override
                public List<AppTaskPolicy.Task> tasks() {
                    return access.projectionTasks();
                }

                @Override
                public void remove(AppTaskPolicy.Task task) {
                    access.removeProjectionTask(task, displayId, existing);
                }

                @Override
                public void releaseDisplay() {
                    if (display != null) {
                        VirtualDisplay ownDisplay = display;
                        display = null;
                        ownDisplay.release();
                        System.err.println("SHELL_CAPTURE_REMOTE_DISPLAY_RELEASED");
                    }
                }
            });
        } finally {
            System.err.println("SHELL_CAPTURE_INDEPENDENT_CLEANUP task_queries=" + polls.getQueries()
                    + "; original_tasks_not_moved_or_removed");
        }
    }
}
