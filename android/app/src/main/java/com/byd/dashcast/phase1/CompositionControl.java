package com.byd.dashcast.phase1;

import java.io.IOException;

/** A single video stream owns its background lease and restoration.
 * @author Codex
 * @date 2026/10/21
 */
interface CompositionControl {
    /** Validate the matching receiver/HMI before media begins. */
    void prepare(NoAuthVideoTransport channel) throws IOException;
    /** Activate only after the first actual video acknowledgement. */
    void activate(NoAuthVideoTransport channel) throws IOException;
    /** Maintain only this healthy stream's lease. */
    void tick(NoAuthVideoTransport channel) throws IOException;
    /** Confirm owned restoration before media/USB teardown. */
    void restore(NoAuthVideoTransport channel) throws IOException;
}
