package com.kc3smw.cyclemania

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Canvas-drawn elevation profile for the Ride Summary screen. Colors each
 * short run of the ride green (flat), red (uphill) or blue (downhill) using
 * the same grade buckets as the route line on the map, so the two visuals
 * read as one consistent language.
 */
class ElevationChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var profile: ElevationProfile.Profile? = null
    private var useKm: Boolean = true

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private val flatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4CAF50")
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val upPaint = Paint(flatPaint).apply { color = Color.parseColor("#FFEF5350") }
    private val downPaint = Paint(flatPaint).apply { color = Color.parseColor("#FF42A5F5") }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#264CAF50")
        style = Paint.Style.FILL
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(12f)
    }

    fun setProfile(profile: ElevationProfile.Profile?, useKm: Boolean) {
        this.profile = profile
        this.useKm = useKm
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = profile ?: return
        val altitudes = p.smoothedAltitudes
        val distances = p.cumulativeDistances
        if (altitudes.size < 2) return

        val paddingTop = dp(22f)
        val paddingBottom = dp(20f)
        val paddingSides = dp(8f)
        val chartWidth = width - paddingSides * 2
        val chartHeight = height - paddingTop - paddingBottom
        if (chartWidth <= 0 || chartHeight <= 0) return

        val minAlt = p.minAltitudeMeters
        val maxAlt = p.maxAltitudeMeters
        val altRange = (maxAlt - minAlt).coerceAtLeast(1.0)
        val totalDist = distances.last().coerceAtLeast(1.0)

        fun xFor(d: Double) = paddingSides + (d / totalDist * chartWidth).toFloat()
        fun yFor(alt: Double) = paddingTop + ((maxAlt - alt) / altRange * chartHeight).toFloat()

        // Subtle filled area under the whole profile for visual weight.
        val fillPath = Path()
        fillPath.moveTo(xFor(distances[0]), height - paddingBottom)
        for (i in altitudes.indices) {
            fillPath.lineTo(xFor(distances[i]), yFor(altitudes[i]))
        }
        fillPath.lineTo(xFor(distances.last()), height - paddingBottom)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        // Colored line per grade chunk (green/red/blue).
        for (chunk in p.chunks) {
            val paint = when (chunk.category) {
                ElevationProfile.GradeCategory.FLAT -> flatPaint
                ElevationProfile.GradeCategory.UPHILL -> upPaint
                ElevationProfile.GradeCategory.DOWNHILL -> downPaint
            }
            val path = Path()
            path.moveTo(xFor(distances[chunk.startIndex]), yFor(altitudes[chunk.startIndex]))
            for (i in (chunk.startIndex + 1)..chunk.endIndex) {
                path.lineTo(xFor(distances[i]), yFor(altitudes[i]))
            }
            canvas.drawPath(path, paint)
        }

        // Start/end markers with altitude labels, similar to typical ride-summary charts.
        fun formatAlt(m: Double) = if (useKm) String.format("%.0f m", m) else String.format("%.0f ft", m * 3.28084)

        val startX = xFor(distances[0])
        val startY = yFor(altitudes[0])
        val endX = xFor(distances.last())
        val endY = yFor(altitudes.last())
        canvas.drawCircle(startX, startY, dp(3f), markerPaint)
        canvas.drawCircle(endX, endY, dp(3f), markerPaint)

        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(formatAlt(altitudes[0]), startX, (startY - dp(8f)).coerceAtLeast(textPaint.textSize), textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(formatAlt(altitudes.last()), endX, (endY - dp(8f)).coerceAtLeast(textPaint.textSize), textPaint)
    }
}
