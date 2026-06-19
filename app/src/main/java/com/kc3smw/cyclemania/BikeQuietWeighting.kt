package com.kc3smw.cyclemania

import com.graphhopper.routing.ev.BooleanEncodedValue
import com.graphhopper.routing.ev.DecimalEncodedValue
import com.graphhopper.routing.ev.EnumEncodedValue
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.weighting.FastestWeighting
import com.graphhopper.routing.weighting.TurnCostProvider
import com.graphhopper.util.EdgeIteratorState

// Penalises major roads so the router prefers residential/tertiary streets.
// Extends FastestWeighting so speed + the bike vehicle's built-in priority
// are both respected — we just amplify the road-class signal on top.
// This replaces the old CustomModel approach which relied on Janino runtime
// bytecode compilation (incompatible with Android ART).
class BikeQuietWeighting(
    accessEnc: BooleanEncodedValue,
    speedEnc: DecimalEncodedValue,
    private val roadClassEnc: EnumEncodedValue<RoadClass>
) : FastestWeighting(accessEnc, speedEnc, TurnCostProvider.NO_TURN_COST_PROVIDER) {

    override fun calcEdgeWeight(edgeState: EdgeIteratorState, reverse: Boolean): Double {
        val base = super.calcEdgeWeight(edgeState, reverse)
        if (base.isInfinite()) return base
        val penalty = when (edgeState.get(roadClassEnc)) {
            RoadClass.MOTORWAY, RoadClass.TRUNK -> 20.0
            RoadClass.PRIMARY                   -> 15.0
            RoadClass.SECONDARY                 ->  3.0
            RoadClass.TERTIARY                  ->  1.3
            else                                ->  1.0
        }
        return base * penalty
    }

    override fun getName(): String = "bike_quiet"
}
