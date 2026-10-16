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

    // EZ-60 probe report 2026-10-14: a 23-minute drive rolled over twice, and only its last minutes were reported.
    @Test fun theSessionLogSpansTheFilesItRolledInto() {
        val directory = java.nio.file.Files.createTempDirectory("diplay-logs").toFile()
        try {
            java.io.File(directory, "previous-3.log").writeText("DiPlay log started yesterday\n18:00:00.000  old session\n")
            java.io.File(directory, "previous-2.log").writeText("DiPlay log started 07:04\n07:04:23.677  Host started\n")
            java.io.File(directory, "previous.log").writeText("07:15:00.000  Video: video stats rx=31fps\n")
            java.io.File(directory, "diplay.log").writeText("07:26:00.000  HUD: route state=1\n")
            assertEquals(listOf("DiPlay log started 07:04", "07:04:23.677  Host started",
                "07:15:00.000  Video: video stats rx=31fps", "07:26:00.000  HUD: route state=1"),
                MazdaCockpitProbe.sessionLines(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun probeKeepsDisplayAndBuildPropertiesButNotIdentifiers() {
        assertTrue(MazdaCockpitProbe.interestingProperty("ro.product.model"))
        assertTrue(MazdaCockpitProbe.interestingProperty("ro.sf.lcd_density"))
        assertTrue(MazdaCockpitProbe.interestingProperty("persist.vendor.camzd.dual_screen"))
        assertFalse(MazdaCockpitProbe.interestingProperty("ro.serialno"))
        assertFalse(MazdaCockpitProbe.interestingProperty("ro.boot.wifimacaddr"))
        assertFalse(MazdaCockpitProbe.interestingProperty("persist.sys.timezone"))
    }

    // The voice assistant's session id carries the car's VIN; reports go to other people. (A made-up VIN.)
    @Test fun aVinIsMaskedButBuildAndDecoderNamesStay() {
        assertEquals("[settings] global.speech_id=TSpeechID_vin[vin]_1791801487547",
            DiagnosticRedactor.mask("[settings] global.speech_id=TSpeechID_vinLVRHDTEST0N123456_1791801487547"))
        assertEquals("vin=[vin]", DiagnosticRedactor.redact("vin=LVRHDTEST0N123456"))
        for (kept in listOf("ro.build.version.incremental=20250930150736", "decoder=c2.mtk.avc.decoder.lowlatency",
            "fingerprint=TINNOVE/vext_wt_car/auto8676p1_64_bsp:14/UP1A.231005.007", "C281_MIDDLE_EAST_AND_NORTH_AFRICA",
            "12345678901234567")) {
            assertEquals(kept, DiagnosticRedactor.mask(kept))
        }
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
