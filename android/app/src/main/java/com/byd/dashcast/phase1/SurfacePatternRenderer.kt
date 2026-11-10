package com.byd.dashcast.phase1

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import java.io.Closeable
import java.io.IOException
import java.util.Objects

/**
 * GPU-only moving pattern on the codec's recordable input Surface. No Canvas/YUV byte layout assumptions.
 * Owns only its EGL objects and the supplied codec input Surface, not an on-screen application window.
 *
 * @author Codex
 * @date 2026/10/10
 */
internal class SurfacePatternRenderer(input: Surface?, private val width: Int, private val height: Int) : Closeable {
    private val input: Surface = Objects.requireNonNull(input)!!
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var closed = false

    init {
        try {
            initialize()
        } catch (error: IOException) {
            throw initializationFailed(error)
        } catch (error: RuntimeException) {
            throw initializationFailed(error)
        }
    }

    private fun initializationFailed(error: Exception): IOException {
        try {
            close()
        } catch (cleanup: IOException) {
            error.addSuppressed(cleanup)
        }
        return IOException("Recordable EGL input initialization failed", error)
    }

    @Throws(IOException::class)
    private fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        require(display != EGL14.EGL_NO_DISPLAY, "getDisplay")
        val version = IntArray(2)
        require(EGL14.eglInitialize(display, version, 0, version, 1), "initialize")
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        require(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) &&
            count[0] > 0 && configs[0] != null, "chooseRecordableConfig")
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        require(context != EGL14.EGL_NO_CONTEXT, "createContext")
        window = EGL14.eglCreateWindowSurface(display, configs[0], input, intArrayOf(EGL14.EGL_NONE), 0)
        require(window != EGL14.EGL_NO_SURFACE, "createWindowSurface")
        require(EGL14.eglMakeCurrent(display, window, window, context), "makeCurrent")
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_DITHER)
        checkGl()
    }

    @Throws(IOException::class)
    fun render(frame: Int, ptsMicros: Long) {
        if (closed || frame < 0 || ptsMicros < 0 || ptsMicros > Long.MAX_VALUE / 1000) {
            throw IOException("Invalid/closed EGL frame input")
        }
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        for (stripe in 0 until STRIPES) {
            val left = stripe * width / STRIPES
            val right = (stripe + 1) * width / STRIPES
            val color = COLORS[(stripe + frame / 4) % STRIPES]
            GLES20.glScissor(left, 0, right - left, height)
            GLES20.glClearColor(color[0], color[1], color[2], 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glScissor(frame * 24 % width, height / 2, 48, height / 8)
        GLES20.glClearColor(1f, 1f, 1f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        checkGl()
        require(EGLExt.eglPresentationTimeANDROID(display, window, ptsMicros * 1000), "presentationTime")
        require(EGL14.eglSwapBuffers(display, window), "swapBuffers")
    }

    @Throws(IOException::class)
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        var okay = true
        try {
            if (display != EGL14.EGL_NO_DISPLAY) {
                okay = okay and EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (window != EGL14.EGL_NO_SURFACE) {
                    okay = okay and EGL14.eglDestroySurface(display, window)
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    okay = okay and EGL14.eglDestroyContext(display, context)
                }
                okay = okay and EGL14.eglReleaseThread()
                okay = okay and EGL14.eglTerminate(display)
            }
        } finally {
            input.release()
            display = EGL14.EGL_NO_DISPLAY
            window = EGL14.EGL_NO_SURFACE
            context = EGL14.EGL_NO_CONTEXT
        }
        if (!okay) {
            throw IOException("EGL cleanup incomplete; do not claim a successful encoder run")
        }
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val STRIPES = 8
        val COLORS = arrayOf(
            floatArrayOf(0.9f, 0.9f, 0.9f), floatArrayOf(0.9f, 0.9f, 0.1f), floatArrayOf(0.1f, 0.9f, 0.9f),
            floatArrayOf(0.1f, 0.9f, 0.1f), floatArrayOf(0.9f, 0.1f, 0.9f), floatArrayOf(0.9f, 0.1f, 0.1f),
            floatArrayOf(0.1f, 0.1f, 0.9f), floatArrayOf(0.1f, 0.1f, 0.1f))

        @Throws(IOException::class)
        fun require(okay: Boolean, operation: String) {
            if (!okay) {
                throw IOException("EGL " + operation + " failed code=0x" + Integer.toHexString(EGL14.eglGetError()))
            }
        }

        @Throws(IOException::class)
        fun checkGl() {
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                throw IOException("GL pattern render failed code=0x" + Integer.toHexString(error))
            }
        }
    }
}
