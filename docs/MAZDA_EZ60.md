# DiPlay for the Mazda EZ-60

This fork tunes DiPlay for the Chinese-market Mazda EZ-60 (Changan Mazda J90K, CAMZD OS on Android 14,
MediaTek MT8676). The car has one 26.45-inch 5K screen (5120×1440, 32:9) with a driver half and a
passenger half; a two-finger swipe moves an app between them.

It installs next to upstream DiPlay as `com.reachkim.diplay.ez60` (debug builds add `.hudtest`). The
update check in About and upstream's background update checks are off, because upstream releases are a
different app. This fork includes upstream DiPlay through 0.2.17.

## Status

| Area | State |
| --- | --- |
| Driver panel | CarPlay fills the 2496×1080 panel at `[64,192]–[2560,1272]` and narrows to 1728×1080 when the car shows its left vehicle panel or voice assistant, without reconnecting. Geometry from the car's own apps, via [TidePlayer's measurements](https://github.com/JulyStar-Lv/TidePlayer/blob/main/docs/automotive/CAR_WINDOW_PROFILES.md). Not yet seen with CarPlay running. |
| Passenger half | The panel mirrors the driver's: `[0,192]–[2496,1272]` in the window, 1728 wide while the right voice assistant shows. This mirroring is assumed, not measured. |
| Two-finger swipe | Moves the window between halves without resizing or relaunching it, so CarPlay keeps its session; the panel follows the new half. The CarPlay screen shares the DiPlay menu's task, so both move together. |
| Opening on the launch half | A new task started from DiPlay opens on DiPlay's half (tested on both halves), and the CarPlay screen now opens inside DiPlay's own task. Opening DiPlay from the other half's app list moves it there. |
| Video | H.264 at 2496×1080, 60 fps by default on the EZ-60, through `c2.mtk.avc.decoder.lowlatency` with its own output thread. Best measured so far (built-in car hotspot, 2026-10-11): 43–59 fps while in use, decode 4–5 ms (p90 7–11 ms), touch to picture 21–60 ms, up to 33 Mbps. Earlier, Wi-Fi Direct with the car joined to the iPhone's hotspot gave 23–35 fps and pauses of up to 1.5 s. |
| Wheel keys | Previous, next and play/pause arrive as standard media keys. |
| USB | An iPhone on the USB port appears as a USB data device (Apple `05ac:12a8`, 12 interfaces). Wired CarPlay runs through an Android VPN, and CAMZD OS has no VPN consent screen (`com.android.vpndialogs` is missing), so the VPN must be allowed once over ADB; see below. |
| Wi-Fi, audio | Upstream behaviour so far. |

## Measured on the car

From the EZ-60 probe on 2026-10-10 (CAMZD OS build 20250930150736):

- **Platform:** Wutong/Tinnove J90K, Android 14 (Android Automotive), MediaTek MT8676.
- **Screen:** one 5120×1440 display with one touch panel. DiPlay's window is 2560×1440 on either half: the left half belongs to the car's left task group, the right half to the "copilot" group. A 160 px status bar is at the top and a 136 px dock at the bottom of each half. The system reports 160 dpi, which is nominal; the panel is about 0.126 mm per pixel.
- **Swipe:** `global.fly_to_right_status` goes 1 (to the passenger half) or 2 (back), the window moves within about 0.2 s, and `copilot_display_status` / `main_display_status` name the app on each half. The app is not relaunched; it gets a configuration change with the new bounds.
- **New tasks:** a separate task started from DiPlay opens on DiPlay's half. Launching DiPlay from the other half's app list moves its task to that half.
- **Video decoders:** `c2.mtk.avc.decoder` (no low-latency mode) and `c2.mtk.avc.decoder.lowlatency`. H.264 at 2496×1080 is good for 87–191 fps, HEVC for 31–70 fps. No decoder accepts 5120 wide (the limit is 4096).
- **Wi-Fi:** 5 GHz and 6 GHz, Wi-Fi Direct, hotspot and client at the same time. The hotspot interface is `ap0`.
- **Audio:** Android Automotive buses for media, navigation, calls, ringtone and voice per seat; the car sets the volume.
- **Bluetooth:** hands-free and music receiver only.
- **Instrument cluster:** an 800×480 presentation display, `MeterDisplay#0`.
- **Also installed:** `com.autochips.carplayapp`, a CarPlay app from the head unit's maker.

## Connect

### Wired: allow the VPN once

DiPlay's wired CarPlay needs Android's VPN permission. The car cannot show the screen that grants it,
so allow it once from a computer with ADB access to the car:

```sh
adb shell appops set com.reachkim.diplay.ez60.hudtest ACTIVATE_VPN allow
```

Use `com.reachkim.diplay.ez60` for a release build. The permission lasts until the app is uninstalled;
installing a new build over it keeps it.

### Wireless: choose how the iPhone joins

Wireless needs a Wi-Fi link besides Bluetooth. The default is **Built-in car hotspot**, which needs the
car hotspot's name and password: without them **Connect** returns to Connection setup. In DiPlay, open
**Settings → Connection → Connection setup** and either enter the car hotspot's details or choose
**Wi-Fi Direct**, which needs no hotspot. Pair the iPhone with the car over Bluetooth first.

### Smooth wireless

- **Frame rate:** **Settings → Display** sets it; the EZ-60 starts at 60 fps. A build that saved 30 keeps 30
  until it is changed there.
- **Measure it:** **Settings → Diagnostics → FPS counter** (from upstream 0.2.17) shows the frame rate over
  the CarPlay picture.
- **Video path:** **Low-latency decoding** starts on for the EZ-60: frames leave the decoder from their own
  thread, and the main screen tries `c2.mtk.avc.decoder.lowlatency` first. **Direct video output**
  (Advanced, off by default) draws CarPlay through a SurfaceView instead of the app's TextureView, which
  saves a GPU copy on a 5120×1440 screen the car's 3D desktop also draws on; the panel's rounded corners
  may not clip it. Compare both with the FPS counter.
- **Video format:** keep HEVC off. The hardware H.264 decoder takes 6–13 ms a frame at 2496×1080; the
  hardware HEVC decoder manages only 31–70 fps there, and Android's software HEVC decoder took 19–67 ms a
  frame (it is ignored on the EZ-60).
- **Wi-Fi Direct band:** the EZ-60 starts on **Auto 5 GHz**. With plain Auto, a passing "Wi-Fi P2P is busy"
  answer to the 5 GHz requests sent the group to 2.4 GHz channel 6, where the picture fell to 5–9 fps,
  and Auto then remembered that channel. A channel chosen in Connection setup still takes priority.
- **Wi-Fi pauses:** if the car is joined to another Wi-Fi network on a different channel, its radio
  switches between that channel and CarPlay's, and the picture stops for up to a second at a time. The
  iPhone's own Personal Hotspot is the usual case: then the iPhone switches channels too. Turn the hotspot
  off (or disconnect the car from other Wi-Fi) while using wireless CarPlay; CarPlay does not need it.
  Otherwise set **Connection setup → Wi-Fi Direct → Preferred channel** to the other network's channel.
  See [Smooth wireless CarPlay](SMOOTH_WIRELESS.md).
- **Wi-Fi scanning:** with Wi-Fi Direct and no network joined, the car's Wi-Fi client keeps scanning, which
  takes the radio off the CarPlay channel (upstream measured a 10 s period on BYD cars). Upstream 0.2.17
  pauses that during Wi-Fi Direct, but only with the car's ADB over network approved. The **Built-in car
  hotspot** mode runs with the client off and avoids it; upstream now recommends it, and on the EZ-60 it
  gave the smoothest sessions so far (see Video above).

## Windshield HUD

The EZ-60 has no instrument cluster; the windshield HUD is the driver's only other display. It does not
project apps' windows: a test card on its 800×480 presentation display (`MeterDisplay#0`) never appeared.
Its navigation comes from the head unit's navigation service, `com.incall.double.INTERACTIVE_SERVICE`
(`IDouInteractiveManager`, reached through `com.incall.SVR_MNG_SERVICE`), which the car's navigation apps
feed.

**Turn-by-turn on the HUD (experimental)** (**Settings → Navigation → Location**, EZ-60 only, on by
default) feeds it with CarPlay's route guidance, which Google Maps and Apple Maps both send:

| Step | Call |
|---|---|
| Guidance starts | `requestNaviFocus(package, callback)`, then `sendNavigateStatus(2)` |
| Each update | `sendNavigateRoadInfo(current, next)` when the roads change, `sendNavigateTurnInfo(turnId, metres)`, `sendNavigateRemainInfo(metres, seconds)`: a new turn or road at once, other changes at most once a second |
| Guidance ends | remaining 0/0, empty roads, turn 0/0, `sendNavigateStatus(0)`, `abandonNaviFocus` |

While DiPlay holds navigation focus, the HUD also projects the (573,167)–(800,355) area, 227×188 pixels, of
its 800×480 display (`MeterDisplay#0`); confirmed with the probe's test card on 2026-10-11. The HUD draws the
turn arrow, distance and remaining route itself; DiPlay's window fills that area with CarPlay's
instrument-cluster map (requested at 232×192 with the car marker centred; Google Maps sends it, confirmed
2026-10-11), or with a turn card while the iPhone sends no map. The HUD switch also turns the map off; the change applies at the next
connection.

Only these navigation calls are made, from a background thread, and the session log records each step
(`HUD: ...`). Turn icons use the numbers EZNav sends: 3 destination, 16 straight, 17/18 slight or keep
right/left, 19 left, 20 right, 21/22 sharp left/right, 23 U-turn, 53 roundabout, 74–83 roundabout exit 1–10.
With these the HUD draws its own arrow beside the distance and its 3D guidance chevrons (16, 19 and 3 confirmed
with Google Maps on 2026-10-11). No vehicle properties are written.

**Song on the HUD (experimental)** (same card, on by default) fills the HUD's music line the way the car's media
center (`com.tinnove.mediacenter`) does: `requestMediaFocus(package, callback)`, then
`sendMediaSource(json)` with the media center's song record (song, singer, album, play state and the current
lyric line). CarPlay sends no lyrics, so the lyric line carries "song - artist". DiPlay sends it only while it
holds Android audio focus for CarPlay music, as the media center does, so the two never take the HUD from each
other; when CarPlay music stops or another source plays, it sends the media center's empty record and calls
`abandonMediaFocus`. The cover is not sent yet: the media center hands the HUD a world-readable image file in
its own shared folder.

## Build

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :mobile:assembleDebug
```

Add `-Pez60.variant=next` to build "DiPlay EZ-60 Next" (`com.reachkim.diplay.ez60.next`), which installs
next to the working "DiPlay EZ-60 Test" app instead of over it, for trying a branch. Its settings, paired
iPhone and VPN permission are its own.

The APK is `mobile/build/outputs/apk/debug/mobile-debug.apk`. It has no CarPlay accessory identity, so
it cannot connect an iPhone; it is enough for the probe. To build one that connects, see
[Build a standalone car-test APK](BUILD.md#build-a-standalone-car-test-apk).

## EZ-60 probe

**Settings → Diagnostics → EZ-60 probe** records what the port needs to know about the car: the
screens and windows, the OEM screen-state settings, the video decoders (with a trial start at 2496×1080
and 5120×1440), Wi-Fi/Bluetooth/USB, audio outputs, input devices and the installed projection apps.
Serial numbers and addresses are left out or masked. Nothing is sent automatically.

1. Open DiPlay from the **driver-side** app list. Tap **Run EZ-60 probe**.
2. Tap **Start recording events**, then:
   - swipe DiPlay to the passenger half and back with two fingers;
   - make the voice assistant or the left vehicle panel appear and disappear;
   - press each steering-wheel button once;
   - switch between day and night (headlights or display setting);
   - plug the iPhone into each USB port.
3. Go back to **Settings → Diagnostics** and tap **Stop recording events**.
4. Open DiPlay from the **passenger-side** app list and tap **Run EZ-60 probe** again.
   Then tap **Test CarPlay window**: it opens the way the CarPlay screen does and starts recording.
   Note which half it appears on, tap it to close, and repeat from the driver side.
5. Send the report:
   - **Open on iPhone**: with the iPhone and the car on the same Wi-Fi (the car hotspot, or the car
     joined to the iPhone's Personal Hotspot), open the address shown in Safari, then Select All and
     Copy. The page closes with the report.
   - Or **Save diagnostic report** (it includes the probe), or photograph the report.
