package com.byd.dashcast.phase1

/** Physical content bounds, not the encoded canvas or the feathered outer window.
 * Geometry is paired with RWV4 instrument firmware. Source UI is laid out at the
 * inner aspect before resampling; only edge pixels bleed beneath native feathers.
 * @author Codex
 * @date 2026/10/23
 */
internal data class ContentViewport(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val encodedLeft: Int,
    val encodedTop: Int,
    val encodedWidth: Int,
    val encodedHeight: Int
) {
    init {
        require(sourceWidth in 1..1920 && sourceHeight in 1..720)
        require(encodedLeft >= 0 && encodedTop >= 0 && encodedWidth > 0 && encodedHeight > 0)
        require(encodedLeft + encodedWidth <= 1920 && encodedTop + encodedHeight <= 720)
    }

    /** Bottom-left GL coordinates; Android/QNX profile coordinates are top-left. */
    fun normalized(): FloatArray = floatArrayOf(encodedLeft / 1920f,
        (720 - encodedTop - encodedHeight) / 720f, encodedWidth / 1920f, encodedHeight / 720f)

    companion object {
        @JvmStatic fun full(): ContentViewport = ContentViewport(1728, 384, 96, 144, 1728, 384)
        @JvmStatic fun center(): ContentViewport = ContentViewport(1200, 640, 60, 40, 1800, 640)

        // The SurfaceTexture matrix is applied AFTER effective-viewport mapping.
        // Applying it first would move/flip the inset when producers rotate buffers.
        const val VERTEX = "attribute vec4 position;attribute vec2 uv;varying vec2 tex;" +
            "void main(){gl_Position=position;tex=uv;}"
        const val FRAGMENT = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;varying vec2 tex;uniform samplerExternalOES image;" +
            "uniform mat4 transform;uniform vec4 content;" +
            "vec4 sampleAt(vec2 p){return texture2D(image,(transform*vec4(p,0.0,1.0)).xy);}" +
            "void main(){vec2 q=(tex-content.xy)/content.zw;vec2 p=clamp(q,0.0,1.0);" +
            "vec4 sharp=sampleAt(p);float d=max(max(-q.x,q.x-1.0),max(-q.y,q.y-1.0));" +
            "if(d>0.0){vec2 b=clamp(p,vec2(0.04),vec2(0.96));" +
            "vec4 soft=0.25*(sampleAt(b+vec2(0.02,0.02))+sampleAt(b+vec2(-0.02,0.02))+" +
            "sampleAt(b+vec2(0.02,-0.02))+sampleAt(b+vec2(-0.02,-0.02)));" +
            "gl_FragColor=mix(sharp,soft,smoothstep(0.0,0.012,d));}else{gl_FragColor=sharp;}}"
    }
}
