package com.shilapi.xcertplay.network

import java.net.InetAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

/** The EZ-60's in-vehicle Ethernet never stands in for its car hotspot (car-output9: wt_eth2_4…10 hold IPv4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ManualHotspotEz60Test {
    private fun iface(name: String, index: Int, vararg addresses: String) = HotspotInterfaceSnapshot(
        name, index, true, addresses.map(InetAddress::getByName), wirelessInterfaceName(name))

    private val eth0 = iface("eth0", 6, "172.16.2.20")
    private val wtEth24 = iface("wt_eth2_4", 230, "172.16.4.20")
    private val wtEth28 = iface("wt_eth2_8", 233, "172.16.8.20")
    private val ap0 = iface("ap0", 267, "192.168.43.1")

    @After fun resetModel() = ShadowBuild.reset()

    @Test fun anEmptyTetherSetOnTheEz60FindsNoWiredHotspot() {
        ShadowBuild.setModel("J90K")
        val ap = apInterfaces(emptySet(), listOf(eth0, wtEth24, wtEth28))
        assertEquals(emptySet<String>(), ap)
        assertNull(select(ap, eth0, wtEth24, wtEth28))
    }

    @Test fun theEz60sNamedHotspotIsKept() {
        ShadowBuild.setModel("J90K")
        val ap = apInterfaces(setOf("ap0"), listOf(eth0, wtEth24, ap0))
        assertEquals(setOf("ap0"), ap)
        assertEquals("ap0", select(ap, eth0, wtEth24, ap0))
    }

    @Test fun otherHeadUnitsStillSearchTheirWiredInterfaces() {
        ShadowBuild.setModel("CS11")
        val vlan = iface("eth0.3", 12, "198.18.34.15")
        assertEquals(setOf("eth0.3"), apInterfaces(emptySet(), listOf(vlan)))
    }

    private fun select(ap: Set<String>?, vararg interfaces: HotspotInterfaceSnapshot) = selectHotspotInterface(
        HotspotNetworkSnapshot(interfaces.toList(), ap, emptySet(), "wlan0", consistent = true, apEnabled = true)) {}?.name

    @Suppress("UNCHECKED_CAST")
    private fun apInterfaces(observed: Set<String>?, interfaces: List<HotspotInterfaceSnapshot>): Set<String>? =
        ManualHotspotInterfaces::class.java.getDeclaredMethod("apInterfaces", Set::class.java, List::class.java,
            java.lang.Boolean::class.java, Set::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(ManualHotspotInterfaces(RuntimeEnvironment.getApplication()), observed, interfaces, true,
                emptySet<String>(), "wlan0") as Set<String>?
}
