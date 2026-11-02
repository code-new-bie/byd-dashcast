package com.byd.dashcast.phase1;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.view.Surface;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;

/**
 * GPU-only moving pattern on the codec's recordable input Surface. No Canvas/YUV byte layout assumptions.
 * Owns only its EGL objects and the supplied codec input Surface, not an on-screen application window.
 *
 * @author Codex
 * @date 2026/10/10
 */
final class SurfacePatternRenderer implements Closeable {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final int STRIPES = 8;
    private static final float[][] COLORS = {
        {0.9f, 0.9f, 0.9f}, {0.9f, 0.9f, 0.1f}, {0.1f, 0.9f, 0.9f}, {0.1f, 0.9f, 0.1f},
        {0.9f, 0.1f, 0.9f}, {0.9f, 0.1f, 0.1f}, {0.1f, 0.1f, 0.9f}, {0.1f, 0.1f, 0.1f}
    };
    private final Surface input;
    private final int width;
    private final int height;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private boolean closed;

    SurfacePatternRenderer(Surface input, int width, int height) throws IOException {
        this.input = Objects.requireNonNull(input);
        this.width = width;
        this.height = height;
        try {
            initialize();
        } catch (IOException | RuntimeException error) {
            try {
                close();
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw new IOException("Recordable EGL input initialization failed", error);
        }
    }

    private void initialize() throws IOException {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        require(display != EGL14.EGL_NO_DISPLAY, "getDisplay");
        int[] version = new int[2];
        require(EGL14.eglInitialize(display, version, 0, version, 1), "initialize");
        int[] attributes = {
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        require(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
                && count[0] > 0 && configs[0] != null, "chooseRecordableConfig");
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        require(context != EGL14.EGL_NO_CONTEXT, "createContext");
        window = EGL14.eglCreateWindowSurface(display, configs[0], input, new int[] {EGL14.EGL_NONE}, 0);
        require(window != EGL14.EGL_NO_SURFACE, "createWindowSurface");
        require(EGL14.eglMakeCurrent(display, window, window, context), "makeCurrent");
        GLES20.glViewport(0, 0, width, height);
        GLES20.glDisable(GLES20.GL_DITHER);
        checkGl();
    }

    void render(int frame, long ptsMicros) throws IOException {
        if (closed || frame < 0 || ptsMicros < 0 || ptsMicros > Long.MAX_VALUE / 1000) {
            throw new IOException("Invalid/closed EGL frame input");
        }
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        for (int stripe = 0; stripe < STRIPES; stripe++) {
            int left = stripe * width / STRIPES;
            int right = (stripe + 1) * width / STRIPES;
            float[] color = COLORS[(stripe + frame / 4) % STRIPES];
            GLES20.glScissor(left, 0, right - left, height);
            GLES20.glClearColor(color[0], color[1], color[2], 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }
        GLES20.glScissor(frame * 24 % width, height / 2, 48, height / 8);
        GLES20.glClearColor(1, 1, 1, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        checkGl();
        require(EGLExt.eglPresentationTimeANDROID(display, window, ptsMicros * 1000), "presentationTime");
        require(EGL14.eglSwapBuffers(display, window), "swapBuffers");
    }

    private static void require(boolean okay, String operation) throws IOException {
        if (!okay) {
            throw new IOException("EGL " + operation + " failed code=0x"
                    + Integer.toHexString(EGL14.eglGetError()));
        }
    }

    private static void checkGl() throws IOException {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            throw new IOException("GL pattern render failed code=0x" + Integer.toHexString(error));
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        boolean okay = true;
        try {
            if (display != EGL14.EGL_NO_DISPLAY) {
                okay &= EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (window != EGL14.EGL_NO_SURFACE) {
                    okay &= EGL14.eglDestroySurface(display, window);
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    okay &= EGL14.eglDestroyContext(display, context);
                }
                okay &= EGL14.eglReleaseThread();
                okay &= EGL14.eglTerminate(display);
            }
        } finally {
            input.release();
            display = EGL14.EGL_NO_DISPLAY;
            window = EGL14.EGL_NO_SURFACE;
            context = EGL14.EGL_NO_CONTEXT;
        }
        if (!okay) {
            throw new IOException("EGL cleanup incomplete; do not claim a successful encoder run");
        }
    }
}
