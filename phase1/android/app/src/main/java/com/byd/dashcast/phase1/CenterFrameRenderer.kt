package com.byd.dashcast.phase1

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Resamples a real 16:9 source into the unchanged 1920x720 AVC transport.
 * QNX's 768x432 destination reverses this horizontal resampling exactly.
 * This owns only its EGL context, input Surface and callback thread.
 * @author Codex
 * @date 2026/10/22
 */
internal class CenterFrameRenderer @JvmOverloads constructor(
    output: Surface,
    private val viewport: ContentViewport? = null
) : AutoCloseable {
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var texture = 0
    private var program = 0
    private var source: SurfaceTexture? = null
    private var input: Surface? = null
    private var callbacks: HandlerThread? = null
    private val pending = AtomicBoolean()
    private var lastTimestamp = -1L
    private val matrix = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        position(0)
    }

    init {
        try {
            initialize(output)
        } catch (failure: Exception) {
            try { close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun initialize(output: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        requireEgl(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, version, 0, version, 1), "initialize")
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
        requireEgl(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
            && count[0] == 1 && configs[0] != null, "config")
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        requireEgl(context != EGL14.EGL_NO_CONTEXT, "context")
        window = EGL14.eglCreateWindowSurface(display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
        requireEgl(window != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, window, window, context), "surface")
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        texture = names[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        program = GLES20.glCreateProgram()
        val vertex = shader(GLES20.GL_VERTEX_SHADER, ContentViewport.VERTEX)
        var fragment = 0
        try {
            fragment = shader(GLES20.GL_FRAGMENT_SHADER, ContentViewport.FRAGMENT)
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] != GLES20.GL_TRUE) { throw IOException("CENTER_GL_LINK ${GLES20.glGetProgramInfoLog(program)}") }
        } finally {
            GLES20.glDeleteShader(vertex)
            if (fragment != 0) { GLES20.glDeleteShader(fragment) }
        }
        val worker = HandlerThread("dashcast-center-frames").apply { start() }
        callbacks = worker
        source = SurfaceTexture(texture).also {
            it.setDefaultBufferSize(viewport?.sourceWidth ?: INPUT_WIDTH, viewport?.sourceHeight ?: INPUT_HEIGHT)
            it.setOnFrameAvailableListener({ pending.set(true) }, Handler(worker.looper))
            input = Surface(it)
        }
        checkGl("initialize")
        System.err.println("SHELL_CAPTURE_CONTENT_RENDERER viewport=$viewport encoded=1920x720; " +
            "layout uses effective inner aspect, edge-clamped bleed; legacy center=${viewport == null}")
    }

    fun inputSurface(): Surface = input ?: throw IllegalStateException("CENTER_RENDERER_CLOSED")

    @Throws(IOException::class)
    fun renderIfAvailable() {
        if (!pending.getAndSet(false)) { return }
        val owned = source ?: throw IOException("CENTER_RENDERER_CLOSED")
        owned.updateTexImage()
        owned.getTransformMatrix(matrix)
        val timestamp = owned.timestamp
        if (timestamp <= lastTimestamp) { return }
        GLES20.glViewport(0, 0, NoAuthVideoProtocol.WIDTH, NoAuthVideoProtocol.HEIGHT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "transform"), 1, false, matrix, 0)
        val bounds = viewport?.normalized() ?: floatArrayOf(0f, 0f, 1f, 1f)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "content"), 1, bounds, 0)
        val position = GLES20.glGetAttribLocation(program, "position")
        val uv = GLES20.glGetAttribLocation(program, "uv")
        vertices.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(position)
        vertices.position(2)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGl("draw")
        requireEgl(EGLExt.eglPresentationTimeANDROID(display, window, timestamp), "timestamp")
        requireEgl(EGL14.eglSwapBuffers(display, window), "swap")
        lastTimestamp = timestamp
    }

    override fun close() {
        var failure: Exception? = null
        fun release(action: () -> Unit) {
            try { action() } catch (error: Exception) {
                if (failure == null) { failure = error } else { failure!!.addSuppressed(error) }
            }
        }
        release { source?.setOnFrameAvailableListener(null) }
        release { input?.release() }; input = null
        release { source?.release() }; source = null
        val worker = callbacks; callbacks = null
        if (worker != null) {
            release {
                worker.quitSafely()
                worker.join(2000)
                if (worker.isAlive) { throw IOException("CENTER_CALLBACK_CLEANUP_UNCONFIRMED") }
            }
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            release {
                if (context != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE) {
                    requireEgl(EGL14.eglMakeCurrent(display, window, window, context), "cleanup-current")
                    if (program != 0) { GLES20.glDeleteProgram(program) }
                    if (texture != 0) { GLES20.glDeleteTextures(1, intArrayOf(texture), 0) }
                }
            }
            release { requireEgl(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT), "cleanup-release") }
            release { if (window != EGL14.EGL_NO_SURFACE) { requireEgl(EGL14.eglDestroySurface(display, window), "cleanup-surface") } }
            release { if (context != EGL14.EGL_NO_CONTEXT) { requireEgl(EGL14.eglDestroyContext(display, context), "cleanup-context") } }
            release { requireEgl(EGL14.eglReleaseThread(), "cleanup-thread") }
            release { requireEgl(EGL14.eglTerminate(display), "cleanup-display") }
        }
        window = EGL14.EGL_NO_SURFACE; context = EGL14.EGL_NO_CONTEXT; display = EGL14.EGL_NO_DISPLAY
        program = 0; texture = 0
        failure?.let { throw it }
    }

    private fun requireEgl(success: Boolean, operation: String) {
        if (!success) { throw IOException("CENTER_EGL_$operation error=${EGL14.eglGetError()}") }
    }

    private fun checkGl(operation: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) { throw IOException("CENTER_GL_$operation error=$error") }
    }

    private fun shader(kind: Int, source: String): Int {
        val shader = GLES20.glCreateShader(kind)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val detail = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IOException("CENTER_SHADER_COMPILE $detail")
        }
        return shader
    }

    companion object {
        const val INPUT_WIDTH = 1280
        const val INPUT_HEIGHT = 720
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
