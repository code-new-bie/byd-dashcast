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
    private val viewport: ContentViewport? = null,
    crop: Boolean = false
) : AutoCloseable {
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null
    private var mirrorWindow = EGL14.EGL_NO_SURFACE
    private var mirror: MirrorSinkMailbox.Sink? = null
    private var mirrorDirty = false
    private var texture = 0
    private var program = 0
    private var paddingTexture = 0
    private var paddingFramebuffer = 0
    private var paddingProgram = 0
    private var paddingPosition = -1
    private var paddingUv = -1
    private var paddingImage = -1
    private var paddingTransform = -1
    private var paddingSourceBounds = -1
    private var paddingLocation = -1
    private var positionLocation = -1
    private var uvLocation = -1
    private var imageLocation = -1
    private var transformLocation = -1
    private var contentLocation = -1
    private var sourceBoundsLocation = -1
    private val mapping = SourceRenderMapping.create(viewport, crop)
    private val submissions = FrameSubmissionClock()
    private var sourceUpdates = 0L
    private var submittedFrames = 0L
    private var repeatedDraws = 0L
    private var source: SurfaceTexture? = null
    private var input: Surface? = null
    private var callbacks: HandlerThread? = null
    private val pending = AtomicBoolean()
    private var lastTimestamp = -1L
    private var validProducerBuffer = false
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
        config = configs[0]
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
        program = linkProgram(ContentViewport.FRAGMENT)
        paddingProgram = linkProgram(ContentViewport.PADDING_FRAGMENT)
        positionLocation = GLES20.glGetAttribLocation(program, "position")
        uvLocation = GLES20.glGetAttribLocation(program, "uv")
        imageLocation = GLES20.glGetUniformLocation(program, "image")
        transformLocation = GLES20.glGetUniformLocation(program, "transform")
        contentLocation = GLES20.glGetUniformLocation(program, "content")
        sourceBoundsLocation = GLES20.glGetUniformLocation(program, "sourceBounds")
        paddingLocation = GLES20.glGetUniformLocation(program, "padding")
        paddingPosition = GLES20.glGetAttribLocation(paddingProgram, "position")
        paddingUv = GLES20.glGetAttribLocation(paddingProgram, "uv")
        paddingImage = GLES20.glGetUniformLocation(paddingProgram, "image")
        paddingTransform = GLES20.glGetUniformLocation(paddingProgram, "transform")
        paddingSourceBounds = GLES20.glGetUniformLocation(paddingProgram, "sourceBounds")
        if (listOf(positionLocation, uvLocation, imageLocation, transformLocation, contentLocation, sourceBoundsLocation, paddingLocation,
                paddingPosition, paddingUv, paddingImage, paddingTransform, paddingSourceBounds).any { it < 0 }) {
            throw IOException("CENTER_GL_SHADER_INTERFACE_MISSING")
        }
        GLES20.glGenTextures(1, names, 0)
        paddingTexture = names[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, paddingTexture)
        for (key in intArrayOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, key, GLES20.GL_LINEAR)
        }
        for (key in intArrayOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T)) {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, key, GLES20.GL_CLAMP_TO_EDGE)
        }
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            ContentViewport.PADDING_WIDTH, ContentViewport.PADDING_HEIGHT, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glGenFramebuffers(1, names, 0)
        paddingFramebuffer = names[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, paddingFramebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, paddingTexture, 0)
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw IOException("CENTER_GL_PADDING_FRAMEBUFFER_INCOMPLETE")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val worker = HandlerThread("dashcast-center-frames").apply { start() }
        callbacks = worker
        source = SurfaceTexture(texture).also {
            it.setDefaultBufferSize(viewport?.sourceWidth ?: INPUT_WIDTH, viewport?.sourceHeight ?: INPUT_HEIGHT)
            it.setOnFrameAvailableListener({ pending.set(true) }, Handler(worker.looper))
            input = Surface(it)
        }
        checkGl("initialize")
        System.err.println("SHELL_CAPTURE_CONTENT_RENDERER viewport=$viewport encoded=1920x720; " +
            "separate_source_aspect=true lowpass_padding=32x16x64 no_glyph_extrusion=true; legacy center=${viewport == null}")
    }

    fun inputSurface(): Surface = input ?: throw IllegalStateException("CENTER_RENDERER_CLOSED")

    fun renderedMirrorLease(): Long = if (mirrorDirty) -1L else mirror?.lease ?: -1L

    fun outputPollTimeoutUs(): Long = if (!validProducerBuffer) 10_000L else
        minOf(10_000L, maxOf(1L, submissions.delayNanos(System.nanoTime()) / 1000))

    /** Called only on the encoder/GL thread. A failed optional consumer never tears down the encoder. */
    fun setMirrorSink(next: MirrorSinkMailbox.Sink) {
        removeMirror()
        if (next.surface == null) { return }
        try {
            mirror = next
            if (!next.surface.isValid) { throw IOException("MIRROR_SURFACE_INVALID") }
            mirrorWindow = EGL14.eglCreateWindowSurface(display, config, next.surface, intArrayOf(EGL14.EGL_NONE), 0)
            requireEgl(mirrorWindow != EGL14.EGL_NO_SURFACE, "mirror-surface")
            mirrorDirty = true
        } catch (error: Exception) {
            System.err.println("SHELL_MIRROR_OPTIONAL_SURFACE_REJECTED type=${error.javaClass.simpleName}")
            removeMirror()
        }
    }

    @Throws(IOException::class)
    @JvmOverloads fun renderIfAvailable(allowed: Boolean = true, minimumSourceTimestampNanos: Long = 0) {
        if (!allowed) { return }
        val fresh = pending.getAndSet(false)
        val owned = source ?: throw IOException("CENTER_RENDERER_CLOSED")
        if (fresh) {
            owned.updateTexImage()
            owned.getTransformMatrix(matrix)
            val actual = owned.timestamp
            validProducerBuffer = FrameSubmissionClock.validProducer(actual, lastTimestamp, System.nanoTime())
            if (validProducerBuffer) { lastTimestamp = actual }
            sourceUpdates++
        }
        val timestamp = System.nanoTime()
        val sourceReady = validProducerBuffer && FrameSubmissionClock.sourceReady(lastTimestamp, minimumSourceTimestampNanos)
        if (!submissions.due(timestamp, sourceReady)) {
            if (mirrorDirty && sourceReady) { drawMirror() }
            return
        }
        // A tiny low-pass pass removes legible high-frequency UI from the padding.
        // Do not sample paddingTexture while it is attached to the draw framebuffer.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, paddingFramebuffer)
        GLES20.glViewport(0, 0, ContentViewport.PADDING_WIDTH, ContentViewport.PADDING_HEIGHT)
        GLES20.glUseProgram(paddingProgram)
        GLES20.glUniform1i(paddingImage, 0)
        GLES20.glUniformMatrix4fv(paddingTransform, 1, false, matrix, 0)
        GLES20.glUniform4fv(paddingSourceBounds, 1, mapping.source, 0)
        drawQuad(paddingPosition, paddingUv)
        checkGl("padding")
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, NoAuthVideoProtocol.WIDTH, NoAuthVideoProtocol.HEIGHT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, paddingTexture)
        GLES20.glUniform1i(paddingLocation, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(imageLocation, 0)
        GLES20.glUniformMatrix4fv(transformLocation, 1, false, matrix, 0)
        GLES20.glUniform4fv(contentLocation, 1, mapping.content, 0)
        GLES20.glUniform4fv(sourceBoundsLocation, 1, mapping.source, 0)
        drawQuad(positionLocation, uvLocation)
        checkGl("draw")
        requireEgl(EGLExt.eglPresentationTimeANDROID(display, window, timestamp), "timestamp")
        requireEgl(EGL14.eglSwapBuffers(display, window), "swap")
        submissions.submitted(timestamp)
        submittedFrames++
        if (!fresh) { repeatedDraws++ }
        drawMirror()
    }

    private fun drawMirror() {
        val sink = mirror ?: return
        try {
            requireEgl(EGL14.eglMakeCurrent(display, mirrorWindow, mirrorWindow, context), "mirror-current")
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, sink.width, sink.height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val bounds = MirrorGeometry.fit(viewport?.sourceWidth ?: INPUT_WIDTH,
                viewport?.sourceHeight ?: INPUT_HEIGHT, sink.width, sink.height)
            GLES20.glViewport(bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniform1i(imageLocation, 0)
            GLES20.glUniformMatrix4fv(transformLocation, 1, false, matrix, 0)
            GLES20.glUniform4fv(contentLocation, 1, floatArrayOf(0f, 0f, 1f, 1f), 0)
            GLES20.glUniform4fv(sourceBoundsLocation, 1, floatArrayOf(0f, 0f, 1f, 1f), 0)
            drawQuad(positionLocation, uvLocation)
            checkGl("mirror-draw")
            requireEgl(EGL14.eglSwapBuffers(display, mirrorWindow), "mirror-swap")
            mirrorDirty = false
        } catch (error: Exception) {
            System.err.println("SHELL_MIRROR_OPTIONAL_SURFACE_DETACHED type=${error.javaClass.simpleName}")
            removeMirror()
        } finally {
            requireEgl(EGL14.eglMakeCurrent(display, window, window, context), "encoder-resume")
        }
    }

    private fun removeMirror() {
        if (mirrorWindow != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, mirrorWindow)
            mirrorWindow = EGL14.EGL_NO_SURFACE
        }
        mirror?.surface?.release()
        mirror = null
        mirrorDirty = false
    }

    private fun drawQuad(position: Int, uv: Int) {
        vertices.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(position)
        vertices.position(2)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun close() {
        System.err.println("SHELL_GL_SUBMISSIONS source_updates=$sourceUpdates submitted=$submittedFrames repeated_draws=$repeatedDraws " +
            "presentation=actual_monotonic_submit_time encoded_pts_retimed=false source_motion_fps_not_claimed=true")
        var failure: Exception? = null
        fun release(action: () -> Unit) {
            try { action() } catch (error: Exception) {
                if (failure == null) { failure = error } else { failure!!.addSuppressed(error) }
            }
        }
        release { removeMirror() }
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
                    if (paddingProgram != 0) { GLES20.glDeleteProgram(paddingProgram) }
                    if (texture != 0) { GLES20.glDeleteTextures(1, intArrayOf(texture), 0) }
                    if (paddingTexture != 0) { GLES20.glDeleteTextures(1, intArrayOf(paddingTexture), 0) }
                    if (paddingFramebuffer != 0) { GLES20.glDeleteFramebuffers(1, intArrayOf(paddingFramebuffer), 0) }
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
        program = 0; texture = 0; paddingProgram = 0; paddingTexture = 0; paddingFramebuffer = 0
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

    private fun linkProgram(fragmentSource: String): Int {
        val linkedProgram = GLES20.glCreateProgram()
        var vertex = 0
        var fragment = 0
        try {
            vertex = shader(GLES20.GL_VERTEX_SHADER, ContentViewport.VERTEX)
            fragment = shader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            GLES20.glAttachShader(linkedProgram, vertex)
            GLES20.glAttachShader(linkedProgram, fragment)
            GLES20.glLinkProgram(linkedProgram)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(linkedProgram, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] != GLES20.GL_TRUE) {
                throw IOException("CENTER_GL_LINK ${GLES20.glGetProgramInfoLog(linkedProgram)}")
            }
            return linkedProgram
        } catch (failure: Exception) {
            GLES20.glDeleteProgram(linkedProgram)
            throw failure
        } finally {
            if (vertex != 0) { GLES20.glDeleteShader(vertex) }
            if (fragment != 0) { GLES20.glDeleteShader(fragment) }
        }
    }

    companion object {
        const val INPUT_WIDTH = 1280
        const val INPUT_HEIGHT = 720
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
