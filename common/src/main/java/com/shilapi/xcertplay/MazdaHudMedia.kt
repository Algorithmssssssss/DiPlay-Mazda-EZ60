package com.shilapi.xcertplay

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.MazdaInteractiveService.Companion.ABANDON_MEDIA_FOCUS
import com.shilapi.xcertplay.MazdaInteractiveService.Companion.MEDIA_SOURCE
import com.shilapi.xcertplay.MazdaInteractiveService.Companion.REQUEST_MEDIA_FOCUS
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.util.concurrent.Executors

/**
 * The CarPlay song on the Mazda EZ-60's windshield HUD. The HUD shows the playing song's lyric line, which the
 * car's media center (com.tinnove.mediacenter, DSIManager) sends through the head unit's interaction service
 * ([MazdaInteractiveService]): it takes media focus, then sends the song as JSON. CarPlay sends no lyrics, so
 * DiPlay puts "title - artist" in that line.
 *
 * Like the media center, DiPlay sends only while it holds Android audio focus for CarPlay music
 * ([CarPlayMediaKeys]): the media center sends only while it holds audio focus, so the two never take the HUD
 * from each other. The media center's Bluetooth player also takes audio focus whenever the iPhone reports playing
 * over Bluetooth, if the car's per-phone "always play" switch is on, and then reclaims the line every second
 * (EZ-60 report 14); DiPlay steps aside while it holds focus ("bt_source_focus_key" = 1). Song names are not logged.
 */
internal object MazdaHudMedia {
    private const val TAG = "DiPlay-MazdaHudMedia"

    /** What the HUD shows of the CarPlay song. */
    internal data class Song(val title: String, val artist: String, val album: String, val playing: Boolean) {
        companion object {
            fun of(info: CarPlayNowPlaying): Song? = info.title?.trim()?.takeIf { it.isNotEmpty() }?.let {
                Song(it, info.artist?.trim().orEmpty(), info.album?.trim().orEmpty(), info.playing)
            }
        }
    }

    /** The CarPlay host's session log while one is open. */
    @Volatile var log: ((String) -> Unit)? = null

    @Volatile private var requested: Song? = null
    @Volatile private var observing = false
    private val worker = Executors.newSingleThreadExecutor {
        Thread(it, "diplay-mazda-hud-media").apply { isDaemon = true }
    }
    private val callback by lazy {
        MazdaInteractiveService.FocusCallback(MazdaInteractiveService.MEDIA_FOCUS_CALLBACK) {
            emit("HUD: media focus changed value=$it")
            worker.execute { onFocusOnWorker(it) }
        }
    }
    // Worker thread only.
    private val service = MazdaInteractiveService(::emit)
    private var appContext: Context? = null
    private var wanted: Song? = null
    private var active = false
    private var sent: Song? = null
    private var yieldLogged = false

    /** The song CarPlay plays while it holds audio focus, or null. Any thread. */
    fun update(context: Context, song: Song?) {
        if (!MazdaPlatform.isEz60 || song == requested) return
        requested = song
        val app = context.applicationContext
        observeBluetoothSource(app)
        worker.execute {
            appContext = app
            wanted = song
            applyOnWorker()
        }
    }

    /** The HUD song switch changed: show or clear the current song now. */
    fun settingChanged(context: Context) {
        if (!MazdaPlatform.isEz60) return
        val app = context.applicationContext
        worker.execute {
            appContext = app
            applyOnWorker()
        }
    }

    private fun applyOnWorker() {
        val context = appContext ?: return
        val song = wanted?.takeIf { MazdaHudSettings.song(context) }
        when {
            song == null -> stopOnWorker(context)
            bluetoothSourceHasFocus(context) -> yieldOnWorker(context)
            else -> {
                yieldLogged = false
                sendOnWorker(context, song)
            }
        }
    }

    // The car's Bluetooth player holds focus: its record owns the line until it lets go.
    private fun yieldOnWorker(context: Context) {
        if (!yieldLogged) emit("HUD: song waits; the car's Bluetooth music has focus")
        yieldLogged = true
        if (!active) return
        active = false
        sent = null
        service.call(ABANDON_MEDIA_FOCUS) {
            writeString(context.packageName)
            writeStrongBinder(callback)
        }
    }

    private fun bluetoothSourceHasFocus(context: Context): Boolean =
        runCatching { Settings.Global.getInt(context.contentResolver, BT_SOURCE_FOCUS, 0) == 1 }.getOrDefault(false)

    private fun observeBluetoothSource(context: Context) {
        if (observing) return
        observing = true
        runCatching {
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor(BT_SOURCE_FOCUS), false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        worker.execute(::applyOnWorker)
                    }
                })
        }.onFailure { emit("HUD: Bluetooth source focus not observable ${it.javaClass.simpleName}") }
    }

    private fun sendOnWorker(context: Context, song: Song) {
        service.binder() ?: return
        if (!active) {
            val focus = service.call(REQUEST_MEDIA_FOCUS) {
                writeString(context.packageName)
                writeStrongBinder(callback)
            }
            active = true
            sent = null
            emit("HUD: song feed started on the car's service focus=$focus")
        }
        if (song == sent) return
        val accepted = service.call(MEDIA_SOURCE) { writeString(songJson(song)) }
        if (sent?.copy(playing = song.playing) != song) emit("HUD: song sent playing=${song.playing} accepted=$accepted")
        sent = song
    }

    private fun stopOnWorker(context: Context) {
        if (!active) return
        active = false
        sent = null
        service.binder() ?: return
        // The media center's clear, then focus goes back.
        service.call(MEDIA_SOURCE) { writeString(CLEARED_JSON) }
        service.call(ABANDON_MEDIA_FOCUS) {
            writeString(context.packageName)
            writeStrongBinder(callback)
        }
        emit("HUD: song cleared")
    }

    private fun onFocusOnWorker(value: Int?) {
        if (!active) return
        if (value == FOCUS_GAINED) {
            // Sent again once the car grants focus, as the media center does.
            sent = null
            applyOnWorker()
        } else {
            // Another player took the HUD: leave it until DiPlay's song or play state changes.
            active = false
            sent = null
        }
    }

    private fun emit(message: String) {
        log?.invoke(message) ?: Log.i(TAG, message)
    }

    private const val FOCUS_GAINED = 1
    private const val BT_SOURCE_FOCUS = "bt_source_focus_key" // the media center's Bluetooth player: 1 = focus
    private const val PLAYING = 2 // the media center's play states
    private const val PAUSED = 7
    private const val SOURCE_OTHER = 35 // the media center's type for any source but Bluetooth, local or USB music
    private const val UNKNOWN = "未知" // the media center's name for a missing singer or album
    internal const val CLEARED_JSON =
        "{\"albumUri\":\"\",\"playStatus\":1,\"singerName\":\"$UNKNOWN\",\"songName\":\"$UNKNOWN\"," +
            "\"sourceID\":0,\"sourceType\":$SOURCE_OTHER}"

    /** The media center's song record (its Gson SongBean), with "title - artist" as the lyric line. */
    internal fun songJson(song: Song): String {
        val id = (listOf(song.title, song.artist, song.album).joinToString("\u0000").hashCode() and Int.MAX_VALUE)
            .coerceAtLeast(1)
        val line = if (song.artist.isEmpty()) song.title else "${song.title} - ${song.artist}"
        return "{\"albumUri\":\"\"," +
            "\"lyricObj\":{\"curLyric\":${quote(line)},\"lyricStatus\":1,\"sourceID\":$id}," +
            "\"playStatus\":${if (song.playing) PLAYING else PAUSED}," +
            "\"programName\":${quote(song.album.ifEmpty { UNKNOWN })}," +
            "\"singerName\":${quote(song.artist.ifEmpty { UNKNOWN })}," +
            "\"songName\":${quote(song.title)},\"sourceID\":$id,\"sourceType\":$SOURCE_OTHER}"
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
