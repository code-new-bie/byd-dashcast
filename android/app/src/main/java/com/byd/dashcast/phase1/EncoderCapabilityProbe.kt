package com.byd.dashcast.phase1

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import java.util.function.Consumer

/**
 * Read-only advertised AVC capabilities; does not instantiate an encoder or certify a successful run.
 *
 * @author Codex
 * @date 2026/10/10
 */
internal object EncoderCapabilityProbe {
    private const val MAX_ENCODERS = 8

    @JvmStatic
    fun logHardwareAvc(log: Consumer<String>) {
        var count = 0
        try {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (!info.isEncoder || !info.isHardwareAccelerated || !hasAvc(info)) {
                    continue
                }
                if (count++ >= MAX_ENCODERS) {
                    log.accept("AVC_CAPABILITY_LIMIT $MAX_ENCODERS")
                    break
                }
                logOne(info, log)
            }
            log.accept("AVC_CAPABILITY_ADVERTISEMENT_ONLY; configure/start/output still require runtime validation")
        } catch (error: RuntimeException) {
            log.accept("AVC_CAPABILITY_UNAVAILABLE type=" + error.javaClass.simpleName)
        }
    }

    private fun hasAvc(info: MediaCodecInfo): Boolean =
        info.supportedTypes.any { MediaFormat.MIMETYPE_VIDEO_AVC.equals(it, ignoreCase = true) }

    private fun logOne(info: MediaCodecInfo, log: Consumer<String>) {
        val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val video = caps.videoCapabilities
        var surface = false
        var flexibleYuv = false
        for (color in caps.colorFormats) {
            surface = surface or (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            flexibleYuv = flexibleYuv or (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        }
        log.accept("AVC_CAP name=" + info.name + " surface=" + surface + " flexibleYuv=" + flexibleYuv +
            " size1920x720at30=" + (video != null && video.areSizeAndRateSupported(1920, 720, 30.0)) +
            " widthAlign=" + (video?.widthAlignment ?: -1) +
            " heightAlign=" + (video?.heightAlignment ?: -1))
        for (level in caps.profileLevels) {
            if (level.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) {
                log.accept("AVC_BASELINE_LEVEL name=" + info.name + " level=" + level.level)
            }
        }
    }
}
