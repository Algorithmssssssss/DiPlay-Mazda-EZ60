package com.shilapi.xcertplay

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings

/**
 * A read-only look at the EZ-60's ambient lighting, as the car's media center sees it when it syncs the lights
 * to its own music (com.tinnove.mediacenter, CarAtmoManager): its Settings.Global switches, and the vehicle
 * properties it uses, read through the car's vehicle-property service ("virtualcar_service"). Only [READ_CALLS]
 * are made; nothing is written. It tells whether DiPlay could sync the lights to CarPlay music.
 */
internal object MazdaAmbientProbe {
    /** The media center's switches: music sync on/off, sync mode, colour and brightness. */
    val SETTINGS = listOf(
        "chinatsp.global.LIGHT_RHYTHM_CTL",
        "chinatsp.global.LIGHT_RHYTHM_MODE",
        "chinatsp.global.LIGHT_COLORBAR_CTL",
        "chinatsp.global.LIGHT_BRIGHTNESS_CTL",
    )

    /** The vehicle properties the media center reads and writes for the lights, with what it uses them for. */
    val PROPERTIES = linkedMapOf(
        826278810 to "light brightness",
        826278811 to "light colour",
        826278822 to "rhythm colour",
        826278823 to "rhythm brightness",
        826278772 to "scene mode",
        829424549 to "light zones",
    )
    private val AREAS = listOf(0, 1)

    private const val SERVICE = "virtualcar_service"
    private const val SERVICE_INTERFACE = "com.openos.virtualcar.IVirtualCar"
    private const val GET_SERVICE = 2 // IVirtualCar.getVirtualCarService(String)
    private const val PROPERTY_SERVICE = "virtualcar_property_service"
    private const val PROPERTY_INTERFACE = "com.openos.virtualcar.IVirturalCarProperty"
    private const val GET_VALUE = 3 // IVirturalCarProperty.getValue(int property, int area)
    private const val IS_SUPPORT = 4 // IVirturalCarProperty.isSupport(int property, int area)
    /** Every call this probe can make. setValue (2) and the listener calls are not among them. */
    val READ_CALLS = setOf(GET_SERVICE, GET_VALUE, IS_SUPPORT)

    @Volatile private var properties: IBinder? = null

    /** The snapshot section: the switches, then each property's support and value per area. */
    fun section(context: Context, out: StringBuilder) {
        for (name in SETTINGS) out.appendLine("setting $name=${setting(context, name) ?: "unset"}")
        val binder = propertyService()
        if (binder == null) {
            out.appendLine("vehicle-property service unavailable")
            return
        }
        for ((id, purpose) in PROPERTIES) for (area in AREAS) {
            out.appendLine("property $id area=$area ($purpose) supported=${isSupported(binder, id, area)} " +
                "value=${value(binder, id, area)}")
        }
    }

    /** Current values by "property area", for noting changes while the probe records. */
    fun values(): Map<String, String> {
        val binder = propertyService() ?: return emptyMap()
        return PROPERTIES.keys.flatMap { id -> AREAS.map { area -> "$id area=$area" to value(binder, id, area) } }.toMap()
    }

    private fun setting(context: Context, name: String): String? =
        runCatching { Settings.Global.getString(context.contentResolver, name) }.getOrElse { "blocked" }

    private fun propertyService(): IBinder? {
        properties?.takeIf { it.isBinderAlive }?.let { return it }
        val car = runCatching {
            val manager = Class.forName("android.os.ServiceManager")
            manager.getMethod("getService", String::class.java).invoke(null, SERVICE) as IBinder?
        }.getOrNull() ?: return null
        return transact(car, SERVICE_INTERFACE, GET_SERVICE, { writeString(PROPERTY_SERVICE) }) { readStrongBinder() }
            ?.also { properties = it }
    }

    private fun isSupported(binder: IBinder, id: Int, area: Int): String =
        transact(binder, PROPERTY_INTERFACE, IS_SUPPORT, { writeInt(id); writeInt(area) }) { readInt() != 0 }
            ?.toString() ?: "error"

    private fun value(binder: IBinder, id: Int, area: Int): String =
        transact(binder, PROPERTY_INTERFACE, GET_VALUE, { writeInt(id); writeInt(area) }) {
            if (readInt() == 0) return@transact "none"
            // VirtualCarValue: category, function, area, status code, timestamp, value class, value.
            readInt(); readInt(); readInt()
            val code = readInt()
            readLong()
            val type = readString()
            val value = runCatching { readValue(MazdaAmbientProbe::class.java.classLoader) }
                .getOrElse { return@transact "unreadable type=$type code=$code" }
            "${describe(value)} type=${type?.substringAfterLast('.')} code=$code"
        } ?: "error"

    internal fun describe(value: Any?): String = when (value) {
        null -> "null"
        is ByteArray -> value.joinToString(",", "[", "]")
        is IntArray -> value.joinToString(",", "[", "]")
        is FloatArray -> value.joinToString(",", "[", "]")
        is LongArray -> value.joinToString(",", "[", "]")
        is Array<*> -> value.joinToString(",", "[", "]")
        else -> value.toString()
    }

    private fun <T> transact(binder: IBinder, descriptor: String, code: Int, write: Parcel.() -> Unit,
        read: Parcel.() -> T): T? {
        check(code in READ_CALLS) { "not a read call: $code" }
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken(descriptor)
            request.write()
            if (!binder.transact(code, request, reply, 0)) return null
            reply.readException()
            reply.read()
        } catch (_: Exception) {
            if (!binder.isBinderAlive) properties = null
            null
        } finally {
            request.recycle()
            reply.recycle()
        }
    }
}
