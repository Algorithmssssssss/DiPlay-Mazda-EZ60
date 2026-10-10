package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MazdaHudMediaTest {
    @Test fun aSongNeedsATitle() {
        assertNull(MazdaHudMedia.Song.of(CarPlayNowPlaying()))
        assertNull(MazdaHudMedia.Song.of(CarPlayNowPlaying(title = "  ", artist = "Artist")))
        assertEquals(MazdaHudMedia.Song("Song", "Artist", "", true),
            MazdaHudMedia.Song.of(CarPlayNowPlaying(title = " Song ", artist = "Artist", playing = true,
                elapsedMillis = 1_000)))
    }

    @Test fun theSongGoesOutAsTheMediaCentersRecord() {
        val json = MazdaHudMedia.songJson(MazdaHudMedia.Song("Song", "Artist", "Album", playing = true))
        val id = Regex("\"sourceID\":(\\d+),\"sourceType\"").find(json)!!.groupValues[1]
        assertEquals("{\"albumUri\":\"\",\"lyricObj\":{\"curLyric\":\"Song - Artist\",\"lyricStatus\":1," +
            "\"sourceID\":$id},\"playStatus\":2,\"programName\":\"Album\",\"singerName\":\"Artist\"," +
            "\"songName\":\"Song\",\"sourceID\":$id,\"sourceType\":35}", json)
    }

    @Test fun missingNamesAndPauseUseTheMediaCentersValues() {
        val json = MazdaHudMedia.songJson(MazdaHudMedia.Song("Song", "", "", playing = false))
        assert("\"curLyric\":\"Song\"" in json)
        assert("\"playStatus\":7" in json)
        assert("\"programName\":\"未知\",\"singerName\":\"未知\"" in json)
    }

    @Test fun theSameSongKeepsItsId() {
        val playing = MazdaHudMedia.songJson(MazdaHudMedia.Song("Song", "Artist", "Album", playing = true))
        val paused = MazdaHudMedia.songJson(MazdaHudMedia.Song("Song", "Artist", "Album", playing = false))
        assertEquals(playing.replace("\"playStatus\":2", "\"playStatus\":7"), paused)
    }

    @Test fun namesAreEscaped() {
        val json = MazdaHudMedia.songJson(MazdaHudMedia.Song("Say \"Hi\"\\", "A\nB", "", playing = true))
        assert("\"curLyric\":\"Say \\\"Hi\\\"\\\\ - A\\nB\"" in json)
    }

    @Test fun clearingMatchesTheMediaCenter() {
        assertEquals("{\"albumUri\":\"\",\"playStatus\":1,\"singerName\":\"未知\",\"songName\":\"未知\"," +
            "\"sourceID\":0,\"sourceType\":35}", MazdaHudMedia.CLEARED_JSON)
    }
}
