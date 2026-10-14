package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MazdaHudPracticeRouteTest {
    private val legs = MazdaHudPracticeRoute.LEGS
    private val total = MazdaHudPracticeRoute.TOTAL_METERS
    private val speed = MazdaHudPracticeRoute.METERS_PER_SECOND

    @Test fun startsWithTheFirstTurnAheadAndTheWholeRouteLeft() {
        val start = MazdaHudPracticeRoute.guidanceAt(0)!!
        assertEquals(legs.first().icon, start.icon)
        assertEquals(legs.first().meters, start.distanceMeters)
        assertEquals(legs.first().road, start.road)
        assertEquals(total.toLong(), start.remainingMeters)
    }

    @Test fun countsDownToEachManeuverAndMovesOnAfterIt() {
        val firstEnd = legs.first().meters
        // 10 s in: 250 m driven, 200 m to the left turn.
        assertEquals(firstEnd - 10 * speed, MazdaHudPracticeRoute.guidanceAt(10)!!.distanceMeters)
        // Once past it, the next maneuver is ahead.
        val after = MazdaHudPracticeRoute.guidanceAt(firstEnd / speed + 1)!!
        assertEquals(legs[1].icon, after.icon)
        assertEquals(legs[1].road, after.road)
    }

    @Test fun playsEveryManeuverInOrder() {
        val seen = (0..total / speed).mapNotNull { MazdaHudPracticeRoute.guidanceAt(it)?.icon }.distinct()
        assertEquals(legs.map { it.icon }, seen)
    }

    @Test fun everyManeuverHasAnArrowOnTheCarsHud() {
        for (leg in legs) assertNotEquals("icon ${leg.icon}", 0, MazdaHud.turnId(leg.icon, leg.roundaboutExit))
    }

    @Test fun holdsTheArrivalThenEnds() {
        val arrived = total / speed
        val atDestination = MazdaHudPracticeRoute.guidanceAt(arrived)!!
        assertEquals(15, atDestination.icon)
        assertEquals(0, atDestination.distanceMeters)
        assertEquals(0L, atDestination.remainingMeters)
        assertEquals(15, MazdaHudPracticeRoute.guidanceAt(arrived + MazdaHudPracticeRoute.ARRIVED_SECONDS - 1)!!.icon)
        assertNull(MazdaHudPracticeRoute.guidanceAt(arrived + MazdaHudPracticeRoute.ARRIVED_SECONDS))
    }

    @Test fun distancesCountDownInStepsOfTenMeters() {
        for (second in 0..total / speed) {
            val guidance = MazdaHudPracticeRoute.guidanceAt(second) ?: continue
            assertEquals(0, guidance.distanceMeters % 10)
        }
    }
}
