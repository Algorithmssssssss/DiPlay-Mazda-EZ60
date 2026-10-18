# DiPlay for Mazda EZ-60 0.2.19-ez60.1 — pre-release

First pre-release of the Mazda EZ-60 fork, based on [DiPlay 0.2.19](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.19). It was tested on all three trims of the Chinese-market EZ-60. Options 1 and 2 take DiPlay's 2D HUD path; it was measured on an Option 1 car (CAMZD OS build 20250930150736) with an iPhone on iOS 27. Option 3, with the AR-HUD, was tested by testers on their own car (firmware `SWD.26.8`). Google Maps was used for navigation. It is not an Apple-certified product.

## What works

- **Wireless CarPlay.** The built-in car hotspot gave the smoothest sessions: 43–59 fps while in use, 4–5 ms to decode a frame, 21–60 ms from touch to picture. Wi-Fi Direct works on 5 GHz.
- **Wired CarPlay** over USB, after allowing the VPN once over ADB.
- **Both screen halves.** CarPlay opens on the half DiPlay was started from and follows the car's two-finger swipe without reconnecting. It narrows from 2496 to 1728 pixels wide when the car shows its left vehicle panel or voice assistant.
- **Every trim, detected by the car itself.** At each connection DiPlay reads the car's HUD type (`car_feature_hud_type`), as the car's own navigation does, and takes the 2D HUD path (Options 1 and 2) or the AR-HUD path (Option 3).
- **Windshield HUD (Options 1 and 2).** While CarPlay navigation guides, DiPlay feeds the car's navigation service the way the car's own navigation apps do. The HUD then draws its turn arrow, distance, remaining distance and time, and its 3D guidance chevrons, and shows CarPlay's map in the car's own 178×178 map box (on the left when the driver swaps the HUD panels). **HUD map view** (Far, Normal, Near) sets how much map it shows. The HUD clears when you end the route or arrive, and DiPlay steps aside if another navigation app takes the HUD. DiPlay only feeds the HUD's display; it has no path to the car's own navigation or driving assistance. While using the car's own navigation, don't start CarPlay directions at the same time.
- **AR-HUD (Option 3).** Turn-by-turn by default: the AR-HUD draws its own turn arrow, distance and arrival time from CarPlay's guidance (not in its Beyond-Class 3D mode, which hides them). **AR-HUD map (experimental)**, off by default, shows CarPlay's map in the AR-HUD's own map box while guiding out of Park, at the car's size, Large or Largest. DiPlay switches the AR-HUD's picture the way the car's own navigation does, gives it back in Park and when the route ends, steps aside when another app uses the HUD, and turns the map off for the connection on any failure. The testers reported no fault in the 360 camera or the dash camera with it.
- **Settings → Mazda EZ-60.** The car's own switches on one page, first in Settings and only on the EZ-60: turn-by-turn on the HUD, HUD map view, the AR-HUD map and its size (Option 3 only) and the ambient lights. Each can be turned off there.
- **Switching the car off.** DiPlay ends its HUD guidance, gives back the HUD's navigation focus and the AR-HUD picture, and removes its HUD window at once, as the car's own navigation does, before the head unit sleeps.
- **Picture:** 60 fps and MediaTek's low-latency decoders by default (`c2.mtk.avc.decoder.lowlatency`, and `c2.mtk.hevc.decoder.lowlatency` if HEVC is on), through upstream's MediaTek decoder tuning: if the low-latency decoder fails or stalls, the session continues on the standard one. Wi-Fi Direct starts on 5 GHz and the slow software HEVC decoder is skipped.
- **Steering-wheel keys:** previous, next and play/pause.
- **Beside the car's own Bluetooth music.** The car's Bluetooth player pauses the iPhone over Bluetooth whenever it loses audio focus, and DiPlay used to take focus back on each play or new track, so CarPlay music paused and resumed every few seconds. DiPlay now leaves focus to that player while it holds it; CarPlay audio plays without it and the wheel keys reach the iPhone through it.
- **EZ-60 probe** under **Settings → Diagnostics**, for reports about the car's screens, windows, hardware and trim. Reports mask the car's VIN.

BYD-only settings are hidden. Upstream's update check is off, because upstream releases are a different app.

## Known issues

- **The HUD's music line** shows "暂无歌词" (no lyrics). It belongs to the car's media center, which shows only lyrics; CarPlay sends none.
- **Wired CarPlay needs one ADB command**, because CAMZD OS has no VPN approval screen:
  `adb shell appops set com.reachkim.diplay.ez60 ACTIVATE_VPN allow`.
  DiPlay runs the same command itself if the car's network ADB is on and DiPlay's ADB key was approved before.
- The passenger half's CarPlay panel is assumed to mirror the driver's; it has not been measured.
- **The AR-HUD map is experimental** and off by default. With the map on the left of the AR-HUD it keeps the car's size, because where the car then draws its speed and gear has not been measured.
- Tested mainly with Google Maps guidance.

## Install

See [Install and connect](INSTALL.md). Pair the iPhone over Bluetooth, choose **Built-in car hotspot (recommended)** in **Settings → Connection → Connection setup**, and keep the iPhone's Personal Hotspot off.

## CarPlay accessory identity

The source and source builds contain no CarPlay accessory identity and cannot connect an iPhone. A build that connects needs one supplied at build time ([Build](BUILD.md)); anyone with such an APK can extract it ([SECURITY.md](../SECURITY.md)).

## Reporting problems

Reproduce the problem while parked, save a report with **Settings → Diagnostics → Save diagnostic report**, remove personal information, and attach it to an [issue](https://github.com/Algorithmssssssss/DiPlay-Mazda-EZ60/issues/new/choose).
