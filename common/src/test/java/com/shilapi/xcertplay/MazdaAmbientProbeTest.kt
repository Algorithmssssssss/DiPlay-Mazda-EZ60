package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class MazdaAmbientProbeTest {
    @Test fun theProbeCanOnlyRead() {
        // IVirtualCar.getVirtualCarService, IVirturalCarProperty.getValue and isSupport; never setValue (2 on the
        // property service) or the listener calls.
        assertEquals(setOf(2, 3, 4), MazdaAmbientProbe.READ_CALLS)
    }

    @Test fun itLooksAtTheMediaCentersAmbientLightProperties() {
        assertEquals(listOf(826278810, 826278811, 826278822, 826278823, 826278772, 829424549),
            MazdaAmbientProbe.PROPERTIES.keys.toList())
    }

    @Test fun valuesAreShownReadably() {
        assertEquals("[-1,-1,-1]", MazdaAmbientProbe.describe(byteArrayOf(-1, -1, -1)))
        assertEquals("35", MazdaAmbientProbe.describe(35))
        assertEquals("null", MazdaAmbientProbe.describe(null))
    }
}
