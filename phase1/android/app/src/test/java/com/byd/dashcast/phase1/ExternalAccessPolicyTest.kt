package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test

class ExternalAccessPolicyTest {
    @Test fun nativeMapPreservesItsNameWithoutBlockingAnOwnedStandby() {
        assertEquals(NamedDisplayContract.NATIVE_NAME,NamedDisplayContract.nativeName(emptyList()))
        assertEquals(NamedDisplayContract.NATIVE_NAME,NamedDisplayContract.nativeName(listOf(NamedDisplayContract.NAME)))
        assertTrue(NamedDisplayContract.matchesMeter(NamedDisplayContract.NATIVE_NAME))
        assertTrue(NamedDisplayContract.standbyAvailable(listOf(NamedDisplayContract.NATIVE_NAME),true))
        assertFalse(NamedDisplayContract.standbyAvailable(listOf(NamedDisplayContract.NATIVE_NAME),false))
        assertFalse(NamedDisplayContract.standbyAvailable(listOf(NamedDisplayContract.NAME),true))
        assertThrows(java.io.IOException::class.java) {NamedDisplayContract.nativeName(listOf("fission_foreign"))}
    }
    @Test fun fullWindowDumpMustNotTreatAnimatorSummariesAsRealWindows() {
        val dump="""WINDOW MANAGER ANIMATOR STATE
            Window #0: WindowStateAnimator{abc title}
              mHasSurface=true
            WINDOW MANAGER WINDOWS
              Window #0 Window{123 u0 sample.app/.Main}:
                mDisplayId=7 mOwnerUid=10123 mViewVisibility=0x0 mHasSurface=true
        """.trimIndent()
        assertEquals(setOf(10123),NamedWindowPolicy.owners(dump,7,10124,0))
    }
    @Test fun mapEntryRequiresPhysicalDisplayFocusAndExactCurrentUser() {
        val main="WINDOW MANAGER DISPLAY CONTENTS\n  Display: mDisplayId=0\n  mCurrentFocus=Window{a0123 u0 com.byd.automap/com.byd.automap.CentralActivity}\n"
        assertTrue(NamedWindowPolicy.mainMapFocused(main,0))
        assertFalse(NamedWindowPolicy.mainMapFocused(main,1))
        assertFalse(NamedWindowPolicy.mainMapFocused(main.replace("mDisplayId=0","mDisplayId=7"),0))
        assertFalse(NamedWindowPolicy.mainMapFocused(main.replace("com.byd.automap/","com.byd.automap.fake/"),0))
        assertFalse(NamedWindowPolicy.mainMapFocused(main.replace("mCurrentFocus=Window", "mLastFocus=Window"),0))
        assertFalse(NamedWindowPolicy.mainMapFocused("WINDOW MANAGER WINDOWS\n"+main.substringAfter("  Display: mDisplayId=0\n"),0))
    }
    @Test fun toggleAloneNeverStartsInstrument() {
        val policy=ExternalAccessPolicy()
        assertNull(policy.candidate(emptySet(),true,true,false,false))
        assertNull(policy.candidate(setOf(10001),true,false,false,false))
    }
    @Test fun acceptedWindowStartsOnlyOnceUntilItActuallyLeaves() {
        val policy=ExternalAccessPolicy()
        assertEquals(10001,policy.candidate(setOf(10001),true,true,false,false))
        policy.consume(10001)
        assertNull(policy.candidate(setOf(10001),true,true,false,false))
        assertNull(policy.candidate(emptySet(),true,true,false,false))
        assertEquals(10001,policy.candidate(setOf(10001),true,true,false,false))
    }
    @Test fun manualVideoKeepsPriorityAndOldExternalWindowCannotStealAfterStop() {
        val policy=ExternalAccessPolicy()
        assertNull(policy.candidate(setOf(10001),true,true,true,false))
        assertNull(policy.candidate(setOf(10001),true,true,false,false))
    }
    @Test fun replacingApplicationWaitsUntilPriorVideoHasFinished() {
        val policy=ExternalAccessPolicy()
        policy.consume(10001)
        assertNull(policy.candidate(setOf(10002),true,true,true,false))
        assertNull(policy.candidate(setOf(10001,10002),true,true,false,false))
        policy.candidate(emptySet(),true,true,false,false)
        assertEquals(10002,policy.candidate(setOf(10002),true,true,false,false))
    }
    @Test fun unknownObservationsAndBusyCoordinatorNeverStart() {
        val policy=ExternalAccessPolicy()
        assertNull(policy.candidate(setOf(10001),false,true,false,false))
        assertNull(policy.candidate(setOf(10001),true,true,false,true))
        assertEquals(10001,policy.candidate(setOf(10001),true,true,false,false))
    }
}
