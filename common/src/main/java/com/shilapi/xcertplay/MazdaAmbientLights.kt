package com.shilapi.xcertplay

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.media.MusicLevel
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Runs [MazdaAmbientShow] on one background thread while the EZ-60 switch is on (off by default). Writes go the way
 * the car's media center sends them: the car's ambient-light adapter ("wt.atom", IAtomInterface.setValue) with the
 * media center's priority; music mode is checked by reading the scene mode back from the vehicle-property service
 * ([MazdaAmbientProbe]). Every entry point catches its own errors: a failure here never reaches CarPlay.
 */
internal object MazdaAmbientLights {
    private const val TAG = "DiPlay-MazdaAmbient"
    private const val PREFS = "diplay_mazda_ambient"
    private const val KEY_ENABLED = "music"
    private const val KEY_LEFT_ON = "left_in_music_mode"
    private const val KEY_EFFECT = "effect"
    private const val KEY_SMOOTHNESS = "smoothness"
    private const val KEY_DEPTH = "depth"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val ATOM_SERVICE = "wt.atom"
    private const val ATOM_INTERFACE = "com.openos.systemadapter.atom.IAtomInterface"
    private const val ATOM_SET_VALUE = 1
    private const val PRIORITY = 200 // the media center's
    private const val FRESH_AUDIO_NANOS = 600_000_000L

    /** The CarPlay host's session log while one is open. */
    @Volatile var log: ((String) -> Unit)? = null

    @Volatile private var appContext: Context? = null
    @Volatile private var playing = false
    @Volatile private var focused = false
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-mazda-ambient").apply { isDaemon = true } }
    // Worker thread only.
    private var ticking: ScheduledFuture<*>? = null
    private var atom: IBinder? = null
    private val show by lazy {
        MazdaAmbientShow(
            write = ::atomWrite,
            readSceneMode = { MazdaAmbientProbe.intValue(MazdaAmbientShow.SCENE_MODE, 0) },
            now = { System.currentTimeMillis() },
            log = ::emit,
            leftOn = { on -> appContext?.let { prefs(it).edit().putBoolean(KEY_LEFT_ON, on).apply() } },
        )
    }

    fun enabled(context: Context): Boolean = MazdaPlatform.isEz60 && prefs(context).getBoolean(KEY_ENABLED, false)

    /** How the lights follow the music; read on every tick, so a change applies at once. */
    fun tuning(context: Context): MazdaAmbientTuning {
        val prefs = prefs(context)
        val defaults = MazdaAmbientTuning()
        fun int(key: String, fallback: Int) = runCatching { prefs.getInt(key, fallback) }.getOrDefault(fallback)
        return MazdaAmbientTuning(
            effect = runCatching { MazdaAmbientTuning.Effect.valueOf(prefs.getString(KEY_EFFECT, null) ?: "") }
                .getOrDefault(defaults.effect),
            smoothness = int(KEY_SMOOTHNESS, defaults.smoothness).coerceIn(MazdaAmbientTuning.SMOOTHNESS),
            depth = int(KEY_DEPTH, defaults.depth).coerceIn(MazdaAmbientTuning.DEPTHS.first(), MazdaAmbientTuning.DEPTHS.last()),
            sensitivity = int(KEY_SENSITIVITY, defaults.sensitivity).coerceIn(MazdaAmbientTuning.SENSITIVITY),
        )
    }

    fun saveTuning(context: Context, tuning: MazdaAmbientTuning) {
        prefs(context).edit()
            .putString(KEY_EFFECT, tuning.effect.name)
            .putInt(KEY_SMOOTHNESS, tuning.smoothness)
            .putInt(KEY_DEPTH, tuning.depth)
            .putInt(KEY_SENSITIVITY, tuning.sensitivity)
            .apply()
    }

    fun saveEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        update(context, playing, focused)
    }

    /** CarPlay music state, from the media session: [playing] and whether CarPlay's music holds the car's focus. */
    fun update(context: Context, playing: Boolean, focused: Boolean) {
        if (!MazdaPlatform.isEz60) return
        this.playing = playing
        this.focused = focused
        appContext = context.applicationContext
        run { schedule() }
    }

    /** A new CarPlay session: a show that failed in the last one may try again. */
    fun sessionStarted(context: Context) {
        if (!MazdaPlatform.isEz60) return
        appContext = context.applicationContext
        run { show.reset() }
    }

    /** At app start: hand back lights DiPlay left in music mode if it stopped in the middle of a song. */
    fun restoreIfLeftOn(context: Context) {
        if (!MazdaPlatform.isEz60 || !prefs(context).getBoolean(KEY_LEFT_ON, false)) return
        appContext = context.applicationContext
        run { show.restoreLeftOn(carSettings(context.applicationContext)) }
    }

    private fun schedule() {
        val context = appContext ?: return
        val on = enabled(context) && playing
        MusicLevel.wanted = on
        if (on && ticking == null) {
            ticking = worker.scheduleWithFixedDelay({
                runCatching { tick() }.onFailure { Log.w(TAG, "ambient light tick failed", it) }
            }, 0, MazdaAmbientShow.TICK_MILLIS, TimeUnit.MILLISECONDS)
        } else if (!on) {
            ticking?.cancel(false)
            ticking = null
            show.stop(if (enabled(context)) "music stopped" else "switch turned off")
        }
    }

    private fun tick() {
        val context = appContext ?: return
        val fresh = System.nanoTime() - MusicLevel.atNanos < FRESH_AUDIO_NANOS
        val peak = MusicLevel.take()
        show.tick(enabled(context) && playing && focused && fresh, carSettings(context), peak.level, peak.bass, tuning(context))
    }

    private fun carSettings(context: Context): MazdaAmbientShow.CarSettings {
        fun global(name: String, default: Int) =
            runCatching { Settings.Global.getInt(context.contentResolver, name, default) }.getOrDefault(default)
        return MazdaAmbientShow.CarSettings(
            musicSyncOn = global("chinatsp.global.LIGHT_RHYTHM_CTL", 0) == 1,
            fixedColour = global("chinatsp.global.LIGHT_RHYTHM_MODE", 0) != 0,
            colour = global("chinatsp.global.LIGHT_COLORBAR_CTL", 1),
            brightness = global("chinatsp.global.LIGHT_BRIGHTNESS_CTL", 6),
        )
    }

    // IAtomInterface.setValue(VirtualCarValue, priority): one-way, as the media center sends it.
    private fun atomWrite(step: MazdaAmbientShow.Write): Boolean {
        check(MazdaAmbientShow.allowed(step)) { "not an ambient-light write: $step" }
        val binder = atom?.takeIf { it.isBinderAlive } ?: runCatching {
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, ATOM_SERVICE) as IBinder?
        }.getOrNull()?.also { atom = it } ?: return false
        val request = Parcel.obtain()
        return try {
            request.writeInterfaceToken(ATOM_INTERFACE)
            request.writeInt(1)
            request.writeInt(step.property)
            request.writeInt(step.area)
            request.writeString(step.value.javaClass.name)
            request.writeValue(step.value)
            request.writeInt(PRIORITY)
            binder.transact(ATOM_SET_VALUE, request, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
            if (!binder.isBinderAlive) atom = null
            false
        } finally {
            request.recycle()
        }
    }

    private fun run(block: () -> Unit) {
        runCatching { worker.execute { runCatching(block).onFailure { Log.w(TAG, "ambient light step failed", it) } } }
    }

    private fun emit(message: String) {
        Log.i(TAG, message)
        log?.let { runCatching { it(message) } }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
