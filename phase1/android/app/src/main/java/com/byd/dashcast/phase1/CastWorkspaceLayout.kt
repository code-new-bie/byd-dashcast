package com.byd.dashcast.phase1

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Measure controls before the mirror, so short screens and IME never push stop off screen. */
internal class CastWorkspaceLayout(context: Context, private val heading: View,
    private val window: EmbeddedCastView, private val controls: View, private val feedback: View) : ViewGroup(context) {
    private val gap = (12 * resources.displayMetrics.density).toInt()

    init { addView(heading); addView(window); addView(controls); addView(feedback) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val limit = MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
        heading.measure(exactWidth, limit); controls.measure(exactWidth, limit); feedback.measure(exactWidth, limit)
        val remaining = (height - heading.measuredHeight - controls.measuredHeight - feedback.measuredHeight - gap * 3)
            .coerceAtLeast(1)
        window.measure(exactWidth, MeasureSpec.makeMeasureSpec(remaining, MeasureSpec.AT_MOST))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var top = 0
        for (child in listOf(heading, window, controls, feedback)) {
            child.layout(0, top, measuredWidth, top + child.measuredHeight)
            top += child.measuredHeight + gap
        }
    }
}
