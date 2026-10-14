package com.byd.dashcast.phase1;

import android.content.Context;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Process;
import android.view.Display;
import android.view.Surface;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Owns one Android virtual display for the shell-side encoder surface.
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

    RemoteDisplaySession(Surface surface, ProjectionTarget target) throws Exception {
        if (surface == null || target == null || !target.isRemote()) {
            throw new IllegalArgumentException("Remote display requires a surface and remote target");
        }
        try {
            Context context = shellContext();
            System.err.println("SHELL_CAPTURE_REMOTE_CONTEXT package=" + context.getPackageName()
                    + " uid=" + Process.myUid());
            display = createVirtualDisplay(context, surface);
            Display androidDisplay = display.getDisplay();
            if (androidDisplay == null || androidDisplay.getDisplayId() < 0) {
                throw new IOException("SHELL_CAPTURE_REMOTE_DISPLAY_INFO_MISSING");
            }
            System.err.println("SHELL_CAPTURE_REMOTE_DISPLAY_CREATED id=" + androidDisplay.getDisplayId()
                    + " size=" + DISPLAY_WIDTH + "x" + DISPLAY_HEIGHT + " density=" + DISPLAY_DENSITY_DPI
                    + " flags=PUBLIC|PRESENTATION|OWN_CONTENT_ONLY component=" + target.component());
            launchOnDisplay(androidDisplay.getDisplayId(), target.component());
        } catch (Exception error) {
            Exception failure = error instanceof InvocationTargetException
                    ? unwrap((InvocationTargetException)error) : error;
            try {
                close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static VirtualDisplay createVirtualDisplay(Context context, Surface surface) throws Exception {
        Class<?> globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object global = globalClass.getMethod("getInstance").invoke(null);
        Method create = globalClass.getMethod("createVirtualDisplay", Context.class,
                Class.forName("android.media.projection.MediaProjection"), String.class, int.class, int.class,
                int.class, Surface.class, int.class, VirtualDisplay.Callback.class, Handler.class, String.class);
        Object result = create.invoke(global, context, null, "dashcast-remote-" + System.currentTimeMillis(),
                DISPLAY_WIDTH, DISPLAY_HEIGHT, DISPLAY_DENSITY_DPI, surface,
                VIRTUAL_DISPLAY_FLAG_PUBLIC | VIRTUAL_DISPLAY_FLAG_PRESENTATION | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                null, null, null);
        if (!(result instanceof VirtualDisplay)) {
            throw new IOException("SHELL_CAPTURE_REMOTE_DISPLAY_CREATE_FAILED");
        }
        return (VirtualDisplay) result;
    }

    private static void launchOnDisplay(int displayId, String component) throws Exception {
        java.lang.Process process = new ProcessBuilder("/system/bin/am", "start", "--display",
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
    public void close() {
        if (display != null) {
            VirtualDisplay owned = display;
            display = null;
            owned.release();
            System.err.println("SHELL_CAPTURE_REMOTE_DISPLAY_RELEASED");
        }
    }
}
