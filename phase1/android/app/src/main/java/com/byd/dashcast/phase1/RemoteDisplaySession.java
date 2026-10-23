package com.byd.dashcast.phase1;

import android.content.Context;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.view.Display;
import android.view.Surface;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Owns one Android virtual display and one SF capture token for an adopted app task.
 * The QNX instrument remains only the remote video endpoint; it is not an Android Display.
 */
final class RemoteDisplaySession implements AutoCloseable {
    private static final int VIRTUAL_DISPLAY_FLAG_PUBLIC = 1;
    private static final int VIRTUAL_DISPLAY_FLAG_PRESENTATION = 2;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 8;
    private static final int DISPLAY_WIDTH = NoAuthVideoProtocol.WIDTH;
    private static final int DISPLAY_HEIGHT = NoAuthVideoProtocol.HEIGHT;
    private static final int DISPLAY_DENSITY_DPI = 160;

    private VirtualDisplay display;
    private RemoteDisplaySink sink;
    private ShellScreenCapture.Mirror mirror;
    private AndroidTaskAccess taskAccess;
    private AppTaskPolicy.Task task;
    private NativeAppLaunchWatcher launchWatcher;
    private AppTaskHandoff handoff;
    private final CaptureSourceFence fence = new CaptureSourceFence();
    private int remoteDisplayId = -1;
    private int sourceDisplayId = -1;
    private long lastPoll;
    private boolean closed;

    RemoteDisplaySession(Surface surface, ProjectionTarget target) throws Exception {
        this(surface, target, DISPLAY_WIDTH, DISPLAY_HEIGHT);
    }

    RemoteDisplaySession(Surface surface, ProjectionTarget target, int width, int height) throws Exception {
        this(surface, target, width, height, CaptureOptions.of(CaptureOptions.Mode.STRETCH));
    }

    RemoteDisplaySession(Surface surface, ProjectionTarget target, int width, int height,
            CaptureOptions options) throws Exception {
        if (surface == null || target == null || !target.isRemote()) {
            throw new IllegalArgumentException("Remote display requires a surface and remote target");
        }
        if (!(width == DISPLAY_WIDTH && height == DISPLAY_HEIGHT)
                && !(width == 1280 && height == 720)
                && !(width == 1920 && height == 480)
                && !(width == 1200 && height == 640)) {
            throw new IllegalArgumentException("Remote display requires an exact supported profile");
        }
        try {
            Context context = shellContext();
            System.err.println("SHELL_CAPTURE_REMOTE_CONTEXT package=" + context.getPackageName()
                    + " uid=" + Process.myUid());
            taskAccess = new AndroidTaskAccess();
            int user = taskAccess.currentUser();
            String packageName = target.component().substring(0, target.component().indexOf('/'));
            String component = launchComponent(context, target.component());
            task = taskAccess.find(packageName, user);
            if (task != null && task.display != 0) {
                throw new IOException("APP_TASK_ALREADY_ON_ANOTHER_DISPLAY; no foreign display is adopted");
            }
            launchWatcher = new NativeAppLaunchWatcher(target.component());
            sink = new RemoteDisplaySink(width, height);
            display = createVirtualDisplay(context, sink.surface(), width, height);
            Display androidDisplay = display.getDisplay();
            if (androidDisplay == null || androidDisplay.getDisplayId() < 0) {
                throw new IOException("SHELL_CAPTURE_REMOTE_DISPLAY_INFO_MISSING");
            }
            System.err.println("SHELL_CAPTURE_REMOTE_DISPLAY_CREATED id=" + androidDisplay.getDisplayId()
                    + " size=" + width + "x" + height + " density=" + DISPLAY_DENSITY_DPI
                    + " flags=PUBLIC|PRESENTATION|OWN_CONTENT_ONLY component=" + target.component());
            remoteDisplayId = androidDisplay.getDisplayId();
            if (task == null) {
                // A cold app may be launched once. An already-running navigation task NEVER
                // receives another launcher intent and is never force-stopped or recreated.
                launchOnDisplay(remoteDisplayId, component);
                long deadline = System.nanoTime() + 2_000_000_000L;
                do {
                    task = taskAccess.find(packageName, user);
                    if (task != null) { break; }
                    java.util.concurrent.locks.LockSupport.parkNanos(25_000_000L);
                } while (System.nanoTime() < deadline);
                if (task == null) { throw new IOException("APP_TASK_COLD_LAUNCH_NOT_OBSERVED"); }
            } else {
                System.err.println("SHELL_CAPTURE_APP_TASK_ADOPTED id=" + task.id
                        + " display=" + task.display + "; existing route retained; no launcher intent");
            }
            launchWatcher.arm(task.id, user);
            sourceDisplayId = task.display;
            mirror = new ShellScreenCapture.Mirror(surface, options, width, height, sourceDisplayId);
            handoff = new AppTaskHandoff(task, remoteDisplayId, new AppTaskHandoff.TaskPort() {
                @Override public AppTaskPolicy.Task read(int id, int userId, String root) {
                    return taskAccess.owned(id, userId, root);
                }
                @Override public AppTaskPolicy.Task transfer(AppTaskPolicy.Task owned, int destination, boolean show) {
                    return taskAccess.transfer(owned, destination, show);
                }
            }, new AppTaskHandoff.SourcePort() {
                @Override public void invalidate() { fence.changed(System.nanoTime()); }
                @Override public void select(int selectedDisplay) throws Exception {
                    mirror.selectDisplay(selectedDisplay);
                    sourceDisplayId = selectedDisplay;
                    fence.changed(System.nanoTime());
                }
            });
            handoff.route(false);
            System.err.println("SHELL_CAPTURE_APP_SHARED_TASK id=" + task.id + " source=" + sourceDisplayId
                    + "; native icon uses same task, background hands off to owned display");
        } catch (Exception error) {
            Exception failure = error instanceof InvocationTargetException
                    ? unwrap((InvocationTargetException)error) : error;
            try {
                close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    void update(MediaCodec codec) throws Exception {
        if (System.nanoTime() - lastPoll >= 100_000_000L) { route(); }
        if (fence.needsSync(System.nanoTime())) {
            Bundle request = new Bundle();
            request.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(request);
        }
    }

    boolean acceptFrame(long ptsMicros, boolean key) throws Exception {
        // Check again at the output boundary, not just once at startup. If the native icon
        // or another application changed the foreground, old queued pictures stay fenced.
        if (route()) { return false; }
        return fence.accept(ptsMicros, key, System.nanoTime());
    }

    private boolean route() throws Exception {
        if (closed) { throw new IOException("APP_TASK_CAPTURE_CLOSED"); }
        sink.checkHealthy();
        boolean nativeIcon = launchWatcher.takeRequest();
        boolean changed = handoff.route(nativeIcon);
        if (changed) {
            System.err.println("SHELL_CAPTURE_APP_SOURCE_CHANGED task=" + task.id + " display=" + sourceDisplayId
                    + " native_icon=" + nativeIcon + "; same encoder/USB session; fresh IDR required");
        }
        lastPoll = System.nanoTime();
        return changed;
    }

    private static VirtualDisplay createVirtualDisplay(Context context, Surface surface, int width, int height) throws Exception {
        Class<?> globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object global = globalClass.getMethod("getInstance").invoke(null);
        Method create = globalClass.getMethod("createVirtualDisplay", Context.class,
                Class.forName("android.media.projection.MediaProjection"), String.class, int.class, int.class,
                int.class, Surface.class, int.class, VirtualDisplay.Callback.class, Handler.class, String.class);
        Object result = create.invoke(global, context, null, "dashcast-remote-" + System.currentTimeMillis(),
                width, height, DISPLAY_DENSITY_DPI, surface,
                VIRTUAL_DISPLAY_FLAG_PUBLIC | VIRTUAL_DISPLAY_FLAG_PRESENTATION | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                null, null, null);
        if (!(result instanceof VirtualDisplay)) {
            throw new IOException("SHELL_CAPTURE_REMOTE_DISPLAY_CREATE_FAILED");
        }
        return (VirtualDisplay) result;
    }

    private static void launchOnDisplay(int displayId, String component) throws Exception {
        java.lang.Process process = new ProcessBuilder("/system/bin/am", "start", "-W", "--display",
                Integer.toString(displayId), "--user", "current", "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER", "-n", component)
                .redirectErrorStream(true)
                .start();
        String detail = BoundedProcessOutput.read(process, 10_000L).trim()
                .replace('\n', ' ').replace('\r', ' ');
        System.err.println("SHELL_CAPTURE_REMOTE_AM exit=" + process.exitValue() + " output=" + detail);
        if (process.exitValue() != 0 || detail.contains("Error:") || detail.contains("SecurityException")) {
            throw new IOException("SHELL_CAPTURE_REMOTE_APP_START_FAILED exit=" + process.exitValue()
                    + " output=" + detail);
        }
    }

    private static String launchComponent(Context context, String requested) throws Exception {
        String candidate = RemoteLaunchPlan.mapActivityCandidate(requested);
        if (candidate == null) { return requested; }
        ComponentName component = ComponentName.unflattenFromString(candidate);
        if (component == null) { throw new IOException("REMOTE_MAP_COMPONENT_INVALID"); }
        ActivityInfo activity = context.getPackageManager().getActivityInfo(component, 0);
        if (!activity.enabled || !activity.exported || activity.permission != null) {
            throw new IOException("REMOTE_MAP_ENTRY_UNAVAILABLE; no private activity or privilege bypass attempted");
        }
        System.err.println("SHELL_CAPTURE_REMOTE_MAP_ENTRY requested=" + requested + " actual=" + candidate
                + "; cold entry only; an existing route is adopted by exact task identity");
        return candidate;
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

    private static Exception unwrap(InvocationTargetException error) {
        Throwable cause = error.getCause();
        if (cause instanceof Exception) {
            return (Exception) cause;
        }
        return new IOException("SHELL_CAPTURE_REMOTE_DISPLAY_REFLECTION_FAILED", cause);
    }

    @Override
    public void close() throws Exception {
        if (closed) { return; }
        closed = true;
        Exception failure = null;
        try {
            if (launchWatcher != null) { launchWatcher.close(); }
        } catch (Exception error) { failure = error; }
        try {
            if (handoff != null) { handoff.close(); }
            else if (taskAccess != null && task != null && remoteDisplayId > 0) {
                AppTaskPolicy.Task current = taskAccess.owned(task.id, task.user, task.root);
                if (current != null && current.display == remoteDisplayId) {
                    current = taskAccess.transfer(current, 0, false);
                }
            }
            if (task != null) { System.err.println("SHELL_CAPTURE_APP_TASK_PRESERVED id=" + task.id
                    + "; no navigation stop, new intent, task removal or package kill"); }
        } catch (Exception error) { failure = collect(failure, error); }
        try {
            if (mirror != null) { mirror.close(); }
        } catch (Exception error) { failure = collect(failure, error); }
        if (display != null) {
            VirtualDisplay owned = display;
            display = null;
            try {
                owned.release();
                System.err.println("SHELL_CAPTURE_REMOTE_DISPLAY_RELEASED");
            } catch (Exception error) { failure = collect(failure, error); }
        }
        try {
            if (sink != null) { sink.close(); }
        } catch (Exception error) { failure = collect(failure, error); }
        if (failure != null) {
            throw new IOException("CLEANUP_UNCONFIRMED owned app task/display cleanup", failure);
        }
    }

    private static Exception collect(Exception current, Exception next) {
        if (current == null) { return next; }
        current.addSuppressed(next);
        return current;
    }
}
