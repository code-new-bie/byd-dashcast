package com.byd.dashcast.phase1

/** Two stable high-resolution sources for the established instrument destinations. */
internal data class WorkspaceDisplayProfile(val width: Int, val height: Int, val density: Int) {
    init {
        require(BusinessDisplayProfile.valid(width, height, density))
    }

    companion object {
        @JvmStatic fun forMode(center: Boolean): WorkspaceDisplayProfile =
            // RWVG: 1.8x / 2x of the small / full instrument sources, so one dp is one instrument pixel.
            if (center) WorkspaceDisplayProfile(3366, 1458, 480)
            else WorkspaceDisplayProfile(3824, 996, 320)
    }
}
