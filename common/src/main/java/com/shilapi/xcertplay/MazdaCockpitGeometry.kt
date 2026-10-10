package com.shilapi.xcertplay

/**
 * Where the Mazda EZ-60 cockpit shows an app inside its window, in physical pixels. The 5120x1440 screen has a
 * driver half (left) and a passenger half (right); the two-finger swipe moves an app's 2560x1440 window
 * between them without resizing or relaunching it (EZ-60 probe, 2026-10-10).
 *
 * On the driver half the car's own apps use [64,192]–[2560,1272] (2496x1080), and [832,192]–[2560,1272]
 * (1728x1080) while its left vehicle panel or voice assistant shows. The passenger half is taken to mirror
 * that against the screen's right edge and the right voice assistant; it is not yet measured.
 */
internal object MazdaCockpitGeometry {
    enum class Side { DRIVER, PASSENGER }

    data class Panel(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    private const val HALF_WIDTH = 2560
    private const val TOP = 192
    private const val BOTTOM = 1272
    private const val EDGE = 64
    private const val VEHICLE_PANEL = 832

    /** The half a window at [windowLeft] sits on. A window wider than a half (the whole cockpit) is the driver's. */
    fun side(windowLeft: Int, windowWidth: Int, displayWidth: Int): Side =
        if (displayWidth >= 2 * HALF_WIDTH && windowWidth <= HALF_WIDTH &&
            windowLeft + windowWidth / 2 >= displayWidth / 2) Side.PASSENGER else Side.DRIVER

    /** Whether the car's panel or voice assistant narrows the app on [side]. */
    fun narrow(side: Side, screenShow: Int, vpaLeft: Int, vpaRight: Int): Boolean = when (side) {
        Side.DRIVER -> screenShow != 0 || vpaLeft == 1
        Side.PASSENGER -> vpaRight == 1
    }

    /** The panel in window coordinates; the window is a cockpit half, or the whole cockpit for the driver. */
    fun panel(side: Side, narrow: Boolean): Panel = when (side) {
        Side.DRIVER -> Panel(if (narrow) VEHICLE_PANEL else EDGE, TOP, HALF_WIDTH, BOTTOM)
        Side.PASSENGER -> Panel(0, TOP, HALF_WIDTH - if (narrow) VEHICLE_PANEL else EDGE, BOTTOM)
    }
}
