package com.byd.dashcast.phase1.ncm;

/**
 * Ordered best-effort USB cleanup; one failed step never skips releasing the remaining resources.
 * @author Codex
 * @date 2026/10/11
 */
public final class NcmRelease {
    /** Owned USB resource operations, injected independently of Android for tests. */
    public interface Steps {
        /** Selects data alternate 0 if the data interface was claimed. */
        boolean deactivateData();

        /** Releases the owned data interface, or succeeds if it was never claimed. */
        boolean releaseData();

        /** Releases the owned control interface, or succeeds if it was never claimed. */
        boolean releaseControl();

        /** Closes the owned USB device connection. */
        void closeConnection();
    }

    private NcmRelease() {
    }

    /** Calls all four steps in order, reporting failure without inventing a Host-role acknowledgement. */
    public static boolean finish(Steps steps) {
        if (steps == null) {
            throw new IllegalArgumentException("Missing NCM release adapter");
        }
        boolean confirmed = attempt(steps::deactivateData, "USB_DATA_ALT0_CLEANUP");
        confirmed &= attempt(steps::releaseData, "USB_RELEASE_DATA");
        confirmed &= attempt(steps::releaseControl, "USB_RELEASE_CONTROL");
        try {
            steps.closeConnection();
        } catch (RuntimeException error) {
            confirmed = false;
            LogHub.throwable("usb", "USB_CLOSE_CONNECTION rejected", error);
        }
        return confirmed;
    }

    private static boolean attempt(java.util.function.BooleanSupplier action, String name) {
        try {
            boolean result = action.getAsBoolean();
            LogHub.i("usb", name + "=" + result);
            return result;
        } catch (RuntimeException error) {
            LogHub.throwable("usb", name + " rejected", error);
            return false;
        }
    }
}
