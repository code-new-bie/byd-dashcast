package com.byd.dashcast.phase1

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView

/** Fixed, DUMP-protected waiting content for an owned named-display session. */
class NamedWaitingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "等待应用按屏幕名投屏\n${NamedDisplayContract.NAME}\n\n在应用中选择此屏幕；回 Dashcast 可停止接入"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(17, 32, 48))
        })
    }
}
