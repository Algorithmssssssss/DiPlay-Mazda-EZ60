# DiPlay for Mazda EZ-60 0.2.17-ez60.1 — pre-release

First pre-release of the Mazda EZ-60 fork, based on [DiPlay 0.2.17](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.17). It was tested on one Chinese-market EZ-60 (CAMZD OS build 20250930150736) with an iPhone on iOS 27, using Google Maps for navigation. It is not an Apple-certified product.

## What works

- **Wireless CarPlay.** The built-in car hotspot gave the smoothest sessions: 43–59 fps while in use, 4–5 ms to decode a frame, 21–60 ms from touch to picture. Wi-Fi Direct works on 5 GHz.
- **Wired CarPlay** over USB, after allowing the VPN once over ADB.
- **Both screen halves.** CarPlay opens on the half DiPlay was started from and follows the car's two-finger swipe without reconnecting. It narrows from 2496 to 1728 pixels wide when the car shows its left vehicle panel or voice assistant.
- **Windshield HUD.** While CarPlay navigation guides, DiPlay feeds the car's navigation service the way the car's own navigation apps do. The HUD then draws its turn arrow, distance, remaining distance and time, and its 3D guidance chevrons, and shows CarPlay's map in its map area.
- **Picture:** 60 fps and the MediaTek low-latency H.264 decoder by default; Wi-Fi Direct starts on 5 GHz and the slow software HEVC decoder is skipped.
- **Steering-wheel keys:** previous, next and play/pause.
- **Beside the car's own Bluetooth music.** The car's Bluetooth player pauses the iPhone over Bluetooth whenever it loses audio focus, and DiPlay used to take focus back on each play or new track, so CarPlay music paused and resumed every few seconds. DiPlay now leaves focus to that player while it holds it; CarPlay audio plays without it and the wheel keys reach the iPhone through it.
- **EZ-60 probe** under **Settings → Diagnostics**, for reports about the car's screens, windows and hardware.

BYD-only settings are hidden. Upstream's update check is off, because upstream releases are a different app.

## Known issues

- **The HUD's music line** shows "暂无歌词" (no lyrics). It belongs to the car's media center, which shows only lyrics; CarPlay sends none.
- **Wired CarPlay needs one ADB command**, because CAMZD OS has no VPN approval screen:
  `adb shell appops set com.reachkim.diplay.ez60 ACTIVATE_VPN allow`
- The passenger half's CarPlay panel is assumed to mirror the driver's; it has not been measured.
- Tested only with Google Maps guidance and one iPhone.

## Install

See [Install and connect](INSTALL.md). Pair the iPhone over Bluetooth, choose **Built-in car hotspot (recommended)** in **Settings → Connection → Connection setup**, and keep the iPhone's Personal Hotspot off.

## CarPlay accessory identity

The source and source builds contain no CarPlay accessory identity and cannot connect an iPhone. A build that connects needs one supplied at build time ([Build](BUILD.md)); anyone with such an APK can extract it ([SECURITY.md](../SECURITY.md)).

## Reporting problems

Reproduce the problem while parked, save a report with **Settings → Diagnostics → Save diagnostic report**, remove personal information, and attach it to an [issue](https://github.com/Algorithmssssssss/DiPlay-Mazda-EZ60/issues/new/choose).
