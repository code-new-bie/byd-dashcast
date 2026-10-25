package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test

/** Synthetic log metadata, never reads a real log or changes activity dispatch. */
class MirrorLaunchPolicyTest {
    private fun line(time: String = "12.200", pkg: String = "example.app", uid: Int = 10050, user: Int = 0,
        action: String = "android.intent.action.MAIN", category: String = "android.intent.category.LAUNCHER") =
        " $time 1000 1000 I ActivityTaskManager: START u$user {act=$action cat=[$category] cmp=$pkg/.Main} from uid $uid"
    private fun policy() = MirrorLaunchPolicy("example.app", 0, 10100, 10200, 12_000_000_000L)

    @Test fun exactFreshLauncherStartIsAccepted() { assertTrue(policy().accept(line(), 12_300_000_000L)) }
    @Test fun callbackCannotReplayOldOrFutureMetadata() {
        val policy = policy()
        assertFalse(policy.accept(line("11.999"), 12_300_000_000L))
        assertFalse(policy.accept(line("14.000"), 12_300_000_000L))
        assertTrue(policy.accept(line(), 12_300_000_000L))
        assertFalse(policy.accept(line(), 12_300_000_000L))
        assertFalse(policy.accept(line("12.100"), 12_300_000_000L))
    }
    @Test fun ownerShellAndAppSelfStartsAreNotUserIconRequests() {
        for (uid in listOf(2000, 10100, 10200)) { assertFalse(policy().accept(line(uid = uid), 12_300_000_000L)) }
    }
    @Test fun unrelatedPackageUserActionOrCategoryDoesNotRoute() {
        for (input in listOf(line(pkg = "example.application"), line(pkg = "other.app"), line(user = 10),
                line(action = "android.intent.action.VIEW"), line(category = "android.intent.category.DEFAULT"),
                "START " + line(), line().replace("from uid", "caller"))) {
            assertFalse(policy().accept(input, 12_300_000_000L))
        }
    }
    @Test fun malformedClockOverflowOrOversizeIsRejected() {
        for (input in listOf(line("none"), line("999999999999999999999999.200"), "x".repeat(8193))) {
            assertFalse(policy().accept(input, 12_300_000_000L))
        }
    }
    @Test fun nanosecondClockAndSystemLauncherAreSupported() {
        assertTrue(policy().accept(line("12.000000001", uid = 1000), 12_300_000_000L))
    }
    @Test fun partialCategoryOrMissingComponentClassCannotTriggerRoute() {
        for (input in listOf(line(category = "android.intent.category.LAUNCHER_FAKE"),
                line().replace("example.app/.Main", "example.app"),
                line().replace("example.app/.Main", "example.app/"))) {
            assertFalse(policy().accept(input, 12_300_000_000L))
        }
    }
}
