package com.shilapi.xcertplay

import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/**
 * The EZ-60 head unit's interaction service ("com.incall.double.INTERACTIVE_SERVICE", IDouInteractiveManager),
 * which the car's own navigation and media apps feed the windshield HUD through. Only the calls in [ALLOWED] are
 * made: navigation guidance, the playing song, and taking and giving back navigation or media focus. Nothing else
 * on that service, and no vehicle properties. Not thread-safe: each user calls it from its own worker thread.
 */
internal class MazdaInteractiveService(private val log: (String) -> Unit) {
    private var service: IBinder? = null
    private var unavailableLogged = false

    /** The service, looked up again after it died; null when the car does not offer it. */
    fun binder(): IBinder? {
        service?.takeIf { it.isBinderAlive }?.let { return it }
        service = null
        val manager = serviceManager(SERVICE_MANAGER) ?: return unavailable("service manager not registered")
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken(SERVICE_MANAGER_INTERFACE)
            request.writeString(NAVIGATION_SERVICE)
            manager.transact(GET_SERVER, request, reply, 0)
            reply.readException()
            reply.readStrongBinder()?.also { service = it; unavailableLogged = false }
                ?: unavailable("navigation service not offered")
        } catch (error: Exception) {
            unavailable("lookup failed ${error.javaClass.simpleName}")
        } finally {
            request.recycle()
            reply.recycle()
        }
    }

    private fun unavailable(reason: String): IBinder? {
        if (!unavailableLogged) log("HUD: car navigation service unavailable: $reason")
        unavailableLogged = true
        return null
    }

    /** One allowlisted call; false when the service is missing or refused it. */
    fun call(code: Int, write: Parcel.() -> Unit): Boolean {
        check(code in ALLOWED) { "not a HUD call: $code" }
        val binder = binder() ?: return false
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken(INTERACTIVE_INTERFACE)
            request.write()
            binder.transact(code, request, reply, 0)
            reply.readException()
            true
        } catch (error: Exception) {
            log("HUD: call $code refused ${error.javaClass.simpleName}: ${error.message}")
            if (!binder.isBinderAlive) service = null
            false
        } finally {
            request.recycle()
            reply.recycle()
        }
    }

    /**
     * The car tells the focus holder when another app takes or returns the HUD: INaviFocusCallback and
     * IMediaFocusCallback both use code 3, onFocusChange(int), where 1 means DiPlay holds focus.
     */
    class FocusCallback(private val descriptor: String, private val onFocus: (Int?) -> Unit) : Binder() {
        init {
            attachInterface(null, descriptor)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in 1..3) return super.onTransact(code, data, reply, flags)
            val value = runCatching {
                data.enforceInterface(descriptor)
                // IMediaFocusCallback's code 2, onMediaInfo, carries a string; every other call an int.
                if (code == 2 && descriptor == MEDIA_FOCUS_CALLBACK) null else data.readInt()
            }.getOrNull()
            if (code == ON_FOCUS_CHANGE) onFocus(value)
            reply?.writeNoException()
            return true
        }
    }

    internal companion object {
        private const val SERVICE_MANAGER = "com.incall.SVR_MNG_SERVICE"
        private const val SERVICE_MANAGER_INTERFACE = "com.incall.serversdk.server.ISvrManager"
        private const val GET_SERVER = 6
        private const val NAVIGATION_SERVICE = "com.incall.double.INTERACTIVE_SERVICE"
        private const val INTERACTIVE_INTERFACE = "com.incall.serversdk.interactive.IDouInteractiveManager"
        const val NAVI_FOCUS_CALLBACK = "com.incall.serversdk.interactive.callback.INaviFocusCallback"
        const val MEDIA_FOCUS_CALLBACK = "com.incall.serversdk.interactive.callback.IMediaFocusCallback"
        private const val ON_FOCUS_CHANGE = 3

        const val STATUS = 22 // sendNavigateStatus(int)
        const val TURN = 24 // sendNavigateTurnInfo(int turnId, int distanceMeters)
        const val ROAD = 26 // sendNavigateRoadInfo(String current, String next)
        const val REMAIN = 27 // sendNavigateRemainInfo(int meters, int seconds)
        const val MEDIA_SOURCE = 37 // sendMediaSource(String json)
        const val REQUEST_MEDIA_FOCUS = 61 // requestMediaFocus(String package, IMediaFocusCallback)
        const val ABANDON_MEDIA_FOCUS = 62 // abandonMediaFocus(String package, IMediaFocusCallback)
        const val REQUEST_NAVI_FOCUS = 63 // requestNaviFocus(String package, INaviFocusCallback)
        const val ABANDON_NAVI_FOCUS = 64 // abandonNaviFocus(String package, INaviFocusCallback)
        val ALLOWED = setOf(STATUS, TURN, ROAD, REMAIN, MEDIA_SOURCE, REQUEST_MEDIA_FOCUS, ABANDON_MEDIA_FOCUS,
            REQUEST_NAVI_FOCUS, ABANDON_NAVI_FOCUS)

        @Suppress("DiscouragedPrivateApi", "PrivateApi")
        private fun serviceManager(name: String): IBinder? = runCatching {
            val manager = Class.forName("android.os.ServiceManager")
            val lookup = runCatching { manager.getMethod("checkService", String::class.java) }.getOrNull()
                ?: manager.getMethod("getService", String::class.java)
            lookup.invoke(null, name) as IBinder?
        }.getOrNull()
    }
}
