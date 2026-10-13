package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The EZ-60 HUD must clear when the driver ends the route or arrives, but not on a route that reloads. */
class HeadUpGuidanceFeedTest {
    private var now = 0L
    private val events = mutableListOf<ClusterTurnGuidance?>()

    @Test fun endingTheRouteClearsTheHeadUpDisplayOnceNoRouteFollows() {
        val feed = guiding()
        feed.accept(route(tlv(0x01, 0)))
        now = 4_900_000_000L
        feed.refresh()
        assertEquals(150, events.last()!!.distanceMeters)
        now = 5_000_000_000L
        feed.refresh()
        assertNull(events.last())
    }

    // EZ-60 report 23: Google Maps resuming a route mid-drive sends NoRouteSet, Loading and RouteSet in bursts up
    // to 2 s apart, and its RouteSet carries no current-maneuver list.
    @Test fun aRouteReloadingMidDriveKeepsTheHeadUpGuidance() {
        val feed = guiding()
        for (state in listOf(0, 3, 0, 3, 1, 0, 3, 1)) {
            now += 1_800_000_000L
            feed.accept(route(tlv(0x01, state)))
            feed.refresh()
        }
        assertTrue(events.none { it == null })
        assertEquals(150, feed.current()!!.distanceMeters)
    }

    @Test fun theDashboardCardKeepsItsInstructionAcrossNoRoute() {
        val feed = TurnGuidanceFeed(BydHudRouteState(nanoTime = { now }, staleRouteNs = 120_000_000_000L,
            emptyListHideNs = 8_000_000_000L, keepAcrossNoRoute = true))
        start(feed)
        feed.accept(route(tlv(0x01, 0)))
        assertEquals(150, events.last()!!.distanceMeters)
        assertEquals(1, events.size)
    }

    @Test fun arrivingClearsTheHeadUpDisplay() {
        val feed = guiding()
        feed.accept(route(tlv(0x01, 2)))
        assertNull(events.last())
    }

    @Test fun guidanceThatStopsArrivingClearsAfterThirtySeconds() {
        val feed = guiding()
        now = 29_000_000_000L
        feed.refresh()
        assertEquals(1, events.size)
        now = 30_000_000_000L
        feed.refresh()
        assertNull(events.last())
    }

    @Test fun aNewRouteShowsAgainAfterTheEnd() {
        val feed = guiding()
        feed.accept(route(tlv(0x01, 0)))
        now = 6_000_000_000L
        feed.refresh()
        assertNull(events.last())
        feed.accept(Iap2Frame(BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE, tlv(0x01, 0, 7) + tlv(0x03, 2)))
        feed.accept(route(tlv(0x01, 1) + tlv(0x0a, 0, 0, 0, 90) + tlv(0x0d, 0, 7)))
        assertEquals(90, events.last()!!.distanceMeters)
    }

    @Test fun aRouteAfterTheEndDoesNotShowTheOldTurn() {
        val feed = guiding()
        feed.accept(route(tlv(0x01, 0)))
        now = 6_000_000_000L
        feed.refresh()
        feed.accept(route(tlv(0x01, 1) + tlv(0x0a, 0, 0, 0, 90)))
        assertNull(feed.current())
    }

    @Test fun aRouteStartedOnTheIphoneShowsAfterANoRouteMessage() {
        // The iPhone sends a route's maneuvers once; a NoRouteSet must not lose them for the guidance that follows.
        val feed = guiding()
        feed.accept(route(tlv(0x01, 0)))
        now = 6_000_000_000L
        feed.refresh()
        assertNull(events.last())
        feed.accept(route(tlv(0x01, 1) + tlv(0x0a, 0, 0, 0, 120) + tlv(0x0d, 0, 7)))
        assertEquals(120, events.last()!!.distanceMeters)
    }

    @Test fun eachRouteStateChangeIsLoggedOnce() {
        val notes = mutableListOf<String>()
        val feed = guiding()
        feed.stateLog = { notes += it }
        feed.accept(route(tlv(0x01, 1) + tlv(0x0a, 0, 0, 0, 140)))
        feed.accept(route(tlv(0x01, 0)))
        feed.accept(route(tlv(0x01, 0)))
        assertEquals(listOf("HUD: route state=0 maneuvers=1 guiding=true"), notes)
    }

    private fun guiding(): TurnGuidanceFeed =
        TurnGuidanceFeed(BydNavigationOutputs.headUpRoute { now }).also(::start)

    private fun start(feed: TurnGuidanceFeed) {
        feed.setListener { events += it }
        events.clear()
        feed.accept(Iap2Frame(BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE, tlv(0x01, 0, 7) + tlv(0x03, 1)))
        feed.accept(route(tlv(0x01, 1) + tlv(0x0a, 0, 0, 0, 150) + tlv(0x0d, 0, 7)))
        assertEquals(150, events.last()!!.distanceMeters)
        events.clear()
        events += feed.current()
    }

    private fun route(payload: ByteArray) = Iap2Frame(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, payload)

    private fun tlv(type: Int, vararg bytes: Int): ByteArray =
        byteArrayOf(0, (bytes.size + 4).toByte(), (type ushr 8).toByte(), type.toByte()) +
            bytes.map(Int::toByte).toByteArray()
}
