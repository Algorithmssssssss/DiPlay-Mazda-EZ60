# DiPlay for the Mazda EZ-60

This fork tunes DiPlay for the Chinese-market Mazda EZ-60 (Changan Mazda J90K, CAMZD OS on Android 14,
MediaTek MT8676). The car has one 26.45-inch 5K screen (5120×1440, 32:9) with a driver half and a
passenger half; a two-finger swipe moves an app between them.

It installs next to upstream DiPlay as `com.reachkim.diplay.ez60` (debug builds add `.hudtest`). The
update check in About is off, because upstream releases are a different app.

## Status

| Area | State |
| --- | --- |
| Driver panel | CarPlay fills the 2496×1080 panel at `[64,192]–[2560,1272]` and narrows to 1728×1080 when the car shows its left vehicle panel or voice assistant, without reconnecting. Geometry from the car's own apps, via [TidePlayer's measurements](https://github.com/JulyStar-Lv/TidePlayer/blob/main/docs/automotive/CAR_WINDOW_PROFILES.md). Not yet confirmed on this car. |
| Passenger half, swipe | Not handled yet. Needs the probe results below. |
| Video, Wi-Fi, USB, audio, wheel keys | Upstream behaviour. Mazda defaults follow the probe results. |

## Build

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :mobile:assembleDebug
```

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
5. Send the report:
   - **Open on iPhone**: with the iPhone and the car on the same Wi-Fi (the car hotspot, or the car
     joined to the iPhone's Personal Hotspot), open the address shown in Safari, then Select All and
     Copy. The page closes with the report.
   - Or **Save diagnostic report** (it includes the probe), or photograph the report.
