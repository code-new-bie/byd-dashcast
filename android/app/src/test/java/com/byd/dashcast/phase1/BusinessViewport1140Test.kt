package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test

class BusinessViewport1140Test {
    @Test fun physicalBusinessAspectNeverInheritsInstrumentCarrierAspect() {
        for ((w,h) in listOf(1920 to 960,1920 to 1080,1080 to 1794)) {
            val profile=BusinessDisplayProfile(w,h,320)
            for (small in listOf(false,true)) {
                val mapped=ContentViewport.forBusiness(profile,small)
                val carrier=if(small)ContentViewport.center()else ContentViewport.full()
                assertEquals(w,mapped.sourceWidth);assertEquals(h,mapped.sourceHeight)
                assertArrayEquals(carrier.normalized(),mapped.normalized(),0f)
            }
        }
    }
    @Test fun nativeMapKeepsWideIndependentEntryAndGenericCanFitOrCrop() {
        val source=ContentViewport.forBusiness(BusinessDisplayProfile(1920,960,240),false)
        assertEquals(1696,ContentViewport.full().sourceWidth)
        assertEquals(456,ContentViewport.full().sourceHeight)
        val fit=SourceRenderMapping.create(source,false)
        val crop=SourceRenderMapping.create(source,true)
        assertNotEquals(fit,crop)
    }
}
