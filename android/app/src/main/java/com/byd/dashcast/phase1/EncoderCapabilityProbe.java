package com.byd.dashcast.phase1;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

import java.util.function.Consumer;

/**
 * Read-only advertised AVC capabilities; does not instantiate an encoder or certify a successful run.
 *
 * @author Codex
 * @date 2026/10/10
 */
final class EncoderCapabilityProbe {
    private static final int MAX_ENCODERS = 8;

    private EncoderCapabilityProbe() {
    }

    static void logHardwareAvc(Consumer<String> log) {
        int count = 0;
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder() || !info.isHardwareAccelerated() || !hasAvc(info)) {
                    continue;
                }
                if (count++ >= MAX_ENCODERS) {
                    log.accept("AVC_CAPABILITY_LIMIT " + MAX_ENCODERS);
                    break;
                }
                logOne(info, log);
            }
            log.accept("AVC_CAPABILITY_ADVERTISEMENT_ONLY; configure/start/output still require runtime validation");
        } catch (RuntimeException error) {
            log.accept("AVC_CAPABILITY_UNAVAILABLE type=" + error.getClass().getSimpleName());
        }
    }

    private static boolean hasAvc(MediaCodecInfo info) {
        for (String type : info.getSupportedTypes()) {
            if (MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }

    private static void logOne(MediaCodecInfo info, Consumer<String> log) {
        MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
        boolean surface = false;
        boolean flexibleYuv = false;
        for (int color : caps.colorFormats) {
            surface |= color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
            flexibleYuv |= color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
        }
        log.accept("AVC_CAP name=" + info.getName() + " surface=" + surface + " flexibleYuv=" + flexibleYuv
                + " size1920x720at30=" + (video != null && video.areSizeAndRateSupported(1920, 720, 30))
                + " widthAlign=" + (video == null ? -1 : video.getWidthAlignment())
                + " heightAlign=" + (video == null ? -1 : video.getHeightAlignment()));
        for (MediaCodecInfo.CodecProfileLevel level : caps.profileLevels) {
            if (level.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) {
                log.accept("AVC_BASELINE_LEVEL name=" + info.getName() + " level=" + level.level);
            }
        }
    }
}
