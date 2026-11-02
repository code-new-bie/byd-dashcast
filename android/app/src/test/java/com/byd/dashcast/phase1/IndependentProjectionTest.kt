package com.byd.dashcast.phase1

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class IndependentProjectionTest {
    private fun facts(component: String = "example.app/.Main", mode: Int = 0, affinity: String? = "main") =
        IndependentAppPlan.Activity(component, true, true, null, mode, affinity, false)

    @Test fun nativeMeterUsesAnotherActivityWithSamePackageAndUserData() {
        val main = facts("com.byd.automap/com.byd.automap.central.CentralActivity", 2, "com.byd.automap")
        val meter = facts(IndependentAppPlan.BYD_METER, 2, "com.byd.automap.extra")
        val plan = IndependentAppPlan.select(main, meter, IndependentAppPlan.VERIFIED_BYD_VERSION)
        assertTrue(plan.nativeSecondary)
        assertEquals(IndependentAppPlan.BYD_METER, plan.component)
        val args = plan.arguments(9, 0)
        assertEquals("0", args[args.indexOf("--user") + 1])
        assertEquals("9", args[args.indexOf("--display") + 1])
        assertFalse(args.contains("meterType")) // Do not invoke vehicle screen-status control extras.
        assertFalse(args.any { it.contains("force-stop") || it.contains("clear-task") })
    }

    @Test fun aliasesResolveToNativeEntryBeforeAnyTaskIsTouched() {
        assertEquals(IndependentAppPlan.BYD_METER,
            IndependentAppPlan.candidate("com.byd.automap/com.byd.automap.activity.StartupActivity"))
        assertEquals("example.app/.Main", IndependentAppPlan.candidate("example.app/.Main"))
    }

    @Test fun standardAndSingleTopAppsUseNewTaskWithoutSeparateUserOrData() {
        for (mode in 0..1) {
            val activity = facts(mode = mode)
            val plan = IndependentAppPlan.select(activity, activity, 1)
            assertFalse(plan.nativeSecondary)
            assertEquals("0x18000000", plan.arguments(2, 0)[8])
        }
    }

    @Test fun everyPackageRejectsSingleTaskAndSingleInstanceWithoutNativeEntry() {
        for (name in listOf("map.app/.Main", "video.app/.Main", "office.app/.Main")) {
            for (mode in 2..3) {
                val activity = facts(name, mode)
                assertThrows(IOException::class.java) { IndependentAppPlan.select(activity, activity, 1) }
            }
        }
    }

    @Test fun launcherAliasIsKeptForLaunchButOwnershipUsesRealActivity() {
        val alias = facts("example.app/.Icon").copy(root = "example.app/.Actual")
        val plan = IndependentAppPlan.select(alias, alias, 1)
        assertEquals("example.app/.Icon", plan.component)
        assertEquals("example.app/.Actual", plan.taskRoot)
        assertThrows(IOException::class.java) {
            IndependentAppPlan.select(alias, alias.copy(launchMode = 2), 1)
        }
    }

    @Test fun standardSplashCannotHideAnInternalSingletonWhichCouldStealPrimaryTask() {
        val splash = facts("example.app/.Splash")
        val main = facts("example.app/.Main", 2)
        assertThrows(IOException::class.java) { IndependentAppPlan.requireMultiInstance(listOf(splash, main)) }
        assertThrows(IOException::class.java) { IndependentAppPlan.requireMultiInstance(emptyList()) }
        IndependentAppPlan.requireMultiInstance(listOf(splash, main.copy(launchMode = 0)))
    }

    @Test fun privateDisabledPermissionForeignAndReparentingEntriesAreRejected() {
        val original = facts()
        for (invalid in listOf(original.copy(enabled = false), original.copy(exported = false),
            original.copy(permission = "private.permission"), original.copy(component = "other.app/.Main"),
            original.copy(reparents = true))) {
            assertThrows(IOException::class.java) { IndependentAppPlan.select(original, invalid, 1) }
        }
    }

    @Test fun nativeAdapterUsesInstalledCapabilitiesInsteadOfAnExactVersionGate() {
        val main = facts("com.byd.automap/com.byd.automap.central.CentralActivity", 2, "main")
        val meter = facts(IndependentAppPlan.BYD_METER, 2, "meter")
        assertTrue(IndependentAppPlan.select(main, meter, 1).nativeSecondary)
        assertThrows(IOException::class.java) {
            IndependentAppPlan.select(main, meter.copy(affinity = "main"), IndependentAppPlan.VERIFIED_BYD_VERSION)
        }
        assertThrows(IllegalArgumentException::class.java) {
            IndependentAppPlan.Plan("example.app/.Main", false).arguments(0, 0)
        }
    }

    private fun task(id: Int, display: Int, root: String = "example.app/.Main", user: Int = 0) =
        AppTaskPolicy.Task(id, 20 + id, display, user, root, root, display == 0)

    @Test fun existingPrimaryTaskIsExcludedAndNeverAdopted() {
        val original = task(1, 0)
        val policy = IndependentTaskPolicy(setOf(1), original.root, 0, 9)
        assertNull(policy.find(listOf(original)))
        assertEquals(2, policy.find(listOf(original, task(2, 9)))!!.id)
        assertEquals(0, original.display)
        assertThrows(IOException::class.java) { policy.verify(original) }
    }

    @Test fun originalIconAndRepeatedForegroundChangesCannotSwitchCaptureDisplay() {
        val policy = IndependentTaskPolicy(setOf(1), "example.app/.Main", 0, 9)
        val secondary = task(2, 9)
        repeat(1000) { index ->
            val main = task(1, 0).copy(visible = index % 2 == 0)
            assertEquals(secondary, policy.find(listOf(main, secondary)))
            assertEquals(0, main.display)
        }
    }

    @Test fun taskMovedToPrimaryForeignTopOrAmbiguousIsRejectedWithoutFallback() {
        val policy = IndependentTaskPolicy(setOf(1), "example.app/.Main", 0, 9)
        assertThrows(IOException::class.java) { policy.find(listOf(task(2, 0))) }
        assertThrows(IOException::class.java) { policy.find(listOf(task(2, 8))) }
        assertThrows(IOException::class.java) { policy.find(listOf(task(2, 9), task(3, 9))) }
        assertThrows(IOException::class.java) { policy.find(listOf(task(2, 9).copy(top = "foreign/.Main"))) }
        assertNull(policy.find(listOf(task(2, 9, user = 10))))
    }

    @Test fun taskQueriesShareOneBudgetAcrossInputAndOutputAtSixtyFps() {
        val schedule = TaskPollSchedule()
        var reads = 0
        repeat(600) { frame ->
            val now = frame * 16_666_667L
            repeat(4) { if (schedule.due(now)) { reads++ } }
        }
        assertEquals(100, reads)
        assertEquals(100L, schedule.queries)
    }

    @Test fun pollingDoesNotBlockFramesBetweenChecksAndRecoversFromClockDiscontinuity() {
        val schedule = TaskPollSchedule(100)
        assertTrue(schedule.due(0))
        assertFalse(schedule.due(99))
        assertTrue(schedule.due(100))
        assertTrue(schedule.due(0))
        assertFalse(schedule.due(50))
        assertTrue(schedule.due(100))
    }
}
