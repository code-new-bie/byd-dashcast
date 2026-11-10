package com.byd.dashcast.phase1

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Synthetic inputs: not Android Binder, keyboard or vehicle acceptance.
 * @author Codex
 * @date 2026/10/25
 */
class MirrorProjectionPolicyTest {
    private fun task(id: Int = 2, display: Int = 7, user: Int = 0, root: String = "example.app/.Main",
        top: String = root, launcher: Boolean = true) = AppTaskPolicy.Task(id, id + 20, display, user, root, top, true, launcher)
    private fun owner() = MirrorTaskPolicy("example.app", 0, 7, setOf(1))

    @Test fun existingBusinessTaskIsNotAnIconDuplicate() {
        val policy = owner()
        policy.adopt(task(1, 0), true)
        assertEquals(task(1, 0), policy.find(listOf(task(1, 0))))
        assertFalse(policy.disposableIconTask(task(1, 0)))
        assertEquals(listOf(task(1)), policy.returnCandidates(listOf(task(1))))
    }
    @Test fun boundRootReplacementIsRejectedEvenWithinSamePackage() {
        val policy = owner().also { it.adopt(task()) }
        assertThrows(IOException::class.java) { policy.find(listOf(task(root = "example.app/.Other"))) }
    }
    @Test fun onlyNewOwnedDisplayForwardingSuccessorsCanBeAdopted() {
        val policy = owner().also { it.adopt(task()) }
        val successor = task(3, root = "example.app/.Map")
        assertEquals(successor, policy.find(listOf(task(1, 0), successor)))
        assertFalse(policy.disposableIconTask(successor.copy(display = 0)))
    }
    @Test fun noPreexistingSuccessorAdoption() {
        val policy = owner().also { it.adopt(task()) }
        assertNull(policy.find(listOf(task(1))))
        assertThrows(IOException::class.java) { policy.adopt(task(1)) }
    }
    @Test fun duplicateIdentityAndAmbiguousSuccessorAreRejected() {
        val policy = owner().also { it.adopt(task()) }
        assertThrows(IOException::class.java) { policy.find(listOf(task(), task())) }
        assertThrows(IOException::class.java) { policy.find(listOf(task(3), task(4))) }
    }
    @Test fun primaryMigrationDoesNotAuthorizeBusinessDeletion() {
        val policy = owner().also { it.adopt(task()) }
        assertEquals(task(display = 0), policy.find(listOf(task(display = 0))))
        assertFalse(policy.disposableIconTask(task(display = 0)))
    }
    @Test fun foreignUserPackageDisplayAndNegativeIdAreRejected() {
        for (invalid in listOf(task(-1), task(user = 10), task(root = "other.app/.Main"), task(display = 8))) {
            assertThrows(IOException::class.java) { owner().adopt(invalid, true) }
        }
    }
    @Test fun onlyNewLauncherDuplicateOnPrimaryIsDisposable() {
        val policy = owner().also { it.adopt(task()) }
        assertTrue(policy.disposableIconTask(task(3, 0)))
        for (protected in listOf(task(1, 0), task(3), task(3, 0, user = 10), task(3, 0, launcher = false),
                task(3, 0, root = "other.app/.Main"), task(3, 0, top = "other.app/.Prompt"))) {
            assertFalse(policy.disposableIconTask(protected))
        }
    }
    @Test fun returnNeverIncludesForeignUserPackageOrDisplay() {
        val policy = owner().also { it.adopt(task(1), true) }
        assertEquals(listOf(task(1), task(3)), policy.returnCandidates(listOf(task(1), task(3), task(4, 0),
            task(5, user = 10), task(6, root = "other.app/.Main"))))
    }
    @Test fun unavailableTopDoesNotLoseBusinessIdentity() {
        val policy = owner().also { it.adopt(task(1), true) }
        assertEquals(task(1, top = ""), policy.find(listOf(task(1, top = ""))))
        assertEquals(listOf(task(1, top = "other.app/.Permission")),
            policy.returnCandidates(listOf(task(1, top = "other.app/.Permission"))))
    }
    @Test fun returnCannotPretendAnOwnedIdentityChangeWasAlreadyCleaned() {
        val policy = owner().also { it.adopt(task(1), true) }
        for (changed in listOf(task(1, root = "example.app/.Replacement"), task(1, user = 10), task(1, display = 8))) {
            assertThrows(IOException::class.java) { policy.returnCandidates(listOf(changed)) }
        }
        assertThrows(IOException::class.java) { policy.returnCandidates(listOf(task(1), task(1))) }
    }
    @Test fun thousandIconCyclesRetainOriginalIdentity() {
        val policy = owner().also { it.adopt(task(1), true) }
        repeat(1000) { offset ->
            assertTrue(policy.disposableIconTask(task(offset + 10, 0)))
            assertEquals(1, policy.find(listOf(task(1), task(offset + 10, 0)))!!.id)
            assertFalse(policy.disposableIconTask(task(1, 0)))
        }
    }
    @Test fun startupWaitsForStableExactRootWithoutWeakeningBoundIdentity() {
        val startup = MirrorStartupPolicy("example.app", 0, 7, setOf(1))
        assertNull(startup.observe(listOf(task()), 0))
        val map = task(root = "example.app/.Map")
        assertNull(startup.observe(listOf(map), 100_000_000))
        assertNull(startup.observe(listOf(map), 299_999_999))
        assertEquals(map, startup.observe(listOf(map), 300_000_000))
    }
    @Test fun startupRejectsForeignAmbiguousDuplicateOrExistingCandidates() {
        val startup = MirrorStartupPolicy("example.app", 0, 7, setOf(1))
        for (snapshot in listOf(listOf(task(1)), listOf(task(display = 8)), listOf(task(user = 10)),
                listOf(task(top = "")), listOf(task(), task()), listOf(task(), task(3)),
                listOf(task(root = "other.app/.Main")))) {
            assertNull(startup.observe(snapshot, 0))
            assertNull(startup.observe(snapshot, 500_000_000))
        }
    }
    @Test fun geometryPreservesAspectAndRejectsLetterbox() {
        for ((w, h) in listOf(1536 to 384, 1200 to 640)) {
            val box = MirrorGeometry.fit(w, h, 1920, 1080)
            assertEquals(w.toFloat() / h, box.width / box.height, 0.0001f)
            assertTrue(box.contains(960f, 540f))
            assertFalse(box.contains(960f, 0f))
            assertFalse(box.contains(Float.NaN, 540f))
        }
        val portrait = MirrorGeometry.fit(720, 1280, 1920, 720)
        assertFalse(portrait.contains(0f, 360f))
        assertFalse(portrait.contains(portrait.left + portrait.width, 360f))
        assertThrows(IllegalArgumentException::class.java) { MirrorGeometry.fit(0, 1, 1, 1) }
    }
    @Test fun nativeProfilesAreOptionalNotABusinessAllowlist() {
        val text = """[{"package":"example.app","component":"example.app/.Meter","intExtras":{"mode":4}}]"""
        assertEquals(mapOf("mode" to 4), ProjectionEntryRouter.parse(text).single().extras)
        assertTrue(ProjectionEntryRouter.parse("[]").isEmpty())
    }
    @Test fun nativeProfilesRejectCommandInjectionCrossPackageAndDuplicates() {
        for (text in listOf("""[{"package":"example.app","component":"other.app/.Meter"}]""",
                """[{"package":"example.app;id","component":"example.app/.Meter"}]""",
                """[{"package":"example.app","component":"example.app/.Meter;id"}]""",
                """[{"package":"example.app","component":"example.app/.Meter","intExtras":{"x;id":4}}]""",
                """[{"package":"example.app","component":"example.app/.Meter"},{"package":"example.app","component":"example.app/.Meter2"}]""")) {
            assertThrows(IllegalArgumentException::class.java) { ProjectionEntryRouter.parse(text) }
        }
        assertThrows(IllegalArgumentException::class.java) { ProjectionEntryRouter.parse(" ".repeat(16385)) }
    }
    @Test fun endpointChecksCallerSizeAndClosedState() {
        assertTrue(MirrorCommandPolicy.caller(10123, 10123, MirrorWire.MAX_PARCEL, false))
        for ((uid, expected, size, closed) in listOf(listOf(2000, 10123, 12, false),
                listOf(10123, 10123, MirrorWire.MAX_PARCEL + 1, false), listOf(10123, 10123, -1, false),
                listOf(10123, 10123, 12, true), listOf(-1, -1, 12, false))) {
            assertFalse(MirrorCommandPolicy.caller(uid as Int, expected as Int, size as Int, closed as Boolean))
        }
    }
    @Test fun endpointRejectsMalformedPointerCoordinatesAndTimes() {
        assertTrue(MirrorCommandPolicy.input(10, 64, 100, 100, 9))
        for ((p, h, t, i) in listOf(listOf(0, 0, 100, 0), listOf(11, 0, 100, 0),
                listOf(1, 65, 100, 0), listOf(1, 0, 1200, 0), listOf(1, 0, -1, 0), listOf(2, 0, 100, 2))) {
            assertFalse(MirrorCommandPolicy.input(p, h, t.toLong(), 100, i))
        }
        assertTrue(MirrorCommandPolicy.coordinate(0f, 0f, 1536, 384))
        for ((x, y) in listOf(-1f to 0f, 1536f to 0f, 0f to 384f, Float.NaN to 0f, 0f to Float.POSITIVE_INFINITY)) {
            assertFalse(MirrorCommandPolicy.coordinate(x, y, 1536, 384))
        }
    }
}
