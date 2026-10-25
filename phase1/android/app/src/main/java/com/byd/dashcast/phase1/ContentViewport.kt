package com.byd.dashcast.phase1

/** Physical content bounds, not the encoded canvas or the feathered outer window.
 * Geometry is paired with RWV6 instrument firmware. Native source UI uses the
 * inner aspect before resampling. Only low-frequency color fills native feathers.
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
        require(sourceWidth in 1..3840 && sourceHeight in 1..2160 && sourceWidth.toLong() * sourceHeight <= 8_294_400)
        require(encodedLeft >= 0 && encodedTop >= 0 && encodedWidth > 0 && encodedHeight > 0)
        require(encodedLeft + encodedWidth <= 1920 && encodedTop + encodedHeight <= 720)
    }

    /** Bottom-left GL coordinates; Android/QNX profile coordinates are top-left. */
    fun normalized(): FloatArray = floatArrayOf(encodedLeft / 1920f,
        (720 - encodedTop - encodedHeight) / 720f, encodedWidth / 1920f, encodedHeight / 720f)

    companion object {
        @JvmStatic fun full(): ContentViewport = ContentViewport(1408, 368, 256, 144, 1408, 368)
        @JvmStatic fun center(): ContentViewport = ContentViewport(1200, 640, 60, 40, 1800, 640)

        const val PADDING_WIDTH = 32
        const val PADDING_HEIGHT = 16

        // The SurfaceTexture matrix is applied AFTER effective-viewport mapping.
        // Applying it first would move/flip the inset when producers rotate buffers.
        const val VERTEX = "attribute vec4 position;attribute vec2 uv;varying vec2 tex;" +
            "void main(){gl_Position=position;tex=uv;}"
        const val FRAGMENT = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;varying vec2 tex;uniform samplerExternalOES image;" +
            "uniform mat4 transform;uniform vec4 content;uniform sampler2D padding;uniform vec4 sourceBounds;" +
            "vec4 sampleAt(vec2 p){if(sourceBounds.z>0.0&&sourceBounds.w>0.0){p=sourceBounds.xy+p*sourceBounds.zw;}" +
            "return texture2D(image,(transform*vec4(p,0.0,1.0)).xy);}" +
            "void main(){vec2 q=(tex-content.xy)/content.zw;" +
            "if(q.x<0.0||q.x>1.0||q.y<0.0||q.y>1.0){" +
            "gl_FragColor=texture2D(padding,clamp(q,0.0,1.0));}" +
            "else{gl_FragColor=sampleAt(q);}}"

        // Integrate once into 32x16, rather than many OES reads per padding pixel.
        // No high-resolution glyph is clamped and extruded through the feather.
        // This pass applies the producer transform; padding lookup must not repeat it.
        const val PADDING_FRAGMENT = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;varying vec2 tex;uniform samplerExternalOES image;uniform mat4 transform;uniform vec4 sourceBounds;" +
            "void main(){vec4 sum=vec4(0.0);" +
            "for(int x=0;x<8;x++){for(int y=0;y<8;y++){" +
            "vec2 p=clamp(tex+(vec2(float(x),float(y))-vec2(3.5))/vec2(64.0,32.0),0.0,1.0);" +
            "if(sourceBounds.z>0.0&&sourceBounds.w>0.0){p=sourceBounds.xy+p*sourceBounds.zw;}" +
            "sum+=texture2D(image,(transform*vec4(p,0.0,1.0)).xy);}}gl_FragColor=sum/64.0;}"
    }
}
