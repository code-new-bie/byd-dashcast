package com.byd.dashcast.phase1

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ProductUiModelTest {
    private fun view(state: ProjectionSessionController.State, kind: ProductOperation = ProductOperation.NONE,
        busy: Boolean = false, setup: Boolean = true, selected: Boolean = true, allowed: Boolean = true) =
        ProductUiModel.present(state, kind, busy, setup, selected, allowed)

    @Test fun firstUseLeadsToSetupInsteadOfAnUnexplainedDisabledButton() {
        assertEquals(ProductPresentation.Action.SETUP, view(ProjectionSessionController.State.IDLE, setup = false).action)
    }

    @Test fun missingOrUnavailableAppLeadsToSelection() {
        assertEquals(ProductPresentation.Action.CHOOSE, view(ProjectionSessionController.State.IDLE, selected = false).action)
        assertEquals(ProductPresentation.Action.CHOOSE, view(ProjectionSessionController.State.IDLE, allowed = false).action)
    }

    @Test fun startRemainsCancellableDuringConnectionPreparation() {
        val ui = view(ProjectionSessionController.State.STOPPED, ProductOperation.START, true)
        assertEquals(ProductPresentation.Action.STOP, ui.action)
        assertTrue(ui.enabled)
        assertFalse(ui.canChooseApp)
    }

    @Test fun streamingStopIsAvailableDuringLogExport() {
        val ui = view(ProjectionSessionController.State.STREAMING, ProductOperation.EXPORT, true)
        assertEquals(ProductPresentation.Action.STOP, ui.action)
        assertTrue(ui.enabled)
    }

    @Test fun aConnectingOperationCannotStartASecondSession() {
        val ui = view(ProjectionSessionController.State.IDLE, ProductOperation.CONNECT, true)
        assertEquals(ProductPresentation.Action.NONE, ui.action)
        assertFalse(ui.enabled)
        assertFalse(ui.modeEnabled)
    }

    @Test fun startingIsCancellableEvenBeforeAnAppIsSelected() {
        val ui = view(ProjectionSessionController.State.STARTING, selected = false)
        assertEquals(ProductPresentation.Action.STOP, ui.action)
        assertTrue(ui.enabled)
    }

    @Test fun stoppingDoesNotExposePrematureRestartOrModeToggle() {
        val ui = view(ProjectionSessionController.State.STOPPING)
        assertEquals(ProductPresentation.Action.NONE, ui.action)
        assertFalse(ui.enabled)
        assertFalse(ui.modeEnabled)
        assertFalse(ui.canChooseApp)
    }

    @Test fun unknownCleanupStaysBlockedAcrossAllPresentationOperations() {
        ProductOperation.values().forEach {
            val ui = view(ProjectionSessionController.State.QUARANTINED, it, true)
            assertFalse(ui.enabled)
            assertFalse(ui.modeEnabled)
            assertEquals(ProductPresentation.Action.NONE, ui.action)
        }
    }

    @Test fun recoverableDisconnectStillOffersTheGuardedRecoveryPath() {
        listOf(ProjectionSessionController.State.DISCONNECTED, ProjectionSessionController.State.RECOVERY_REQUIRED).forEach {
            val ui = view(it)
            assertEquals(ProductPresentation.Action.START, ui.action)
            assertTrue(ui.enabled)
            assertEquals("重新连接", ui.actionLabel)
        }
    }

    @Test fun anAbsentOrAmbiguousUsbObservationNeverMeansHostConfirmed() {
        listOf(ProductConnection.ABSENT, ProductConnection.AMBIGUOUS, ProductConnection.UNAVAILABLE).forEach {
            assertFalse(it.observed)
            assertFalse(it.description.contains("U盘"))
            assertFalse(it.description.contains("成功"))
        }
        assertTrue(ProductConnection.PERMISSION.observed)
    }

    @Test fun unknownErrorsNeverExposeRawCodesPathsOrExceptionTypes() {
        val raw = "IllegalStateException APP_OWNER_123 /data/user/private/key"
        val ui = ProductErrors.explain(IOException(raw))
        assertFalse(ui.contains("APP_OWNER_123"))
        assertFalse(ui.contains("/data/"))
        assertFalse(ui.contains("IllegalStateException"))
        assertTrue(ui.contains("导出日志"))
    }

    @Test fun firmwareAndCleanupErrorsStillRequireTheirActualRecoverySteps() {
        assertTrue(ProductErrors.explain(IOException("PEER_RECOVERY_INCOMPATIBLE")).contains("配套"))
        assertTrue(ProductErrors.explain(IOException("CLEANUP_UNCONFIRMED")).contains("重启仪表"))
        assertTrue(ProductErrors.explain(IOException("CLEANUP_UNCONFIRMED: APP_INDEPENDENT_FAILED")).contains("重启仪表"))
        assertTrue(ProductErrors.explain(IOException("DETACHED_RECOVERY_RECONNECT_REQUIRED")).contains("数据线"))
    }

    @Test fun appPreflightNeverClaimsSuccessfulVehicleCompatibility() {
        val allowed = AppEntryCapability(true, false, "", "raw private evidence")
        val blocked = AppEntryCapability(false, false, "APP_ENTRY_NOT_ASSESSED", "raw private evidence")
        assertFalse(ProductUiModel.appSummary(allowed).contains("验证通过"))
        assertTrue(ProductUiModel.appIssue(allowed).contains("实际支持"))
        assertTrue(ProductUiModel.appIssue(blocked).contains("刷新"))
        assertFalse(ProductUiModel.appIssue(blocked).contains("raw private evidence"))
    }
}
