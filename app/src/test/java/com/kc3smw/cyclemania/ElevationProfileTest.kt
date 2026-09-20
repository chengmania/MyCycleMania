package com.kc3smw.cyclemania

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationProfileTest {

    /** Builds a straight line of points heading due north, climbing at a steady grade. */
    private fun straightClimb(distanceMeters: Double, gradePercent: Double, stepMeters: Double = 5.0): List<TrackPoint> {
        val points = mutableListOf<TrackPoint>()
        val metersPerDegreeLat = 111_320.0
        var d = 0.0
        var t = 0L
        while (d <= distanceMeters) {
            val lat = d / metersPerDegreeLat
            val alt = d * gradePercent / 100.0
            points.add(TrackPoint(lat, 0.0, alt, t, 0f))
            d += stepMeters
            t += 1000
        }
        return points
    }

    @Test
    fun `steady climb is classified uphill with dashes scaling by grade`() {
        val points = straightClimb(distanceMeters = 400.0, gradePercent = 8.0)
        val profile = ElevationProfile.build(points)!!

        assertTrue("expected some ascent", profile.totalAscentMeters > 0.0)
        assertTrue("all chunks should read as uphill for a steady climb",
            profile.chunks.all { it.category == ElevationProfile.GradeCategory.UPHILL })

        val styled = RouteLineStyler.build(points, profile)
        assertTrue("uphill segments should be dashed, not solid", styled.all { it.dashLengthPx > 0f })
    }

    @Test
    fun `steady descent is classified downhill`() {
        val points = straightClimb(distanceMeters = 400.0, gradePercent = -6.0)
        val profile = ElevationProfile.build(points)!!

        assertTrue(profile.chunks.all { it.category == ElevationProfile.GradeCategory.DOWNHILL })
        val styled = RouteLineStyler.build(points, profile)
        assertTrue(styled.all { it.dashLengthPx > 0f })
    }

    @Test
    fun `gentle grade under threshold reads as flat and solid`() {
        val points = straightClimb(distanceMeters = 400.0, gradePercent = 0.5)
        val profile = ElevationProfile.build(points)!!

        assertTrue(profile.chunks.all { it.category == ElevationProfile.GradeCategory.FLAT })
        val styled = RouteLineStyler.build(points, profile)
        assertTrue(styled.all { it.dashLengthPx == 0f })
    }

    @Test
    fun `steeper grade produces longer dashes than a gentle grade`() {
        val gentle = straightClimb(distanceMeters = 400.0, gradePercent = 3.0)
        val steep = straightClimb(distanceMeters = 400.0, gradePercent = 12.0)

        val gentleDash = RouteLineStyler.build(gentle, ElevationProfile.build(gentle)!!).first().dashLengthPx
        val steepDash = RouteLineStyler.build(steep, ElevationProfile.build(steep)!!).first().dashLengthPx

        assertTrue("steeper grade should produce a longer dash", steepDash > gentleDash)
    }

    @Test
    fun `tiny near-zero distance does not blow up into a false steep reading`() {
        // A handful of points jittering within ~1m of each other (GPS noise while
        // stationary) with a small absolute altitude wobble should read as flat,
        // not spike into an extreme percentage from dividing by ~0 distance.
        val points = listOf(
            TrackPoint(0.0, 0.0, 137.0, 0L, 0f),
            TrackPoint(0.000001, 0.0, 137.2, 1000L, 0f),
            TrackPoint(0.000002, 0.0, 137.1, 2000L, 0f),
            TrackPoint(0.000001, 0.0, 137.4, 3000L, 0f)
        )
        val profile = ElevationProfile.build(points)!!
        assertTrue(profile.chunks.all { it.category == ElevationProfile.GradeCategory.FLAT })
    }

    @Test
    fun `out-and-back ride nudges the retraced points off the original line`() {
        val outbound = straightClimb(distanceMeters = 300.0, gradePercent = 4.0)
        // Ride back the same road: reverse order, continuing the timestamp sequence,
        // so the return trip's coordinates exactly retrace the outbound ones.
        val inbound = outbound.reversed().mapIndexed { i, p ->
            p.copy(timestamp = outbound.last().timestamp + 1000 + i * 1000L)
        }
        val roundTrip = outbound + inbound

        val profile = ElevationProfile.build(roundTrip)!!
        val styled = RouteLineStyler.build(roundTrip, profile)
        val displayedPoints = styled.flatMap { it.points }

        // Somewhere in the middle of the retraced road, the displayed (possibly
        // offset) coordinates should diverge from the raw recorded coordinates -
        // otherwise the two passes would still draw exactly on top of each other.
        val rawMidpoint = roundTrip[roundTrip.size / 2]
        val maxDivergenceNearby = displayedPoints
            .filter { RideRecorder.haversineMeters(it.latitude, it.longitude, rawMidpoint.latitude, rawMidpoint.longitude) < 20.0 }
            .maxOf { RideRecorder.haversineMeters(it.latitude, it.longitude, rawMidpoint.latitude, rawMidpoint.longitude) }

        assertTrue(
            "expected at least one nearby point to be nudged off the original line, max divergence was ${maxDivergenceNearby}m",
            maxDivergenceNearby > 1.0
        )
    }
}
