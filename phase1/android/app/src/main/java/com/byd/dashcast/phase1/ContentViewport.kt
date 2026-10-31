package com.byd.dashcast.phase1

/** Sharp source covers the WHOLE native inner-rim opening, including its sides.
 * RWVE keeps 32px side outset and adds 8px above/below; safe bounds do not clip.
 * The carrier rectangle and native 1920x720 transport are separate sizes.
 * @author Codex
 * @date 2026/10/23
 */
internal data class ContentViewport @JvmOverloads constructor(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val encodedLeft: Int,
    val encodedTop: Int,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val physicalAspect: Float = encodedWidth.toFloat() / encodedHeight
) {
    init {
        require(sourceWidth in 1..3840 && sourceHeight in 1..2160 && sourceWidth.toLong() * sourceHeight <= 8_294_400)
        require(encodedLeft >= 0 && encodedTop >= 0 && encodedWidth > 0 && encodedHeight > 0)
        require(encodedLeft + encodedWidth <= 1920 && encodedTop + encodedHeight <= 720)
        require(physicalAspect.isFinite() && physicalAspect > 0f)
    }

    /** Bottom-left GL coordinates; Android/QNX profile coordinates are top-left. */
    fun normalized(): FloatArray = floatArrayOf(encodedLeft / 1920f,
        (720 - encodedTop - encodedHeight) / 720f, encodedWidth / 1920f, encodedHeight / 720f)

    fun withSourceSize(width: Int, height: Int): ContentViewport = copy(sourceWidth=width,sourceHeight=height)

    /** Complete content stays within the supplied aperture; the main task is unchanged. */
    fun readableCandidates(): List<FloatArray> {
        fun box(x: Float,y: Float,w: Float,h: Float) =
            floatArrayOf(x/1920f,(720f-y-h)/720f,w/1920f,h/720f)
        return when {
            encodedLeft==112 && encodedTop==112 && encodedWidth==1696 && encodedHeight==456 -> listOf(
                box(420f,140f,1080f,396f),box(260f,159f,1400f,375f))
            encodedLeft==0 && encodedTop==0 && encodedWidth==1920 && encodedHeight==720 -> listOf(
                box(60f,40f,1800f,640f))
            else -> listOf(normalized())
        }
    }

    companion object {
        @JvmStatic fun full(): ContentViewport = ContentViewport(1696, 456, 112, 112, 1696, 456)
        /** Historical carrier fixture sizes; live generic apps use forBusiness instead. */
        const val FULL_MIRROR_SOURCE_WIDTH = 3392
        const val FULL_MIRROR_SOURCE_HEIGHT = 912
        @JvmStatic fun forBusiness(profile: BusinessDisplayProfile, center: Boolean): ContentViewport {
            val destination = if (center) center() else full()
            return destination.copy(sourceWidth=profile.width,sourceHeight=profile.height)
        }
        /** Fully clear conservative box inside the curved full-mode aperture. */
        @JvmStatic fun fullSafeBounds(): IntArray = intArrayOf(320, 176, 1280, 336)
        // The RGBA overlay now provides the feather itself. Keep sharp source
        // throughout the physical window, including every partly visible edge.
        @JvmStatic fun center(): ContentViewport = ContentViewport(1280,720,0,0,1920,720,768f/432f)

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
