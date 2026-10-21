package com.shilapi.xcertplay

import android.content.Context
import android.media.audiofx.Visualizer
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.media.MusicLevel
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Runs [MazdaAmbientShow] on one background thread while the EZ-60 switch is on (off by default). Writes go the way
 * the car's media center sends them: the car's ambient-light adapter ("wt.atom", IAtomInterface.setValue) with the
 * media center's priority; music mode is checked by reading the scene mode back from the vehicle-property service
 * ([MazdaAmbientProbe]). Every entry point catches its own errors: a failure here never reaches CarPlay.
 * With CarPlay audio over the car's Bluetooth the music never reaches DiPlay; the level then comes from the car's own
 * sound output ([MazdaCarOutputLevel], experimental).
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
    // Music louder than this (RMS, about -40 dBFS) is heard: a pause sends silence or nothing.
    private const val AUDIBLE_LEVEL = 0.01
    private const val AUDIBLE_HOLD_NANOS = 2_000_000_000L

    /** The CarPlay host's session log while one is open. */
    @Volatile var log: ((String) -> Unit)? = null

    @Volatile private var appContext: Context? = null
    @Volatile private var playing = false
    @Volatile private var focused = false
    @Volatile private var audioActive = false
    @Volatile private var song: String? = null
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-mazda-ambient").apply { isDaemon = true } }
    // Worker thread only.
    private var ticking: ScheduledFuture<*>? = null
    private var atom: IBinder? = null
    private var audibleAtNanos = 0L
    private var followingAudio = false
    private val carOutput = MazdaCarOutputLevel(::emit)
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
        update(context, playing, focused, audioActive, song)
    }

    /**
     * CarPlay music state, from the media session: [playing] as the iPhone reports it, whether CarPlay's music holds
     * the car's focus, whether its music audio stream is open ([audioActive]) and which [song] it is.
     */
    fun update(context: Context, playing: Boolean, focused: Boolean, audioActive: Boolean = false, song: String? = null) {
        if (!MazdaPlatform.isEz60) return
        this.playing = playing
        this.focused = focused
        this.audioActive = audioActive
        val newSong = song != null && song != this.song
        this.song = song
        appContext = context.applicationContext
        run {
            if (newSong) show.newSong()
            schedule()
        }
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
        // Also while the music stream is open: the iPhone can report a song that plays as stopped ([tick]).
        val on = enabled(context) && (playing || audioActive)
        MusicLevel.wanted = on
        if (on && ticking == null) {
            ticking = worker.scheduleWithFixedDelay({
                runCatching { tick() }.onFailure { Log.w(TAG, "ambient light tick failed", it) }
            }, 0, MazdaAmbientShow.TICK_MILLIS, TimeUnit.MILLISECONDS)
        } else if (!on) {
            ticking?.cancel(false)
            ticking = null
            carOutput.release()
            show.stop(if (enabled(context)) "music stopped" else "switch turned off")
        }
    }

    private fun tick() {
        val context = appContext ?: return
        val now = System.nanoTime()
        val fresh: Boolean
        val peak: MusicLevel.Peak
        if (AirPlayPersistence.loadCarBluetoothAudio(context)) {
            val heard = carOutput.read(playing && focused)
            fresh = heard != null
            peak = heard ?: MusicLevel.Peak(0.0, 0.0)
        } else {
            carOutput.release()
            fresh = now - MusicLevel.atNanos < FRESH_AUDIO_NANOS
            peak = MusicLevel.take()
        }
        if (fresh && peak.level >= AUDIBLE_LEVEL) audibleAtNanos = now
        // After some song changes the iPhone reported "not playing" for up to a minute while the song played on
        // (EZ-60, 2026-10-21): music the car can hear counts as playing.
        val audible = now - audibleAtNanos < AUDIBLE_HOLD_NANOS
        val following = !playing && audible
        if (following != followingAudio) {
            followingAudio = following
            if (following) emit("Ambient: the iPhone reports no music playing, but music is heard; following the music")
        }
        show.tick(enabled(context) && (playing || audible) && focused && fresh, carSettings(context), peak.level,
            peak.bass, tuning(context))
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

/**
 * The music level of the car's own sound output (Android's Visualizer on the output mix, session 0), for CarPlay
 * audio over the car's Bluetooth: the iPhone then plays to the car's Bluetooth player and no music reaches DiPlay.
 * Needs the microphone permission DiPlay already has for calls. Experimental: whether the EZ-60's output mix carries
 * the Bluetooth player is untested, so the log says what it hears. Worker thread only.
 */
internal class MazdaCarOutputLevel(private val log: (String) -> Unit) {
    private var visualizer: Visualizer? = null
    private var unavailable = false
    private var waveform = ByteArray(0)
    private var samplingRateHz = 48_000
    private var heardOnce = false
    private var silentTicks = 0

    /** The level of the last capture, or null when the car's output cannot be heard. [expecting]: music should play. */
    fun read(expecting: Boolean): MusicLevel.Peak? {
        val listening = visualizer ?: open() ?: return null
        if (runCatching { listening.getWaveForm(waveform) }.getOrDefault(Visualizer.ERROR) != Visualizer.SUCCESS) return null
        val peak = levels(waveform, samplingRateHz)
        if (peak.level >= HEARD_LEVEL && !heardOnce) {
            heardOnce = true
            log("Ambient: Bluetooth music: the car's sound output is heard; the lights follow it")
        } else if (!heardOnce && expecting && ++silentTicks == SILENT_TICKS_TO_REPORT) {
            log("Ambient: Bluetooth music: the car's sound output stays silent while the iPhone plays; " +
                "its Bluetooth player cannot be heard here")
        }
        return peak
    }

    fun release() {
        visualizer?.let { runCatching { it.enabled = false; it.release() } }
        visualizer = null
    }

    private fun open(): Visualizer? {
        if (unavailable) return null
        return runCatching {
            Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(CAPTURE_SIZE)
                scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
                enabled = true
            }
        }.onSuccess {
            visualizer = it
            waveform = ByteArray(it.captureSize)
            samplingRateHz = (it.samplingRate / 1000).takeIf { rate -> rate > 0 } ?: 48_000
            log("Ambient: Bluetooth music: listening to the car's sound output (capture ${it.captureSize} at " +
                "$samplingRateHz Hz)")
        }.onFailure {
            unavailable = true
            log("Ambient: Bluetooth music: the car's sound output cannot be heard (${it.javaClass.simpleName}: " +
                "${it.message}); no light sync over Bluetooth")
        }.getOrNull()
    }

    internal companion object {
        private const val CAPTURE_SIZE = 1024
        private const val HEARD_LEVEL = 0.01
        private const val SILENT_TICKS_TO_REPORT = 80 // about 10 s of ticks
        private const val BASS_HZ = 150.0

        /** RMS of a Visualizer waveform (8-bit, 128 = silence) and of its bass below about 150 Hz, both 0..1. */
        fun levels(waveform: ByteArray, samplingRateHz: Int): MusicLevel.Peak {
            if (waveform.isEmpty()) return MusicLevel.Peak(0.0, 0.0)
            val smoothing = 1 - exp(-2 * PI * BASS_HZ / samplingRateHz.coerceAtLeast(1))
            var bass = 0.0
            var sum = 0.0
            var bassSum = 0.0
            for (byte in waveform) {
                val sample = ((byte.toInt() and 0xff) - 128) / 128.0
                bass += (sample - bass) * smoothing
                sum += sample * sample
                bassSum += bass * bass
            }
            return MusicLevel.Peak(sqrt(sum / waveform.size), sqrt(bassSum / waveform.size))
        }
    }
}
