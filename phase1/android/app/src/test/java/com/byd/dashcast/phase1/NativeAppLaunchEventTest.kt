package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test

class NativeAppLaunchEventTest {
    private val pkg = "com.autonavi.amapauto"
    private fun event(body: String = "0,9876,42,$pkg/.MainMapActivity,android.intent.action.MAIN,NULL,NULL,270532608",
        time: String = "1792668484.700") = "$time  1900  2100 I am_new_intent: [$body]"

    @Test fun actualOem30003SchemaSelectsOnlyTheAdoptedNativeLauncherTask() {
        assertTrue(NativeAppLaunchEvent.matches(event(), pkg, 42, 0, 1792668484700))
        assertTrue(NativeAppLaunchEvent.matches(event(time = "1792668484.700001"), pkg, 42, 0, 1792668484700))
        assertFalse(NativeAppLaunchEvent.matches(event(), pkg, 42, 0, 1792668484701))
    }

    @Test fun staleOtherTaskOtherUserDeepLinkInternalIntentAndMalformedEventsAreIgnored() {
        val base = "0,9876,42,$pkg/.MainMapActivity,android.intent.action.MAIN,NULL,NULL,270532608"
        for (line in listOf("", "x".repeat(4097), event(time = "1792668483.999"),
            event(base.replace("0,9876", "10,9876")), event(base.replace(",42,", ",43,")),
            event(base.replace(pkg, "$pkg.evil")), event(base.replace("MAIN", "VIEW")),
            event(base.replace("NULL,NULL", "NULL,amap://route")),
            event(base.replace("270532608", "0")), event(base.replace("270532608", "999999999999999999999")),
            event(base + ",trailing"), event().replace("am_new_intent", "am_create_activity"))) {
            assertFalse(line, NativeAppLaunchEvent.matches(line, pkg, 42, 0, 1792668484700))
        }
        assertFalse(NativeAppLaunchEvent.matches(event(), pkg, -1, 0, 0))
    }

    @Test fun nativeUsbFillIconCreationIsRecognizedButForwardedOrUnrelatedIntentsAreNot() {
        val launcher = "$pkg/com.autonavi.auto.remote.fill.UsbFillActivity"
        val creation = event("0,123,99,$launcher,android.intent.action.MAIN,NULL,NULL,270532608")
            .replace("am_new_intent", "am_create_activity")
        assertTrue(NativeAppLaunchEvent.matches(creation, pkg, 42, 0, 1792668484700, launcher))
        assertFalse(NativeAppLaunchEvent.matches(creation, pkg, 42, 0, 1792668484700))
        for (line in listOf(creation.replace("99,", "bad,"), creation.replace("99,", "-1,"),
            creation.replace("UsbFillActivity", "OtherActivity"), creation.replace("MAIN", "VIEW"),
            creation.replace("android.intent.action.MAIN", "NULL"), creation.replace("270532608", "131072"),
            creation.replace("0,123", "10,123"))) {
            assertFalse(line, NativeAppLaunchEvent.matches(line, pkg, 42, 0, 1792668484700, launcher))
        }
    }
}
