package com.byd.dashcast.phase1

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AppEntryCapabilityTest {
    private fun activity(name: String, mode: Int = 0, affinity: String? = "main") =
        IndependentAppPlan.Activity(name, true, true, null, mode, affinity, false)

    @Test fun nativeVersionChangeWithSamePublicIsolatedEntryIsNotRejected() {
        val main = activity(IndependentAppPlan.BYD_PRIMARY, 2, "main")
        val meter = activity(IndependentAppPlan.BYD_METER, 2, "meter")
        for (version in listOf(1L, 220102029L, 220102030L, 0x1_00000001L)) {
            assertTrue(IndependentAppPlan.select(main, meter, version).nativeSecondary)
        }
    }

    @Test fun nativeInvalidVersionRootModeOrAffinityStillFailsSpecifically() {
        val main = activity(IndependentAppPlan.BYD_PRIMARY, 2, "main")
        val meter = activity(IndependentAppPlan.BYD_METER, 2, "meter")
        val version = assertThrows(IOException::class.java) { IndependentAppPlan.select(main, meter, 0) }
        assertTrue(version.message!!.contains("VERSION_UNAVAILABLE"))
        for (bad in listOf(meter.copy(root = main.root), meter.copy(launchMode = 3),
            meter.copy(affinity = "main"), meter.copy(affinity = null))) {
            assertThrows(IOException::class.java) { IndependentAppPlan.select(main, bad, 1) }
        }
    }

    @Test fun independentUnrelatedSingletonDoesNotRejectTheSelectedTask() {
        val launcher = activity("example.app/.Main")
        val settings = activity("example.app/.Settings", 2, "settings")
        IndependentAppPlan.requireMultiInstance(listOf(launcher, settings))
        assertFalse(IndependentAppPlan.select(launcher, launcher, 1).nativeSecondary)
    }

    @Test fun sameTaskSingletonRedirectAndReparentingAreNotWeakened() {
        val launcher = activity("example.app/.Splash")
        for (unsafe in listOf(activity("example.app/.Main", 2), activity("example.app/.Main").copy(reparents = true))) {
            val error = assertThrows(IOException::class.java) {
                IndependentAppPlan.requireMultiInstance(listOf(launcher, unsafe))
            }
            assertTrue(error.message!!.contains("offending=example.app/.Main"))
        }
    }

    @Test fun disabledUnrelatedActivityIsNotAReasonToReject() {
        IndependentAppPlan.requireMultiInstance(listOf(activity("example.app/.Main"),
            activity("example.app/.Disabled", 2).copy(enabled = false)))
    }

    @Test fun selectedSingletonIsStillRejectedRegardlessOfUnrelatedPages() {
        for (mode in 2..3) {
            val selected = activity("example.app/.Main", mode)
            assertThrows(IOException::class.java) { IndependentAppPlan.requireMultiInstance(listOf(selected)) }
        }
    }

    @Test fun unknownAffinityIsNotAssumedIndependent() {
        assertThrows(IOException::class.java) {
            IndependentAppPlan.requireMultiInstance(listOf(activity("example.app/.Main", affinity = null),
                activity("example.app/.Other", 2, null)))
        }
    }

    @Test fun diagnosticsAreBoundedSingleLineWithoutIntentOrFileData() {
        val input = "pkg.name/.Main\nforged=yes '" + "x".repeat(1000)
        val safe = IndependentAppPlan.safe(input)
        assertEquals(384, safe.length)
        assertFalse(safe.contains('\n'))
        assertFalse(safe.contains('='))
        assertFalse(safe.contains('\''))
    }

    @Test fun catalogueDoesNotDefaultToAnUnsupportedNavigationApp() {
        val bad = ProductApp("bad/.Main", "不支持", true, AppEntryCapability.UNASSESSED)
        val good = ProductApp("good/.Main", "可开始", true, AppEntryCapability(true, true, "", "proof"))
        assertEquals(good, ProductAppCatalog.select(listOf(bad, good), null))
        assertEquals(bad, ProductAppCatalog.select(listOf(bad, good), bad.component))
        assertNull(ProductAppCatalog.select(listOf(bad), null))
    }

    @Test fun capabilitiesDoNotMislabelStructuralChecksAsVehicleValidation() {
        assertFalse(AppEntryCapability.UNASSESSED.allowed)
        assertTrue(AppEntryCapability(true, true, "", "").summary.contains("以设备结果为准"))
        assertTrue(AppEntryCapability(false, false, "APP_NATIVE_SECONDARY_AFFINITY_NOT_INDEPENDENT", "")
            .summary.contains("未分离"))
    }

    @Test fun exportIncludesUnsupportedAppsWithoutATriedVideoSession() {
        val app = ProductApp("demo/.Main", "测试", true,
            AppEntryCapability(false, false, "APP_SINGLE_INSTANCE_UNSUPPORTED", "version=44 candidate=demo/.Main\n"))
        val output = ProductAppCatalog.diagnostics(listOf(app))
        assertTrue(output.contains("allowed=false"))
        assertTrue(output.contains("version=44"))
        assertTrue(output.contains("diagnostic_only=true"))
    }
}
