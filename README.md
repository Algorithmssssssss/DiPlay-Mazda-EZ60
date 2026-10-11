# DiPlay for Mazda EZ-60

**CarPlay for the Chinese-market Mazda EZ-60** (Changan Mazda J90K, CAMZD OS on Android 14, MediaTek MT8676), wired and wireless, on the car's 5K split screen and windshield HUD. A fork of [DiPlay](https://github.com/shihabal3amri/DiPlay). App: `com.reachkim.diplay.ez60`, installs beside upstream DiPlay.

> **Pre-release.** Tested on one EZ-60 with an iPhone on iOS 27. It is not an Apple-certified product, and it is not affiliated with or endorsed by Apple, Mazda, Changan or Tinnove.

[Releases](https://github.com/Algorithmssssssss/DiPlay-Mazda-EZ60/releases) · [Install and connect](docs/INSTALL.md) · [Report a problem](https://github.com/Algorithmssssssss/DiPlay-Mazda-EZ60/issues/new/choose) · [中文](README.zh-CN.md)

## What works in 0.2.17-ez60.1

- **Wireless CarPlay** over the car's built-in hotspot (smoothest: 43–59 fps while in use, 4–5 ms per frame to decode, 21–60 ms from touch to picture) or Wi-Fi Direct on 5 GHz.
- **Wired CarPlay** over USB, after one ADB command (the car has no VPN approval screen).
- **Both screen halves.** CarPlay opens on the half you start it from, follows the car's two-finger swipe to the other half without reconnecting, and narrows when the car shows its vehicle panel or voice assistant.
- **Windshield HUD.** While CarPlay navigation guides, the car's HUD draws its own turn arrow, distance and remaining route, and shows CarPlay's map in its map area; it clears when you end the route or arrive. Confirmed with Google Maps.
- **Picture tuned for the MT8676:** 60 fps and the MediaTek low-latency H.264 decoder by default.
- Steering-wheel previous, next and play/pause.
- **Works beside the car's own Bluetooth music:** DiPlay leaves audio focus to the car's Bluetooth player, so the car no longer pauses the iPhone when CarPlay plays.
- BYD-only settings are hidden; everything else from DiPlay 0.2.17 is included.

## Known issues

- **The HUD's music line** shows "暂无歌词" (no lyrics). It is the car's lyrics line, and CarPlay sends no lyrics.
- The passenger half's CarPlay panel is assumed to mirror the driver's; it has not been measured.

Details: [DiPlay for the Mazda EZ-60](docs/MAZDA_EZ60.md).

## CarPlay accessory identity

The source code and source builds contain no CarPlay accessory identity, so they cannot connect an iPhone. A build that connects needs one supplied at build time; see [Build](docs/BUILD.md). Each release's notes say whether its APK contains one. Anyone with such an APK can extract its identity; see [SECURITY.md](SECURITY.md).

## Documentation

- [Install and connect](docs/INSTALL.md)
- [DiPlay for the Mazda EZ-60](docs/MAZDA_EZ60.md): measurements, HUD, known issues and the EZ-60 probe
- [Smooth wireless CarPlay](docs/SMOOTH_WIRELESS.md)
- [Privacy and diagnostic reports](docs/PRIVACY.md)
- [Build from source](docs/BUILD.md)
- [Changes](CHANGELOG.md)
- [Credits and licenses](docs/THIRD_PARTY_NOTICES.md)

Other files in `docs/` describe DiPlay features inherited from upstream.

## Source and credits

Based on [DiPlay](https://github.com/shihabal3amri/DiPlay), which is based on [xcertplay](https://github.com/shilapi/xcertplay) (GPL-3.0). The home and settings UI adapts [DiAuto](https://github.com/shihabal3amri/DiAuto) (AGPL-3.0); that license is in `docs/licenses`. The first EZ-60 cockpit port (view-area switching on the 5K screen) comes from [JulyStar-Lv's DiPlay fork](https://github.com/JulyStar-Lv/DiPlay), and its geometry follows measurements published by [TidePlayer](https://github.com/JulyStar-Lv/TidePlayer). Keep these notices when you distribute modifications. CarPlay and its icon belong to Apple Inc.
