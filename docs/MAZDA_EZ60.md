# DiPlay for the Mazda EZ-60

This fork tunes DiPlay for the Chinese-market Mazda EZ-60 (Changan Mazda J90K, CAMZD OS on Android 14,
MediaTek MT8676). The car has one 26.45-inch 5K screen (5120×1440, 32:9) with a driver half and a
passenger half; a two-finger swipe moves an app between them.

It installs next to upstream DiPlay as `com.reachkim.diplay.ez60` (debug builds add `.hudtest`). The
update check in About and upstream's background update checks are off, because upstream releases are a
different app. This fork includes upstream DiPlay through 0.2.19; BYD-only settings are hidden on the
EZ-60.

## Status

| Area | State |
| --- | --- |
| Driver panel | CarPlay fills the 2496×1080 panel at `[64,192]–[2560,1272]` and narrows to 1728×1080 when the car shows its left vehicle panel or voice assistant, without reconnecting. Geometry from the car's own apps, via [TidePlayer's measurements](https://github.com/JulyStar-Lv/TidePlayer/blob/main/docs/automotive/CAR_WINDOW_PROFILES.md). |
| Passenger half | The panel mirrors the driver's: `[0,192]–[2496,1272]` in the window, 1728 wide while the right voice assistant shows. This mirroring is assumed, not measured. |
| Two-finger swipe | Moves the window between halves without resizing or relaunching it, so CarPlay keeps its session; the panel follows the new half. The CarPlay screen shares the DiPlay menu's task, so both move together. |
| Opening on the launch half | A new task started from DiPlay opens on DiPlay's half (tested on both halves), and the CarPlay screen now opens inside DiPlay's own task. Opening DiPlay from the other half's app list moves it there. |
| Video | H.264 at 2496×1080, 60 fps by default on the EZ-60, through `c2.mtk.avc.decoder.lowlatency` with its own output thread. Best measured so far (built-in car hotspot, 2026-10-11): 43–59 fps while in use, decode 4–5 ms (p90 7–11 ms), touch to picture 21–60 ms, up to 33 Mbps. Earlier, Wi-Fi Direct with the car joined to the iPhone's hotspot gave 23–35 fps and pauses of up to 1.5 s. |
| Wheel keys | Previous, next and play/pause arrive as standard media keys. |
| USB | An iPhone on the USB port appears as a USB data device (Apple `05ac:12a8`, 12 interfaces). Wired CarPlay runs through an Android VPN, and CAMZD OS has no VPN consent screen (`com.android.vpndialogs` is missing), so the VPN must be allowed once over ADB; see below. |
| Wireless | Works over the built-in car hotspot (smoothest) and Wi-Fi Direct; see Smooth wireless below. |
| Windshield HUD | Turn arrows, distance, remaining route and CarPlay's map, confirmed with Google Maps; see below. |
| Audio | Upstream behaviour; see Known issues for the car's own Bluetooth music. |

## Measured on the car

From the EZ-60 probe on 2026-10-10 (CAMZD OS build 20250930150736):

- **Platform:** Wutong/Tinnove J90K, Android 14 (Android Automotive), MediaTek MT8676.
- **Screen:** one 5120×1440 display with one touch panel. DiPlay's window is 2560×1440 on either half: the left half belongs to the car's left task group, the right half to the "copilot" group. A 160 px status bar is at the top and a 136 px dock at the bottom of each half. The system reports 160 dpi, which is nominal; the panel is about 0.126 mm per pixel.
- **Swipe:** `global.fly_to_right_status` goes 1 (to the passenger half) or 2 (back), the window moves within about 0.2 s, and `copilot_display_status` / `main_display_status` name the app on each half. The app is not relaunched; it gets a configuration change with the new bounds.
- **New tasks:** a separate task started from DiPlay opens on DiPlay's half. Launching DiPlay from the other half's app list moves its task to that half.
- **Video decoders:** `c2.mtk.avc.decoder` (no low-latency mode) and `c2.mtk.avc.decoder.lowlatency`. H.264 at 2496×1080 is good for 87–191 fps, HEVC for 31–70 fps. No decoder accepts 5120 wide (the limit is 4096).
- **Wi-Fi:** 5 GHz and 6 GHz, Wi-Fi Direct, hotspot and client at the same time. The hotspot interface is `ap0`, or `ap_br_ap0` when the hotspot uses two bands.
- **Audio:** Android Automotive buses for media, navigation, calls, ringtone and voice per seat; the car sets the volume.
- **Call microphone:** Siri hears the driver through CarPlay, but the other side of a Messenger call heard nothing.
  The call's capture of the built-in microphone was silenced by Android itself (report 22: `silenced=true`,
  `peak=0`), with the car's Bluetooth off, while Siri's `VOICE_RECOGNITION` capture of the same microphone got
  real audio (`peak=4779`). The car keeps its own recordings running (`CAMCORDER`, `VOICE_RECOGNITION`,
  `VOICE_PERFORMANCE`). On the EZ-60, DiPlay now records calls with `VOICE_RECOGNITION`, outside Android's
  communication mode and without the platform voice effects. If Android still silences it, DiPlay restarts the
  capture with `VOICE_COMMUNICATION`, then `MIC`, and logs "Microphone: silenced by Android with source=…". The
  report's "Calls and microphone" section keeps these lines. If the other side hears an echo once calls work,
  turn on **Settings → Advanced → Video and audio → Call echo cancellation (experimental)**.
- **Bluetooth:** hands-free and music receiver only.
- **Windshield HUD:** an 800×480 presentation display, `MeterDisplay#0`; the car has no instrument cluster.
- **Also installed:** `com.autochips.carplayapp`, a CarPlay app from the head unit's maker.

## Connect

### Wired: allow the VPN once

DiPlay's wired CarPlay needs Android's VPN permission. The car cannot show the screen that grants it,
so allow it once from a computer with ADB access to the car:

```sh
adb shell appops set com.reachkim.diplay.ez60.hudtest ACTIVATE_VPN allow
```

Use `com.reachkim.diplay.ez60` for a release build. The permission lasts until the app is uninstalled;
installing a new build over it keeps it. Since upstream 0.2.18, DiPlay runs this command itself when the
VPN screen is missing, but only if the car's network ADB is on and DiPlay's ADB key was approved before;
the session log then shows `VPN consent over adb access=READY granted=true`.

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
  thread, and the main screen tries `c2.mtk.avc.decoder.lowlatency` first. **MTK decoder low latency**
  (upstream #608, Advanced) is also on by default on the EZ-60: it picks the same H.264 decoder, picks
  `c2.mtk.hevc.decoder.lowlatency` for HEVC, and if the low-latency decoder fails or stalls mid-session it
  moves to the standard decoder until the next connection (log: `MTK Codec2 low-latency decoder dropped`).
  Its legacy `OMX.MTK` keys are never used here: on the EZ-60 those names are only aliases of the Codec2
  decoders. **Direct video output**
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
- **Car hotspot on two bands:** in the smooth sessions the hotspot interface was `ap0`. In later ones
  (2026-10-11 evening) it was `ap_br_ap0`, which Android creates for a hotspot set to 2.4 and 5 GHz together.
  At the same time, video reached the car with gaps of about 230 ms instead of 70–95 ms, touch to picture
  rose from 20–33 ms to 50–150 ms, and the iPhone lowered its bitrate. The cause is not confirmed yet. If
  the session log shows `hotspot interface confirmed iface=ap_br_ap0`, set the car hotspot to 5 GHz only
  (if the car offers a choice), keep the car off other Wi-Fi networks, and turn the hotspot off and on.

## Windshield HUD

The EZ-60 has no instrument cluster; the windshield HUD is the driver's only other display. Its navigation
comes from the head unit's navigation service, `com.incall.double.INTERACTIVE_SERVICE`
(`IDouInteractiveManager`, reached through `com.incall.SVR_MNG_SERVICE`), which the car's navigation apps
feed. A window on its 800×480 presentation display (`MeterDisplay#0`) is projected only while an app holds
that service's navigation focus.

The EZ-60's own switches are together on **Settings → Mazda EZ-60** (shown on the EZ-60 only, first in the
list), so they are quick to find and turn off.

**Turn-by-turn on the HUD (experimental)** (**Settings → Mazda EZ-60 → Windshield HUD**, on by
default) feeds it with CarPlay's route guidance, which Google Maps and Apple Maps both send:

| Step | Call |
|---|---|
| Guidance starts | `requestNaviFocus(package, callback)`, then `sendNavigateStatus(2)` |
| Each update | `sendNavigateRoadInfo(current, next)` when the roads change, `sendNavigateTurnInfo(turnId, metres)`, `sendNavigateRemainInfo(metres, seconds)`, `sendNavigatePercent(driven %)` when it changes: a new turn or road at once, other changes at most once a second |
| Guidance ends | remaining 0/0, empty roads, turn 0/0, `sendNavigateStatus(1)` (not navigating), `abandonNaviFocus` |

Guidance ends at once when the iPhone reports arrival, 5 seconds after it reports no route (the driver ended it)
unless a route follows, or when it sends no guidance for 30 seconds; it resends guidance about every 2 seconds
while a route runs, even parked. The HUD has its own route feed for this: upstream's dashboard turn card keeps
its turn across a wireless reconnect, and kept the HUD guiding after a route ended (fixed 2026-10-11).

The 5 seconds matter when CarPlay connects mid-drive. Google Maps then resumes its route with bursts of no
route, loading and route set, up to 2 seconds apart, and its route-set updates do not repeat the current turn.
Ending guidance on each "no route" dropped the navigation focus, and with it the HUD's map, until the next turn
began, up to two minutes later (report 23, 2026-10-13).

While DiPlay holds navigation focus, the HUD also projects the (573,167)–(800,355) area, 227×188 pixels, of
its 800×480 display (`MeterDisplay#0`); confirmed with the probe's test card on 2026-10-11. The HUD draws the
turn arrow, distance and remaining route itself; DiPlay's window fills that area with CarPlay's
instrument-cluster map (requested at 232×192 with the car marker centred; Google Maps sends it, confirmed
2026-10-11), or with a turn card while the iPhone sends no map. The HUD switch also turns the map off; the
change applies at the next connection. **HUD map view** (same card: Far, Normal, Near) sets how much map
shows: Normal asks the iPhone for a stream at the area's size (232×192), Far for 150 % (336×280, scaled down:
more streets, smaller) and Near for 75 % (176×144, scaled up). It applies at the next connection.

**Safety.** DiPlay talks only to the HUD's display service (`IDouInteractiveManager`); it writes no vehicle
properties and has no path to the car's driver assistance. It sends only while CarPlay navigation guides,
asks for the HUD's navigation focus once per route, and stops sending at once if the service says another
navigation app took the HUD (`HUD: another navigation app took the HUD`); it then leaves that app's guidance
on the HUD when its own route ends. The car's built-in navigation does not use that focus, so DiPlay cannot
yet tell when it is guiding: while using the car's own navigation (for example with its driving assistance),
do not start CarPlay directions, or turn **Turn-by-turn on the HUD** off.

Only these navigation calls are made, from a background thread, and the session log records each step
(`HUD: ...`). Turn icons, status values and route progress follow the car's own navigation app
(`WT_TinnoveNavigation`): 3 destination, 16 straight, 17/18 slight or keep right/left, 19 left, 20 right,
21/22 sharp left/right, 23/24 U-turn left/right. Roundabouts have a set per direction: counter-clockwise
(right-hand traffic) 53 enter, 42–51 exit 1–10, 63 leave; clockwise (left-hand traffic) 70 enter, 74–83 exit
1–10, 68 leave. With these the HUD draws its own arrow beside the distance and its 3D guidance chevrons (16,
19 and 3 confirmed with Google Maps on 2026-10-11). No vehicle properties are written.

The car's navigation also sends lane guidance (`sendNavigateLaneInfo`, JSON `{"laneNum":n,"lane1":code,…}`),
speed cameras (`sendNavigateCameraInfo`, JSON `{"nearestDistance","type","cameraSpeed"}`), junction views
and 3D map data. DiPlay does not send these: CarPlay gives accessories no speed cameras, and DiPlay does not
read CarPlay's lane guidance yet.

The HUD's music line (top left, "暂无歌词" when there are no lyrics) belongs to the car's media center. It
shows only lyrics, which CarPlay does not send, so DiPlay leaves it alone in this release.

### When the car is switched off

The head unit writes `vehicle_acc_status=2` (Settings.Global) when the car is switched off, then force-stops
DiPlay within about a second and sleeps (report of 2026-10-14: the flag at 17:27:00.2, DiPlay's last log line at
17:27:01.0, a `user_requested` process exit, a new process after the car started at 17:33). Nothing DiPlay runs
at the end of a session gets the chance to run.

The car's own navigation ends its guidance on that change (`AccObserver.exitGuide`, "exit guidance caused by an
ACC change"), which also takes it off the HUD. DiPlay now does the same from its own thread
(`MazdaPowerWatch`): it clears the HUD, sends "not navigating", gives the navigation focus back and gives the
AR-HUD video link back, and starts nothing on the HUD again until the car is on. Before this, a CarPlay route
still guiding at switch-off left DiPlay's navigation state and focus on the car's HUD service, held by a process
that no longer existed.

Why it matters: another developer saw the 360 camera app crash on an AR-HUD car after leaving navigation on
the HUD and locking the car for 5 minutes. Leaving no stale navigation state behind is what the car's own
navigation does, but it is not shown to be the cause. The decompiled 360 camera (`com.tinnove.evs` 3.3.1) and
dash camera (`com.incall.dvr`, build of 2025-12-04) apps never call the HUD service's navigation, focus,
video-link or camera calls, and never look at `MeterDisplay#0`. The dash camera bundles the service's SDK but
uses it only for phone-call state, the network, power and the telematics box (sentinel mode). The 360 camera
app ends its own process in three cases, which can look like a crash:

- the car service is not ready when it connects (`CarSignalManager`), for example just after waking;
- the previous camera screen takes more than 2 s to close when the camera opens again (`AvmDisPlayController`);
- its configuration (car model, camera features) changes while it runs (`ConfigurationManager`).

Finding the cause needs the crash itself from the car: the crash log buffer and the system's crash records.

The session log shows "Car power: switched off …" and the HUD lines that follow. If a run is ever stopped while
it guides on the HUD, the next run logs "HUD: the last run was stopped while guiding on the HUD".

Test on an AR-HUD car: start a CarPlay route so DiPlay guides on the HUD, switch the car off and lock it with
the iPhone still in the car, wait 5 minutes, unlock, start the car and check the 360 camera (camera button), then
send the probe report.

### Trims: 2D HUD and AR-HUD

The car's own navigation (WT_TinnoveNavigation, J90K build of 2025-09-29) supports two HUDs and tells them
apart by the car's feature configuration: service `tinnove_config`, `IConfigInterface.getValue`
(call 3), key `car_feature_hud_type`. A value of 3 is the 3D AR-HUD; any other value, or none, is the 2D HUD.

| | 2D HUD (the test car) | AR-HUD (`car_feature_hud_type=3`) |
| --- | --- | --- |
| HUD picture | the projected part of `MeterDisplay#0`, while an app holds navigation focus | a video link the head unit switches: `setVideoStatus(5)` to show, `6` to hide (calls 72–74), confirmed by `onRequestLVDS(19)` / `onIPStatus(6)` and `sendSwitchLVDSFinishEvent` |
| Car's own map on the HUD | shown when its guidance starts | shown only while driving (not in P), in one of five layouts the car's HUD settings choose |
| Turn, road and remaining distance | the navigation calls in the table above | the same calls |

Both trims use the same `IDouInteractiveManager` call numbers that DiPlay uses. DiPlay reads the HUD type
(read-only) at each connection and follows the same rule:

- 2D HUD: as described above.
- AR-HUD: turn-by-turn only by default. DiPlay opens no window on `MeterDisplay#0`, does not ask the iPhone for
  the map stream and never switches the video link. One video decoder fewer also leaves more of the MediaTek
  video hardware to the car's cameras and DVR. A photo from an AR-HUD car showed that the AR-HUD draws nothing
  from the turn calls alone (only its own speed, gear, range and music line), at least while stopped.
- AR-HUD with **AR-HUD map (experimental)** (**Settings → Mazda EZ-60 → Windshield HUD**, AR-HUD cars only,
  off by default; turning it on applies at the next connection, turning it off removes the map at once): DiPlay
  shows CarPlay's map, or a turn card (arrow, distance, road) when the iPhone sends no map, in the car's own map
  box, as the car's navigation does:

  | | The car's navigation | DiPlay |
  |---|---|---|
  | When | guiding, not in Park, HUD mode with a map | CarPlay guiding with the HUD's navigation focus, not in Park, HUD mode with a map |
  | Readings (read-only VirtualCar properties) | gear `826278449` (4 = P; 8 and 2 seen while driving and reversing), HUD mode `826279576`, map layout `826279579` | the same, once a second while guiding; a failed reading counts as Park |
  | HUD mode → map | 3 (custom) becomes 5 with map layout 1, else 4; then 0 off and 2 easy show no map, 5 shows it left, 1 (classic), 4, 6 and any other value show it right (`ARHudManager.x`, `.u`) | the same (a tester's car started in mode 5) |
  | Map box on `MeterDisplay#0` (4032×845) | 390×350 at (1640,161); at (70,161) in custom mode with the left layout; none in easy mode | the same, or larger with **AR-HUD map size** (Large 125 %, Largest 145 %): the bottom edge and the outer edge stay, the box grows upwards and towards the middle; the stream is asked for at the box size (× the HUD map view), at 60 fps (Google Maps sent the 2D HUD's map at about 16 fps when asked for 30) |
  | Show | `setVideoStatus(5)`; on `onRequestLVDS(19)` show the window and `sendSwitchLVDSFinishEvent(0)`; on `20` ask again after 200 ms, at most 8 times | the same, with its own `IDoubleVideoCallback` (calls 72, 73, 74 and 1 only) |
  | Hide | `setVideoStatus(6)`; on `onIPStatus(6)` remove the window and confirm | the same, when the route ends, in Park, in a mode without a map, or when the connection closes |

  DiPlay only hides a link it switched on itself. A "link on" reply it did not ask for (more than 2 s after its
  own request) means another app took the link, such as a HUD video player or the car's own navigation guiding:
  DiPlay removes its window, leaves the link alone and waits for the next route. A "link off" it did not ask for
  only means the link is off: the car's own navigation hides it on every shift into Park, guiding or not, usually
  before DiPlay's once-a-second reading sees the gear (first tester report: the map stayed off after D → P → D).
  DiPlay removes its window and asks again 3 s later once the car is out of Park, at most 6 times a route.
  Whether this connection asked for the AR-HUD map, and at which size, belongs to the running CarPlay session: when
  CarPlay opens the car's home screen, DiPlay's CarPlay screen closes, and the next one adopts the session and
  shows the map again (first tester report: the map did not come back). The same goes for which output stream 111
  belongs to on every EZ-60 HUD and BYD cluster: a screen that adopted the session gave it the main surface. No reply within 3 s,
  or 8 declined requests, turns the map off until the next connection. DiPlay cannot tell when the car's own
  navigation was already guiding before CarPlay's route started: do not use both at once. Each step is logged
  as "HUD: AR map …" and "HUD: AR-HUD state gear=… hudMode=… mapLayout=…", and the probe's "Car features"
  section shows the current readings.

The session log starts with a "Car features:" line, and the EZ-60 probe adds a "Car features (read-only)"
section: HUD type, screen, Ethernet, audio architecture, speakers, cameras and the other per-trim switches, so
reports from different trims can be compared.

First AR-HUD report (an "Option 3" car, 2026-10-16): `car_feature_hud_type=3`, and DiPlay took the AR-HUD path
at every connection with no crash. Compared with the 2D HUD test car:

| | 2D HUD test car | AR-HUD car |
| --- | --- | --- |
| Firmware | SWD.83.9 (build of 2025-09-30) | SWD.26.8 (build of 2025-12-31) |
| `MeterDisplay#0` | 800×480 | 4032×845 |
| Extra Ethernet links | — | `eth1`, `eth2` (`car_feature_ethernet=1`) |
| Bluetooth stack | `BT_MT8676_v0.0.27SZ` | `BT_J90K_v0.0.34SZ` |

Screen, decoders, audio buses, inputs and the hotspot interface (`ap0`) are the same. On that car the AirPlay
Bonjour service took 10–20 s to start: JmDNS reverse-resolved each hotspot address over a network that did
not answer. DiPlay now names the addresses itself, so nothing is resolved.

Second AR-HUD report (2026-10-17): the Bonjour service started at once. After failed attempts in which the
iPhone did not connect, a new connection succeeded within 1.4 s of the start request and ran for 11 minutes or
more, at about 32 fps with 0.3–0.5 s arrival gaps. With a Google Maps route, the AR-HUD car granted DiPlay
navigation focus and accepted the turn call. Whether the AR-HUD drew anything has not been reported yet.

## Ambient lights following CarPlay music (experimental)

**Settings → Mazda EZ-60 → Ambient lighting → Follow CarPlay music** (off by default). The car's own
music sync runs inside its media center and only for that app's own player, so CarPlay music never reached the
lights (report 17). With the switch on, DiPlay does what the media center does, through the same car adapter
(`wt.atom`, `IAtomInterface.setValue`, priority 200):

| Step | Writes |
|---|---|
| Music starts | light zones `[-1,-1,-1]`, scene mode `826278772` = 2 (music), rhythm colour `826278822` (area 1) = the car's colour |
| While it plays | rhythm brightness `826278823`, 5–100, about 8 times a second, only when it changes; zones and scene mode again each second; the colour steps through the colour bar in the car's mode 0 |
| Music stops | scene mode 1, zones `[0,0,0]`, rhythm colour and brightness back to the car's settings |

How the brightness follows the music is set on the same card (applies at once):

- **Light effect · Rhythm** (default): the light rises and falls with the music's loudness, smoothed.
  **Beat**: it jumps to full on each bass kick (40–180 Hz clearly above its recent average, at most one per
  250 ms) and fades until the next.
- **Smoothness** 1–5 (default 3): how fast the light rises and fades.
- **Depth** 10–100 % (default 60 %): how far the light dims below full; at 30 % it never goes below 70.
- **Beat sensitivity** 1–5 (default 3, Beat only): how small a bass kick counts as a beat.

The first version set the brightness straight from each 120 ms loudness sample, which flickered (report 18).

It runs only while the car's ambient lights are on with their music sync on (`chinatsp.global.LIGHT_RHYTHM_CTL`
= 1, brightness not 0), CarPlay music is playing, and CarPlay's music has the car's focus (or the car's
Bluetooth player has it, which runs no light sync). Safety:

- Only these four properties, with the media center's values, can be written; anything else is refused in code.
- After switching music mode on, DiPlay reads the scene mode back; if the car did not enter music mode, or any
  write fails, it hands the lights back and stays off until the next CarPlay connection
  (`Ambient: stopped: …`).
- If the car or its media center takes the lights back, DiPlay stops writing until the music starts again.
- If DiPlay stops in the middle of a song, it hands the lights back the next time it starts.
- The music level is read from audio DiPlay already plays; no microphone.

## Known issues

- **Fixed: music pausing and playing by itself.** When the iPhone is also connected to the car's own Bluetooth music,
  the car's Bluetooth player takes audio focus as the iPhone plays (`bt_source_focus_key` = 1), and it pauses
  the iPhone over Bluetooth whenever it loses focus. DiPlay used to take focus back on each play or new track,
  so CarPlay music paused and resumed every few seconds (report 16: a new track at 04:37:15.98, the iPhone's
  music stopped at 04:37:16.47). DiPlay now leaves focus to that player while it holds it; CarPlay audio plays
  without focus, and the wheel keys reach the iPhone through it. Confirmed on the car on 2026-10-11.
- **Wired CarPlay needs one ADB command** (see Connect above).
- **Passenger half:** CarPlay's panel there is assumed to mirror the driver's; it has not been measured.

## Build

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :mobile:assembleDebug
```

Add `-Pez60.variant=next` to build `com.reachkim.diplay.ez60.next`, labelled "DiPlay EZ-60", which installs
next to the "DiPlay EZ-60 Test" app instead of over it. Its settings, paired iPhone and VPN permission are its
own.

The APK is `mobile/build/outputs/apk/debug/mobile-debug.apk`. It has no CarPlay accessory identity, so
it cannot connect an iPhone; it is enough for the probe. To build one that connects, see
[Build a standalone car-test APK](BUILD.md#build-a-standalone-car-test-apk).

## EZ-60 probe

**Settings → Diagnostics → EZ-60 probe** records what the port needs to know about the car: the
screens and windows, the OEM screen-state settings, the video decoders (with a trial start at 2496×1080
and 5120×1440), Wi-Fi/Bluetooth/USB, audio outputs, input devices and the installed projection apps.
Serial numbers and addresses are left out or masked. Nothing is sent automatically.

On the EZ-60 the snapshot also has **Ambient lighting (read-only)**: the car media center's music-sync switches
(`chinatsp.global.LIGHT_RHYTHM_CTL`, `LIGHT_RHYTHM_MODE`, `LIGHT_COLORBAR_CTL`, `LIGHT_BRIGHTNESS_CTL`) and the
ambient-light vehicle properties it uses (826278810 brightness, 826278811 colour, 826278822 rhythm colour,
826278823 rhythm brightness, 826278772 scene mode, 829424549 light zones), read through the car's
vehicle-property service with `getValue` and `isSupport` only. While recording, those properties are read once
a second and changes are noted as `[ambient]`. Nothing is written; this decides whether DiPlay could sync the
lights to CarPlay music, which the car's own music sync does only for the media center's own player.

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
