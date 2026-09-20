package com.kc3smw.cyclemania

import org.osmdroid.util.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Builds the grade-colored, dashed ride track for the Ride Summary map:
 * green where flat, dashed red uphill, dashed blue downhill (dash length
 * scales with grade steepness). Where the ride retraces the same road
 * (an out-and-back), the two passes are nudged sideways so both remain
 * visible as parallel lines instead of one drawing over the other.
 */
object RouteLineStyler {

    data class StyledSegment(
        val points: List<GeoPoint>,
        val category: ElevationProfile.GradeCategory,
        val dashLengthPx: Float // 0 = solid
    )

    private const val MIN_GRADE_FOR_DASH = ElevationProfile.FLAT_THRESHOLD_PERCENT
    private const val MAX_GRADE_FOR_DASH = 15.0
    private const val MIN_DASH_DP = 8f
    private const val MAX_DASH_DP = 30f

    private const val OVERLAP_RADIUS_METERS = 5.0
    private const val OVERLAP_MIN_INDEX_GAP = 30
    // A fixed real-world distance rather than a screen-pixel target: osmdroid
    // re-projects GeoPoint overlays live, so this automatically reads as more
    // screen pixels the closer the user zooms in (and fewer zoomed out) with
    // no extra work. A screen-pixel-target approach was tried and discarded -
    // converting a fixed pixel gap to meters at a whole-ride overview zoom
    // (many meters/pixel) demanded an offset of literal tens of meters, which
    // floated the line visibly off the real road shown on the base map tiles.
    // This value approximates a plausible "adjacent trail/shoulder" width;
    // it won't be obvious at a zoomed-out overview (nothing would be, at that
    // scale, on any map) but is clearly two lines once zoomed in on the spot.
    private const val OVERLAP_OFFSET_METERS = 8.0
    private const val OVERLAP_RAMP_POINTS = 4 // ease the offset in/out over this many points
    private const val GRID_CELL_DEGREES = 0.00004 // ~4.4m at the equator

    fun build(
        points: List<TrackPoint>,
        profile: ElevationProfile.Profile,
        overlapOffsetMeters: Double = OVERLAP_OFFSET_METERS
    ): List<StyledSegment> {
        if (points.size < 2) return emptyList()

        val overlapping = findOverlappingIndices(points)
        val displayPoints = buildDisplayPoints(points, overlapping, overlapOffsetMeters)

        return profile.chunks.map { chunk ->
            val segPoints = (chunk.startIndex..chunk.endIndex).map { displayPoints[it] }
            val dashLength = dashLengthFor(chunk.avgGradePercent)
            StyledSegment(segPoints, chunk.category, dashLength)
        }
    }

    private fun dashLengthFor(gradePercent: Double): Float {
        val magnitude = kotlin.math.abs(gradePercent)
        if (magnitude < MIN_GRADE_FOR_DASH) return 0f // flat: solid line
        val t = ((magnitude - MIN_GRADE_FOR_DASH) / (MAX_GRADE_FOR_DASH - MIN_GRADE_FOR_DASH))
            .coerceIn(0.0, 1.0)
        return (MIN_DASH_DP + t * (MAX_DASH_DP - MIN_DASH_DP)).toFloat()
    }

    /**
     * Flags points that lie very close to another point far away in the
     * ride's timeline (i.e. the route doubled back over itself), using a
     * coarse lat/lon grid so this stays roughly linear instead of comparing
     * every point against every other point.
     */
    private fun findOverlappingIndices(points: List<TrackPoint>): BooleanArray {
        val grid = HashMap<Long, MutableList<Int>>()
        fun cellKey(lat: Double, lon: Double): Long {
            val gx = (lat / GRID_CELL_DEGREES).toLong()
            val gy = (lon / GRID_CELL_DEGREES).toLong()
            return (gx shl 32) xor (gy and 0xFFFFFFFFL)
        }
        for ((i, p) in points.withIndex()) {
            grid.getOrPut(cellKey(p.latitude, p.longitude)) { mutableListOf() }.add(i)
        }

        val overlapping = BooleanArray(points.size)
        for (i in points.indices) {
            val p = points[i]
            val gx = (p.latitude / GRID_CELL_DEGREES).toLong()
            val gy = (p.longitude / GRID_CELL_DEGREES).toLong()
            outer@ for (dx in -1..1) {
                for (dy in -1..1) {
                    val key = ((gx + dx) shl 32) xor ((gy + dy) and 0xFFFFFFFFL)
                    val bucket = grid[key] ?: continue
                    for (j in bucket) {
                        if (kotlin.math.abs(i - j) < OVERLAP_MIN_INDEX_GAP) continue
                        val d = RideRecorder.haversineMeters(p.latitude, p.longitude, points[j].latitude, points[j].longitude)
                        if (d < OVERLAP_RADIUS_METERS) {
                            overlapping[i] = true
                            break@outer
                        }
                    }
                }
            }
        }
        return overlapping
    }

    /**
     * For points flagged as overlapping, offsets them to the right of their
     * local direction of travel, ramped smoothly in/out at the edges of each
     * overlapping run instead of snapping on/off (which reads as a kink).
     * Two opposite-direction passes over the same road each shift to their
     * own right, naturally separating onto opposite sides (like traffic on
     * a two-way street).
     */
    // Direction is measured between points this far apart (by distance travelled, not
    // index) rather than immediate neighbors - real GPS fixes a second apart are only
    // a few meters apart and jitter noticeably, which turns a naive prev/next bearing
    // into a direction that swings wildly point to point instead of tracking the road.
    private const val BEARING_BASELINE_METERS = 15.0

    private fun buildDisplayPoints(points: List<TrackPoint>, overlapping: BooleanArray, overlapOffsetMeters: Double): List<GeoPoint> {
        val n = points.size
        val strength = rampedOffsetStrength(overlapping)
        if (strength.none { it > 0.0 }) return points.map { GeoPoint(it.latitude, it.longitude) }

        val cumulative = DoubleArray(n)
        for (i in 1 until n) {
            cumulative[i] = cumulative[i - 1] + RideRecorder.haversineMeters(
                points[i - 1].latitude, points[i - 1].longitude, points[i].latitude, points[i].longitude
            )
        }
        // Smallest index whose cumulative distance is >= target (cumulative is non-decreasing).
        fun indexAtDistance(target: Double): Int {
            var lo = 0
            var hi = n - 1
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (cumulative[mid] < target) lo = mid + 1 else hi = mid
            }
            return lo
        }

        return List(n) { i ->
            val p = points[i]
            if (strength[i] <= 0.0) return@List GeoPoint(p.latitude, p.longitude)

            val prevIdx = indexAtDistance(cumulative[i] - BEARING_BASELINE_METERS).coerceIn(0, i)
            val nextIdx = indexAtDistance(cumulative[i] + BEARING_BASELINE_METERS).coerceIn(i, n - 1)
            val prev = points[prevIdx]
            val next = points[nextIdx]
            val bearing = bearingRadians(prev.latitude, prev.longitude, next.latitude, next.longitude)
            val perpendicular = bearing + Math.PI / 2.0 // 90 deg clockwise = "own right"
            offset(p.latitude, p.longitude, overlapOffsetMeters * strength[i], perpendicular)
        }
    }

    /**
     * Converts the on/off overlap flags into a 0..1 ramp per point: full
     * strength in the middle of a contiguous overlapping run, easing down to
     * 0 over [OVERLAP_RAMP_POINTS] points at each end of the run so the
     * offset fades in/out rather than snapping, which otherwise shows up as
     * a visible notch where the two passes suddenly jog apart.
     */
    private fun rampedOffsetStrength(overlapping: BooleanArray): DoubleArray {
        val n = overlapping.size
        val strength = DoubleArray(n)
        var i = 0
        while (i < n) {
            if (!overlapping[i]) {
                i++
                continue
            }
            var j = i
            while (j < n && overlapping[j]) j++
            // Run is [i, j). Ramp from both ends toward the middle.
            val ramp = min(OVERLAP_RAMP_POINTS, (j - i) / 2).coerceAtLeast(1)
            for (k in i until j) {
                val edgeDistance = min(k - i, j - 1 - k) + 1
                strength[k] = min(1.0, edgeDistance.toDouble() / ramp)
            }
            i = j
        }
        return strength
    }

    private fun bearingRadians(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return atan2(y, x)
    }

    private fun offset(lat: Double, lon: Double, distanceMeters: Double, bearingRadians: Double): GeoPoint {
        val earthRadius = 6_371_000.0
        val angularDistance = distanceMeters / earthRadius
        val phi1 = Math.toRadians(lat)
        val lambda1 = Math.toRadians(lon)

        val phi2 = kotlin.math.asin(
            sin(phi1) * cos(angularDistance) + cos(phi1) * sin(angularDistance) * cos(bearingRadians)
        )
        val lambda2 = lambda1 + atan2(
            sin(bearingRadians) * sin(angularDistance) * cos(phi1),
            cos(angularDistance) - sin(phi1) * sin(phi2)
        )
        return GeoPoint(Math.toDegrees(phi2), Math.toDegrees(lambda2))
    }
}
