package com.byd.dashcast.phase1

import android.app.Activity
import android.os.Bundle

/** Compatibility for older fixed shell routes. No UI or second mirror consumer is created here. */
class MirrorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MirrorWorkspace.open(this)
        finish()
    }
}
