package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test

class ApertureReadability1170Test {
    @Test fun allApplicationControlsSurviveFullAndSmallInstrumentFits() {
        for((w,h) in listOf(1920 to 990,1920 to 1080,1080 to 1794,1696 to 456)) {
            for(small in listOf(false,true)) {
                val viewport=ContentViewport.forBusiness(BusinessDisplayProfile(w,h,160),small)
                val result=SourceRenderMapping.create(viewport,false)
                assertArrayEquals(floatArrayOf(0f,0f,1f,1f),result.source,0f)
                val px=if(small).4f else 1f;val py=if(small).6f else 1f
                assertEquals(w.toFloat()/h,result.content[2]*1920*px/(result.content[3]*720*py),.00001f)
                assertTrue(viewport.readableCandidates().any { box ->
                    result.content[0]>=box[0]-.00001f && result.content[1]>=box[1]-.00001f &&
                        result.content[0]+result.content[2]<=box[0]+box[2]+.00001f &&
                        result.content[1]+result.content[3]<=box[1]+box[3]+.00001f
                })
                assertEquals(w,viewport.sourceWidth);assertEquals(h,viewport.sourceHeight)
                val main=MirrorGeometry.fit(w,h,w,h)
                assertEquals(w.toFloat(),main.width,0f);assertEquals(h.toFloat(),main.height,0f)
            }
        }
    }

    @Test fun accessMapFocusAcceptsDisplayMetadataButNeverLastOrSecondaryFocus() {
        val focus="  mCurrentFocus=Window{aabb u0 com.byd.automap/com.byd.automap.central.CentralActivity}"
        assertTrue(NamedWindowPolicy.mainMapFocused("  Display: mDisplayId=0 stacks=3\n$focus",0))
        assertFalse(NamedWindowPolicy.mainMapFocused("  Display: mDisplayId=1 stacks=3\n$focus",0))
        assertFalse(NamedWindowPolicy.mainMapFocused("  Display: mDisplayId=0 stacks=3\n"+focus.replace("mCurrentFocus","mLastFocus"),0))
    }
}
