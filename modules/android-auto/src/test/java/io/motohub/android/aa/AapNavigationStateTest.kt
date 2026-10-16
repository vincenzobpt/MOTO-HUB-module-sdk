// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa

import io.motohub.android.aa.proto.NavigationStatus.NavigationLane
import io.motohub.android.aa.proto.NavigationStatus.NavigationLane.LaneDirection
import io.motohub.android.aa.proto.NavigationStatus.NavigationManeuver
import io.motohub.android.aa.proto.NavigationStatus.NavigationManeuver.NavigationType
import io.motohub.android.aa.proto.NavigationStatus.NavigationRoad
import io.motohub.android.aa.proto.NavigationStatus.NavigationState
import io.motohub.android.aa.proto.NavigationStatus.NavigationStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The modern NavigationState folded into a snapshot: next turn, the one after it, the lanes. */
class AapNavigationStateTest {

    private fun step(type: NavigationType, road: String = "", lanes: List<NavigationLane> = emptyList()) =
        NavigationStep.newBuilder()
            .setManeuver(NavigationManeuver.newBuilder().setType(type).buildPartial())
            .apply { if (road.isNotEmpty()) setRoad(NavigationRoad.newBuilder().setName(road).buildPartial()) }
            .apply { lanes.forEach { addLanes(it) } }
            .buildPartial()

    private fun lane(vararg directions: Pair<LaneDirection.Shape, Boolean>) =
        NavigationLane.newBuilder().apply {
            directions.forEach { (shape, highlighted) ->
                addLaneDirections(LaneDirection.newBuilder().setShape(shape).setIsHighlighted(highlighted).buildPartial())
            }
        }.buildPartial()

    @Test
    fun secondStepBecomesTheTurnAfterAndLanesKeepTheirOrder() {
        val state = NavigationState.newBuilder()
            .addSteps(
                step(
                    NavigationType.TURN_NORMAL_RIGHT, "Via Roma",
                    listOf(
                        lane(LaneDirection.Shape.STRAIGHT to false),
                        lane(LaneDirection.Shape.STRAIGHT to false, LaneDirection.Shape.NORMAL_RIGHT to true)
                    )
                )
            )
            .addSteps(step(NavigationType.TURN_SLIGHT_LEFT))
            .buildPartial()

        val folded = foldNavigationState(AaNavigationGuidance.Snapshot.INACTIVE, state)!!

        assertEquals(NavigationType.TURN_NORMAL_RIGHT.number, folded.maneuverType)
        assertEquals("Via Roma", folded.road)
        assertEquals(NavigationType.TURN_SLIGHT_LEFT.number, folded.thenManeuverType)
        assertEquals(
            listOf(
                AaNavigationGuidance.Lane(NavigationType.STRAIGHT.number, recommended = false),
                // The highlighted arrow is the one to draw, not the lane's first.
                AaNavigationGuidance.Lane(NavigationType.TURN_NORMAL_RIGHT.number, recommended = true)
            ),
            folded.lanes
        )
    }

    @Test
    fun aSingleStepClearsAStaleTurnAfterAndLanes() {
        val before = AaNavigationGuidance.Snapshot(
            active = true,
            thenManeuverType = NavigationType.TURN_NORMAL_LEFT.number,
            lanes = listOf(AaNavigationGuidance.Lane(NavigationType.STRAIGHT.number, true))
        )
        val state = NavigationState.newBuilder().addSteps(step(NavigationType.DESTINATION)).buildPartial()

        val folded = foldNavigationState(before, state)!!

        assertEquals(-1, folded.thenManeuverType)
        assertEquals(emptyList<AaNavigationGuidance.Lane>(), folded.lanes)
    }

    @Test
    fun noStepsLeavesTheSnapshotAlone() {
        assertNull(foldNavigationState(AaNavigationGuidance.Snapshot.INACTIVE, NavigationState.getDefaultInstance()))
    }

    @Test
    fun unknownLaneShapeIsUnknownManeuver() {
        assertEquals(-1, laneShapeToManeuverType(LaneDirection.Shape.UNKNOWN))
        assertEquals(-1, laneShapeToManeuverType(null))
    }
}
