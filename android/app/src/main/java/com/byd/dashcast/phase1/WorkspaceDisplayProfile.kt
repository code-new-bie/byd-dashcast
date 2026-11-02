package com.byd.dashcast.phase1

/** Two stable high-resolution sources for the established instrument destinations. */
internal data class WorkspaceDisplayProfile(val width: Int, val height: Int, val density: Int) {
    init {
        require(BusinessDisplayProfile.valid(width, height, density))
    }

    companion object {
        @JvmStatic fun forMode(center: Boolean): WorkspaceDisplayProfile =
            if (center) WorkspaceDisplayProfile(2304, 1296, 480)
            else WorkspaceDisplayProfile(3392, 912, 320)
    }
}
