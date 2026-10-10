package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MazdaProbeTest {
    @Test fun platformMatchesChanganMazdaBuildValues() {
        val detection = MazdaPlatform.detect(listOf("alps", null, "CAMZD_J90K_IVI"), oemKeysPresent = false)
        assertEquals("CAMZD", detection.buildMatch)
        assertTrue(detection.likely)
    }

    @Test fun platformFallsBackToTheOemScreenKeys() {
        val detection = MazdaPlatform.detect(listOf("BYD", "DiLink3.0"), oemKeysPresent = true)
        assertNull(detection.buildMatch)
        assertTrue(detection.likely)
        assertFalse(MazdaPlatform.detect(listOf("BYD", "DiLink3.0"), oemKeysPresent = false).likely)
    }

    @Test fun probeKeepsDisplayAndBuildPropertiesButNotIdentifiers() {
        assertTrue(MazdaCockpitProbe.interestingProperty("ro.product.model"))
        assertTrue(MazdaCockpitProbe.interestingProperty("ro.sf.lcd_density"))
        assertTrue(MazdaCockpitProbe.interestingProperty("persist.vendor.camzd.dual_screen"))
        assertFalse(MazdaCockpitProbe.interestingProperty("ro.serialno"))
        assertFalse(MazdaCockpitProbe.interestingProperty("ro.boot.wifimacaddr"))
        assertFalse(MazdaCockpitProbe.interestingProperty("persist.sys.timezone"))
    }

    @Test fun maskKeepsTheLineButHidesAddresses() {
        val line = "usb device name=/dev/bus/usb/001/002 mac=02:11:22:33:44:55 ip=192.168.43.1"
        assertEquals("usb device name=/dev/bus/usb/001/002 mac=[address] ip=[ip]", DiagnosticRedactor.mask(line))
    }

    @Test fun shareListensOnlyOnWifiInterfaces() {
        for (name in listOf("wlan0", "wlan1", "ap0", "swlan0", "p2p-wlan0-0")) assertTrue(name, MazdaProbeShare.isWifiInterface(name))
        for (name in listOf("eth0", "wt_eth2", "wt_eth2_10", "rmnet0", "ccmni0", "dummy0", "lo")) {
            assertFalse(name, MazdaProbeShare.isWifiInterface(name))
        }
    }

    @Test fun shareAnswersOnlyItsRandomPath() {
        val share = MazdaProbeShare { "report <b>&" }
        val page = share.response("GET ${share.path} HTTP/1.1")
        assertEquals("200 OK", page.first)
        assertTrue(page.third.contains("report &lt;b&gt;&amp;"))
        assertEquals("report <b>&", share.response("GET ${share.path}.txt HTTP/1.1").third)
        assertEquals("404 Not Found", share.response("GET / HTTP/1.1").first)
        assertEquals("404 Not Found", share.response("GET /${"0".repeat(18)} HTTP/1.1").first)
        assertEquals("405 Method Not Allowed", share.response("POST ${share.path} HTTP/1.1").first)
        share.close()
    }
}
