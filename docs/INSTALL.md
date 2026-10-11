# Install and connect on the Mazda EZ-60

These steps are for the Chinese-market Mazda EZ-60 (CAMZD OS, Android 14). Park the car first.

1. Download the APK from this fork's [Releases](https://github.com/Algorithmssssssss/DiPlay-Mazda-EZ60/releases).
   Check its release notes: a build without a CarPlay accessory identity cannot connect an iPhone
   (see [Build](BUILD.md)).
2. Copy the APK to the car (a USB stick and the car's file manager, a file-sharing app, or
   `adb install -r <file>.apk`) and install it there. Do not install it on the iPhone.
3. Open **DiPlay EZ-60** from the app list on the half of the screen where you want CarPlay. Grant the
   permissions it asks for: Nearby devices (Bluetooth), microphone for Siri and calls, and notifications.
4. Close other phone-projection apps, including the car's own CarPlay app, before connecting.

An update keeps its settings and pairing only when it uses the same package and signing key as the installed
app. Save any diagnostic reports you need before uninstalling.

## Wireless

1. Pair the iPhone with the car over Bluetooth.
2. Choose how the iPhone joins the car's Wi-Fi in **Settings → Connection → Connection setup**:
   - **Built-in car hotspot (recommended)**: turn the car hotspot on (5 GHz if the car offers it) and enter
     its exact name and password. This gave the smoothest picture on the test car.
   - **Wi-Fi Direct**: needs no hotspot. The EZ-60 starts on **Auto 5 GHz**.
3. Turn off the iPhone's Personal Hotspot, and keep the car off other Wi-Fi networks. Otherwise the radio
   switches channels and the picture pauses.
4. Tap **Connect** and accept CarPlay on the iPhone.

## USB

Wired CarPlay runs through an Android VPN, and the EZ-60 has no screen to approve it. Allow it once from a
computer with ADB access to the car:

```sh
adb shell appops set com.reachkim.diplay.ez60 ACTIVATE_VPN allow
```

The permission lasts until the app is uninstalled. Then connect the iPhone to a USB **data** port with a data
cable, tap **Connect with USB**, and accept Trust and CarPlay on the iPhone.

## Screen halves and the HUD

- CarPlay opens on the half you started DiPlay from. The car's two-finger swipe moves it to the other half
  without reconnecting.
- **Turn-by-turn on the HUD (experimental)**, under **Settings → Navigation → Location**, is on by default.
  While CarPlay navigation guides, the windshield HUD shows the car's own turn arrow, distance and remaining
  route, and CarPlay's map in its map area. **HUD map view** (Far, Normal, Near) sets how much map it shows.
  Changes apply at the next connection.
- While you use the car's own navigation (for example with its driving assistance), don't start CarPlay
  directions at the same time, or turn **Turn-by-turn on the HUD** off.

See [DiPlay for the Mazda EZ-60](MAZDA_EZ60.md) for what was measured on the car and the known issues.

## Picture

The EZ-60 starts at 60 fps with **Low-latency decoding (experimental)** on (**Settings → Advanced → Video and
audio**). Keep HEVC off. **Settings → Diagnostics → FPS counter** shows the frame rate over CarPlay. See
[Smooth wireless CarPlay](SMOOTH_WIRELESS.md).

## Connection recovery and reports

If wireless stops connecting after a reinstall, close other projection apps, then use **Settings → Connection
→ Wireless connection help → Reset CarPlay Wi-Fi**.

To report a problem:

1. Reproduce it on the latest release, while parked.
2. Open **Settings → Diagnostics → Save diagnostic report**. **Settings → Diagnostics → EZ-60 probe** adds
   what DiPlay sees of the car's screens and windows.
3. Review the `.txt` file and remove personal information. Never post your hotspot password.
4. Open an issue on [this fork](https://github.com/Algorithmssssssss/DiPlay-Mazda-EZ60/issues/new/choose)
   with the report, the time of the failure and the steps.
