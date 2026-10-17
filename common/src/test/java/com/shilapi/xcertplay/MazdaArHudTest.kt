package com.shilapi.xcertplay

import com.shilapi.xcertplay.MazdaArHudLayout.Readings
import com.shilapi.xcertplay.MazdaArHudLayout.Side
import com.shilapi.xcertplay.MazdaArHudLink.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AR-HUD map follows the car's own navigation (ARHudManager, DashboardVideoHelper). */
class MazdaArHudTest {
    @Test fun theMapGoesWhereTheCarsNavigationPutsIt() {
        assertEquals(Side.RIGHT, MazdaArHudLayout.side(Readings(gear = 1, hudMode = 1, mapLayout = 1)))
        assertEquals(Side.RIGHT, MazdaArHudLayout.side(Readings(gear = 1, hudMode = 3, mapLayout = 0)))
        assertEquals(Side.LEFT, MazdaArHudLayout.side(Readings(gear = 1, hudMode = 3, mapLayout = 1)))
        assertEquals(Side.RIGHT, MazdaArHudLayout.side(Readings(gear = 1, hudMode = 3, mapLayout = null)))
        assertEquals(1640, Side.RIGHT.left)
        assertEquals(70, Side.LEFT.left)
    }

    @Test fun noMapInParkWithTheHudOffInEasyModeOrWithoutAReading() {
        assertNull(MazdaArHudLayout.side(Readings(gear = MazdaArHudLayout.GEAR_PARK, hudMode = 1, mapLayout = 0)))
        assertNull(MazdaArHudLayout.side(Readings(gear = null, hudMode = 1, mapLayout = 0)))
        for (mode in listOf(0, 2, 4, null)) assertNull("$mode", MazdaArHudLayout.side(Readings(1, mode, 0)))
    }

    @Test fun theCarSwitchesTheLinkThenTheWindowShowsAndIsConfirmed() {
        val car = FakeCar()
        car.link.show()
        assertEquals(listOf("register", "status 5"), car.calls)
        car.link.onRequestLvds(MazdaArHudLink.READY)
        assertEquals(State.SHOWN, car.link.state)
        assertEquals(listOf("register", "status 5", "show", "finish"), car.calls)
        car.link.hide()
        assertEquals(State.HIDING, car.link.state)
        car.link.onIpStatus(MazdaArHudLink.IP_HIDDEN)
        assertEquals(State.IDLE, car.link.state)
        assertEquals(listOf("status 6", "hide", "finish"), car.calls.takeLast(3))
    }

    @Test fun aDeclinedRequestIsRetriedEightTimesThenTheMapStaysOff() {
        val car = FakeCar()
        car.link.show()
        repeat(MazdaArHudLink.MAX_ATTEMPTS) {
            car.link.onRequestLvds(MazdaArHudLink.RETRY)
            car.advance(MazdaArHudLink.RETRY_DELAY_MILLIS)
        }
        assertEquals(State.FAILED, car.link.state)
        assertEquals(MazdaArHudLink.MAX_ATTEMPTS, car.calls.count { it == "status 5" })
        assertTrue("A declined link was never switched on", "status 6" !in car.calls)
        car.link.show()
        assertEquals(State.FAILED, car.link.state)
    }

    @Test fun withoutAReplyTheRequestIsUndoneAndTheMapStaysOff() {
        val car = FakeCar()
        car.link.show()
        car.advance(MazdaArHudLink.REPLY_TIMEOUT_MILLIS)
        assertEquals(State.FAILED, car.link.state)
        assertEquals("status 6", car.calls.last { it.startsWith("status") })
    }

    @Test fun anotherAppsLinkChangeMakesDiPlayStepAsideWithoutHidingIt() {
        val car = FakeCar()
        car.link.show()
        car.link.onRequestLvds(MazdaArHudLink.READY)
        // The car's navigation retries alongside DiPlay: a late duplicate reply is still DiPlay's.
        car.advance(500)
        car.link.onRequestLvds(MazdaArHudLink.READY)
        assertEquals(State.SHOWN, car.link.state)
        car.advance(MazdaArHudLink.REPLY_GRACE_MILLIS)
        car.link.onRequestLvds(MazdaArHudLink.READY)
        assertEquals(State.STOOD_DOWN, car.link.state)
        assertEquals("hide", car.calls.last())
        assertTrue("never hides another app's link", "status 6" !in car.calls)
        car.link.show()
        assertEquals(State.STOOD_DOWN, car.link.state)
        car.link.routeEnded()
        assertEquals(State.IDLE, car.link.state)
        assertTrue("status 6" !in car.calls)
    }

    @Test fun anotherAppHidingTheLinkRemovesTheWindow() {
        val car = FakeCar()
        car.link.show()
        car.link.onRequestLvds(MazdaArHudLink.READY)
        car.advance(MazdaArHudLink.REPLY_GRACE_MILLIS + 1)
        car.link.onIpStatus(MazdaArHudLink.IP_HIDDEN)
        assertEquals(State.STOOD_DOWN, car.link.state)
        assertEquals("hide", car.calls.last())
    }

    @Test fun closingGivesTheLinkBackAndStopsListening() {
        val car = FakeCar()
        car.link.show()
        car.link.onRequestLvds(MazdaArHudLink.READY)
        car.link.close()
        assertEquals(listOf("status 6", "hide", "unregister"), car.calls.takeLast(3))
        assertEquals(State.FAILED, car.link.state)
    }

    private class FakeCar {
        val calls = mutableListOf<String>()
        private var clock = 0L
        private val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val link = MazdaArHudLink(
            calls = object : MazdaArHudLink.Calls {
                override fun register() = true.also { calls += "register" }
                override fun unregister() { calls += "unregister" }
                override fun setVideoStatus(status: Int) = true.also { calls += "status $status" }
                override fun finish() = true.also { calls += "finish" }
            },
            ui = object : MazdaArHudLink.Ui {
                override fun show() { calls += "show" }
                override fun hide() { calls += "hide" }
            },
            log = {},
            now = { clock },
            schedule = { delay, action -> timers += clock + delay to action },
        )

        fun advance(millis: Long) {
            clock += millis
            while (true) {
                val due = timers.filter { it.first <= clock }.minByOrNull { it.first } ?: break
                timers -= due
                due.second()
            }
        }
    }
}
