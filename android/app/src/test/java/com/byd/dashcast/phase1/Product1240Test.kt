package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/** 1.24.0: a quarantined session is cleaned up automatically instead of asking for restarts. */
class Product1240Test {
    private val listing = """
          PID  PPID USER           ARGS
            1     0 root           /init second_stage
          880     1 shell          /system/bin/sh -c ps -A -o PID,PPID,USER,ARGS
         4100     1 shell          /system/bin/app_process /system/bin com.byd.dashcast.phase1.ShellScreenCapture 0 FIT remote:com.example.map/.Main
         4120  4100 shell          /system/bin/logcat -b events -v epoch -T 1
         4121  4120 shell          /system/bin/sleep 9
         4130  4100 system         /system/bin/surfaceflinger-helper
         4200     1 shell          /system/bin/app_process /system/bin com.byd.dashcast.phase1.ShellExternalDisplay 0123 full
         4300     1 u0_a117        com.example.map
         4400     1 u0_a117        /data/app/x/app_process com.byd.dashcast.phase1.ShellScreenCapture
    """.trimIndent()

    @Test fun onlyTheShellCaptureHelperAndItsShellChildrenAreTargeted() {
        val targets = LeftoverCapture.targets(LeftoverCapture.parse(listing))
        // Children first, then the helper; standby display, cast app, other users and system processes stay.
        assertEquals(listOf(4121, 4120, 4100), targets.map { it.pid })
    }

    @Test fun cleanEndsTheHelperAndWaitsUntilItIsGone() {
        val commands = mutableListOf<String>()
        var killed = false
        val ended = LeftoverCapture.clean({ command ->
            commands += command
            if (command.startsWith("kill -9")) { killed = true; "" }
            else if (killed) listing.lines().filterNot { it.contains(" 4100 ") || it.contains("ShellScreenCapture 0") ||
                it.contains(" 4120 ") || it.contains(" 4121 ") }.joinToString("\n")
            else listing
        }, {}, {})
        assertEquals(3, ended)
        assertEquals(LeftoverCapture.LIST, commands[0])
        assertEquals("kill -9 4121 4120 4100 2>/dev/null; true", commands[1])
        assertEquals(3, commands.size)
    }

    @Test fun nothingLeftNeedsNoKillAndAStuckHelperFailsTheCleanup() {
        val idle = listing.lines().filterNot { it.contains("ShellScreenCapture 0") }.joinToString("\n")
        val seen = mutableListOf<String>()
        assertEquals(0, LeftoverCapture.clean({ seen += it; idle }, {}, {}))
        assertEquals(listOf(LeftoverCapture.LIST), seen)
        try {
            LeftoverCapture.clean({ listing }, {}, {})
            fail("a helper that does not go away must not count as cleaned")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.startsWith("LEFTOVER_CAPTURE_STILL_RUNNING"))
        }
    }

    @Test fun theSessionTellsWhichCleanupIsInDoubt() {
        fun run(failure: Exception): Pair<ProjectionSessionController.State, ProjectionSessionController.CleanupScope?> {
            var result: Pair<ProjectionSessionController.State, ProjectionSessionController.CleanupScope?>? = null
            val owner = ProjectionSessionController(Runnable::run, object : ProjectionSessionController.Observer {
                override fun state(state: ProjectionSessionController.State, detail: String) { result = state to null }
                override fun quarantined(detail: String, scope: ProjectionSessionController.CleanupScope) {
                    result = ProjectionSessionController.State.QUARANTINED to scope
                }
            })
            owner.start { _, _, _, _ -> throw failure }
            assertEquals(ProjectionSessionController.State.QUARANTINED, owner.state())
            assertFalse(owner.start { _, _, _, _ -> fail("this owner stays blocked") })
            return result!!
        }
        val capture = ProjectionSessionController.CleanupScope.CAPTURE
        assertEquals(capture, run(ProjectionSessionController.CaptureExitUnconfirmedException("x", null, true)).second)
        assertEquals(ProjectionSessionController.CleanupScope.CAPTURE_AND_PEER,
            run(ProjectionSessionController.CaptureExitUnconfirmedException("x", null, false)).second)
        assertEquals(ProjectionSessionController.CleanupScope.LOCAL,
            run(ProjectionSessionController.CleanupUnconfirmedException("x", null)).second)
        assertEquals(ProjectionSessionController.CleanupScope.LOCAL, run(IllegalStateException("x")).second)
        // Observers written as a plain lambda still hear about the quarantine.
        val states = mutableListOf<ProjectionSessionController.State>()
        val plain = ProjectionSessionController(Runnable::run) { state, _ -> states += state }
        plain.start { _, _, _, _ -> throw ProjectionSessionController.CaptureExitUnconfirmedException("x", null, true) }
        assertEquals(ProjectionSessionController.State.QUARANTINED, states.last())
    }

    @Test fun onlyAMissingHelperExitWithUsbClosedIsCaptureScoped() {
        assertTrue(CleanupRecoveryPolicy.captureOnly(false, true, false))
        assertFalse(CleanupRecoveryPolicy.captureOnly(true, true, false))
        assertFalse(CleanupRecoveryPolicy.captureOnly(false, false, false))
        assertFalse(CleanupRecoveryPolicy.captureOnly(false, true, true))
        val source = File("src/main/java/com/byd/dashcast/phase1/ContinuousProjectionTask.kt").readText()
        assertTrue(source.contains("CleanupRecoveryPolicy.captureOnly(captureClean, localUsbClosed, partialOpenUnknown)"))
        assertTrue(source.contains("ProjectionSessionController.CaptureExitUnconfirmedException("))
    }

    @Test fun aCleanedHelperReleasesTheLockToStoppedOrTheInstrumentCheck() {
        val q = ProjectionSessionController.State.QUARANTINED
        val journal = ProjectionJournal()
        journal.state(q, "CLEANUP_UNCONFIRMED capture_exit=false", ProjectionSessionController.CleanupScope.CAPTURE)
        assertTrue(journal.leftoverCaptureCleaned("helper_processes_ended=1"))
        assertEquals(ProjectionSessionController.State.STOPPED, journal.currentState())
        assertNull(journal.cleanupScope())

        val peer = ProjectionJournal()
        peer.state(q, "CLEANUP_UNCONFIRMED capture_exit=false receiver=false",
            ProjectionSessionController.CleanupScope.CAPTURE_AND_PEER)
        assertTrue(peer.leftoverCaptureCleaned("helper_processes_ended=0"))
        assertEquals(ProjectionSessionController.State.RECOVERY_REQUIRED, peer.currentState())
        // The page then reads the instrument check, not the lockout.
        assertFalse(peer.status().contains("CLEANUP_UNCONFIRMED"))
        assertEquals("仪表正在恢复，稍候点击“重新连接”即可继续。", ProductErrors.explain(IOException(peer.status())))

        val local = ProjectionJournal()
        local.state(q, "CLEANUP_UNCONFIRMED local_usb_closed=false")
        assertEquals(ProjectionSessionController.CleanupScope.LOCAL, local.cleanupScope())
        assertFalse(local.leftoverCaptureCleaned("helper_processes_ended=1"))
        assertEquals(q, local.currentState())
        assertFalse(local.restartedAfterLocalCleanup("not a fresh process"))

        val fresh = ProjectionJournal()
        assertTrue(fresh.restartedAfterLocalCleanup("APP_RESTARTED"))
        assertEquals(ProjectionSessionController.State.RECOVERY_REQUIRED, fresh.currentState())
        assertTrue(fresh.status().contains("PEER_RECOVERY_REQUIRED"))
    }

    @Test fun thePageShowsTheCleanupAndOffersTheRestartWhenItFails() {
        val q = ProjectionSessionController.State.QUARANTINED
        fun view(cleanup: ProductCleanup, queued: Boolean = false, busy: Boolean = true) = ProductUiModel.present(
            q, if (busy) ProductOperation.CLEANUP else ProductOperation.NONE, busy, true, true, cleanup, queued)
        view(ProductCleanup.RUNNING).let {
            assertEquals("正在清理上次投屏", it.title)
            assertEquals(ProductPresentation.Action.START, it.action)
            assertTrue(it.enabled)
            assertFalse(it.modeEnabled)
        }
        view(ProductCleanup.RUNNING, queued = true).let {
            assertEquals("清理后自动开始", it.actionLabel)
            assertFalse(it.enabled)
        }
        view(ProductCleanup.FAILED, busy = false).let {
            assertEquals(ProductPresentation.Action.RESTART, it.action)
            assertEquals("重启应用", it.actionLabel)
            assertTrue(it.enabled)
            assertFalse(it.modeEnabled)
        }
        view(ProductCleanup.NONE, busy = false).let {
            assertEquals(ProductPresentation.Action.NONE, it.action)
            assertFalse(it.enabled)
        }
        assertEquals("上次投屏没能自动清理。请点“重启应用”，仍不行时再重启仪表。",
            ProductErrors.explain(IOException("QUARANTINED: CLEANUP_UNCONFIRMED capture_exit=false")))
    }

    @Test fun automaticRestartsAreSpacedSoARepeatingFailureFallsBackToTheUser() {
        assertTrue(AppRestart.allowAutomatic(0L, 1_000_000L))
        assertFalse(AppRestart.allowAutomatic(1_000_000L, 1_000_000L + 60_000L))
        assertTrue(AppRestart.allowAutomatic(1_000_000L, 1_000_000L + AppRestart.AUTOMATIC_GAP_MILLIS))
        // A clock set back does not block restarts forever.
        assertTrue(AppRestart.allowAutomatic(2_000_000L, 1_000_000L))
    }

    @Test fun controllerCleansUpWhenTheSessionIsQuarantinedAndQueuesAStart() {
        val source = File("src/main/java/com/byd/dashcast/phase1/ProductController.kt").readText()
        assertTrue(source.contains("ProjectionService.setQuarantineListener { sessionQuarantined() }"))
        val start = source.substringAfter("fun startApp(selected: ProductApp)").substringBefore("\n    }\n")
        assertTrue(start.contains("cleanup == ProductCleanup.RUNNING") && start.contains("startAfterCleanup = selected"))
        val clean = source.substringAfter("private fun cleanUpQuarantine()").substringBefore("\n    private fun ")
        assertTrue(clean.contains("AppRestart.allowAutomatic(app)"))
        assertTrue(clean.contains("LeftoverCapture.clean("))
        assertTrue(clean.contains("ProjectionService.leftoverCaptureCleaned("))
        val service = File("src/main/java/com/byd/dashcast/phase1/ProjectionService.kt").readText()
        assertTrue(service.contains("JOURNAL.state(state, detail, scope)"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("<receiver android:name=\".RestartReceiver\" android:exported=\"false\" />"))
    }
}
