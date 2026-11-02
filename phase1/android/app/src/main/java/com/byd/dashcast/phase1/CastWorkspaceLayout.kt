package com.byd.dashcast.phase1

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Cast page: the window gets whichever arrangement shows it largest at its current source aspect.
 * Stacked keeps title, window, controls and feedback top to bottom (best for the wide full-mode source);
 * side puts title, controls and feedback in a column right of the window (best for the 16:9 small mode,
 * which stacked chrome would limit by height and leave blank on both sides). Controls are measured
 * before the window, so short screens and the IME never push stop off screen.
 */
internal class CastWorkspaceLayout(context: Context, private val heading: View,
    private val window: EmbeddedCastView, private val controls: CastControls, private val feedback: View,
    private val allowSide: Boolean) : ViewGroup(context) {
    private val density = resources.displayMetrics.density
    private val gap = (12 * density).toInt()
    private var side = false
    private var panelWidth = 0

    init { addView(heading); addView(window); addView(controls); addView(feedback) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val aspect = window.sourceAspect
        val stackedHeight = (height - measurePanel(width, height, false) - gap * 3).coerceAtLeast(1)
        val stackedArea = CastLayoutMath.fittedArea(aspect, width, stackedHeight)
        var sideArea = 0f
        val panel = CastLayoutMath.panelWidth(width, density)
        if (allowSide && panel > 0) {
            // Title and controls must fit; the feedback line only takes the height that is left.
            measurePanel(panel, height, true)
            if (heading.measuredHeight + controls.measuredHeight + gap <= height) {
                sideArea = CastLayoutMath.fittedArea(aspect, width - panel - gap, height)
            }
        }
        side = CastLayoutMath.preferSide(stackedArea, sideArea)
        if (side) {
            panelWidth = panel
            window.measure(MeasureSpec.makeMeasureSpec(width - panel - gap, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        } else {
            panelWidth = width
            measurePanel(width, height, false)
            window.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(stackedHeight, MeasureSpec.AT_MOST))
        }
        setMeasuredDimension(width, height)
    }

    /** Measures title, controls and feedback at [width]; returns their summed height. */
    private fun measurePanel(width: Int, height: Int, column: Boolean): Int {
        val exact = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val limit = MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
        controls.side = column
        heading.measure(exact, limit); controls.measure(exact, limit)
        val used = heading.measuredHeight + controls.measuredHeight
        val rest = if (column) (height - used - gap * 2).coerceAtLeast(0) else height
        feedback.measure(exact, MeasureSpec.makeMeasureSpec(rest, MeasureSpec.AT_MOST))
        return used + feedback.measuredHeight
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (side) {
            val top = (measuredHeight - window.measuredHeight) / 2
            window.layout(0, top, window.measuredWidth, top + window.measuredHeight)
            var y = 0
            val x = measuredWidth - panelWidth
            for (child in listOf(heading, controls, feedback)) {
                child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                y += child.measuredHeight + gap
            }
        } else {
            var y = 0
            for (child in listOf(heading, window, controls, feedback)) {
                child.layout(0, y, measuredWidth, y + child.measuredHeight)
                y += child.measuredHeight + gap
            }
        }
    }
}

/** Pure arithmetic for the cast-page arrangement, kept separate so desktop tests can check it. */
internal object CastLayoutMath {
    /** Area of the largest [aspect] rectangle inside [width] x [height]. */
    fun fittedArea(aspect: Float, width: Int, height: Int): Float {
        if (width <= 0 || height <= 0 || aspect <= 0f) { return 0f }
        return if (width.toFloat() / height > aspect) height * aspect * height else width * (width / aspect)
    }

    /** Side column width, or 0 when the page is too narrow to give one up. */
    fun panelWidth(width: Int, density: Float): Int {
        val min = (260 * density).toInt(); val max = (380 * density).toInt()
        val wanted = (width * 0.28f).toInt().coerceIn(min, max)
        return if (width - wanted < min * 2) 0 else wanted
    }

    /** Side only when it is clearly larger, so near-equal cases keep the familiar stacked page. */
    fun preferSide(stackedArea: Float, sideArea: Float): Boolean = sideArea > stackedArea * 1.1f
}

/** Source name left and status right on one line, or the status on a second line when the side
 * column is too narrow for both; the old half-and-half row cut a long app name short. */
internal class CastHeading(context: Context, private val label: View, private val status: View) : ViewGroup(context) {
    private val gap = (12 * resources.displayMetrics.density).toInt()
    private var stacked = false

    init { addView(label); addView(status) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val wrap = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        label.measure(wrap, free); status.measure(wrap, free)
        stacked = label.measuredWidth + gap + status.measuredWidth > width
        val height = if (stacked) label.measuredHeight + status.measuredHeight
            else maxOf(label.measuredHeight, status.measuredHeight)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (stacked) {
            label.layout(0, 0, label.measuredWidth, label.measuredHeight)
            status.layout(0, label.measuredHeight, status.measuredWidth, label.measuredHeight + status.measuredHeight)
        } else {
            val row = measuredHeight
            label.layout(0, (row - label.measuredHeight) / 2, label.measuredWidth, (row + label.measuredHeight) / 2)
            val x = measuredWidth - status.measuredWidth
            status.layout(x, (row - status.measuredHeight) / 2, measuredWidth, (row + status.measuredHeight) / 2)
        }
    }
}

/** The cast-page actions as one strip under the window, or one column beside it. */
internal class CastControls(context: Context, private val buttons: List<View>, private val role: View,
    private val note: View, private val modes: List<View>) : ViewGroup(context) {
    /** Set by the parent before each measure; a plain field so it never requests another layout. */
    var side = false
    private val gap = (12 * resources.displayMetrics.density).toInt()
    private val minUnit = (64 * resources.displayMetrics.density).toInt()
    private val rows = ArrayList<List<View>>()

    init { (buttons + role + note + modes).forEach { addView(it) } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val shown = buttons.filter { it.visibility != GONE }
        rows.clear()
        if (side) {
            // Two buttons share a row; with three the main action keeps a row of its own.
            if (shown.size <= 2) { rows += shown } else { rows += listOf(shown.first()); rows += shown.drop(1) }
            rows += listOf(role); rows += listOf(note) + modes
        } else {
            val wrap = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)
            val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            role.measure(wrap, free); note.measure(wrap, free)
            val flexible = shown.size + modes.size
            val unit = (width - role.measuredWidth - note.measuredWidth - gap * (flexible + 1)) / flexible
            if (unit >= minUnit) { rows += shown + role + note + modes }
            else { rows += shown; rows += listOf(role) + note + modes }
        }
        var height = 0
        for (row in rows) { height += measureRow(row, width) + if (height > 0) gap else 0 }
        setMeasuredDimension(width, height)
    }

    /** Role switch and note keep their natural width in a shared row; buttons split what is left. */
    private fun measureRow(row: List<View>, width: Int): Int {
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val natural = if (row.size == 1) emptyList() else row.filter { it == role || it == note }
        natural.forEach { it.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), free) }
        val flexible = row - natural.toSet()
        val share = if (flexible.isEmpty()) 0 else
            ((width - natural.sumOf { it.measuredWidth } - gap * (row.size - 1)) / flexible.size).coerceAtLeast(1)
        flexible.forEach { it.measure(MeasureSpec.makeMeasureSpec(if (row.size == 1) width else share,
            MeasureSpec.EXACTLY), free) }
        return row.maxOf { it.measuredHeight }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = 0
        for (row in rows) {
            val rowHeight = row.maxOf { it.measuredHeight }
            var x = 0
            for (child in row) {
                val top = y + (rowHeight - child.measuredHeight) / 2
                child.layout(x, top, x + child.measuredWidth, top + child.measuredHeight)
                x += child.measuredWidth + gap
            }
            y += rowHeight + gap
        }
    }
}
