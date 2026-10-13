# EZ-60 emulator

An Android emulator for the Mac that copies the Mazda EZ-60 head unit's screen, HUD display and model name.
Use it to try DiPlay's screens without going to the car.

It does not copy the whole car. The car's own system (CAMZD OS) is not available, so the emulator runs Google's Android 14.
The script then changes that system to match what the EZ-60 probe measured.
**CarPlay cannot connect to it** (see [What it can't do](#what-it-cant-do)).

> Status: `ez60-emu` was written on 2026-10-13 and tested against a simulated `adb` only.
> It has not booted a real emulator yet. If a step fails, the error says which one; please report it.

## What it copies

| | In the car | In the emulator |
| --- | --- | --- |
| Android | 14 (CAMZD OS, Android Automotive) | 14 (Google APIs image, not Automotive) |
| Model | `J90K` | `J90K`, set by `setup`, so DiPlay turns on its EZ-60 defaults |
| Screen | 5120×1440, density 160, driver and passenger halves | same size and density, with DiPlay in a 2560×1440 window on one half |
| Two-finger swipe | moves DiPlay's window to the other half | `ez60-emu swipe` moves the window |
| HUD | 800×480 presentation display; the car projects only (573,167)–(800,355) | 800×480 second display, shown whole |
| Vehicle panel, voice assistant | the car writes `key_screen_show`, `key_vpa_cui_show_left/right` | `panel` and `assistant` write the same keys |
| Steering-wheel keys | media previous, next, play/pause | `key prev`, `key next`, `key play` |
| System bars | 160 px top bar, 136 px bottom gesture area | Android's own thinner bars |

## What it can't do

- **CarPlay.** The emulator cannot connect to an iPhone, so there is no CarPlay video, sound, touch, Siri, calls, HUD guidance or song data.
  - Wired CarPlay needs the iPhone's USB connection, and the Mac emulator cannot pass a USB device through.
  - Wireless CarPlay needs the car's Bluetooth and Wi-Fi, and the emulator only simulates both.
- **Speed.** The car's MediaTek video decoders are missing, and the Mac is much faster than the car. Measure lag and frame rate in the car.
- **The car's services.** The HUD feed (`com.incall.SVR_MNG_SERVICE`) and the ambient lights (`virtualcar_service`, `wt.atom`) are missing.
  DiPlay logs that they are unavailable and continues.
- **The car's own apps.** The car's launcher, its split-screen tasks and its native CarPlay app are missing.

## What it is good for

- Settings screens at the car's real size and density: layout, scrolling, search and every language, including Arabic (right to left).
- Large-font screenshots of layout changes, which [AGENTS.md](../../AGENTS.md) asks for (`ez60-emu font 1.3`).
- EZ-60-only options and defaults, which appear because the model is `J90K`.
- DiPlay's cockpit frame on the driver and passenger halves, and how it narrows when the vehicle panel or voice assistant opens.
- Diagnostics → EZ-60 probe: **Test CarPlay window**, the HUD test card, and the probe report (which describes the emulator, not the car).
- Crashes and broken screens, before you install a build in the car.

## Before you start

- Android Studio. Its SDK already has the emulator, `adb` and the command-line tools.
- Disk space. The Android 14 image is a 1.6 GB download, and the emulator needs 6–8 GB in total.
  Run `./ez60-emu doctor` to see how much space is free.
- Memory. The emulator uses 4 GB of RAM while it runs.
- An APK. Any debug build works, for example from `./gradlew :mobile:assembleDebug` (the APK is `mobile/build/outputs/apk/debug/mobile-debug.apk`).
  The emulator can't run CarPlay, so the build needs no CarPlay identity files.

## First time

Run these from this folder (`tools/ez60-emulator`):

```bash
./ez60-emu create
```

`create` asks before it downloads the image. Then:

```bash
./ez60-emu start
```

```bash
./ez60-emu setup
```

`setup` runs once. It sets the model name, turns on windows, checks the HUD display and restarts Android.
Then install DiPlay and open it on the driver half:

```bash
./ez60-emu install /path/to/DiPlay.apk
```

```bash
./ez60-emu launch
```

## Every day

1. Start the emulator with `./ez60-emu start`.
   Do not use the ▶ button in Android Studio's Device Manager. It starts the emulator without its changed system partition.
   The model is then no longer `J90K`, and DiPlay acts like a phone app.
2. In Android Studio, choose **DiPlay EZ-60 (J90K)** in the device menu and press Run.
   Logcat, the debugger and Layout Inspector work as usual.
3. Android Studio opens DiPlay across the whole screen. Run `./ez60-emu launch` to reopen it in a window on the driver half.
   Alternatively, add `--windowingMode 5` to **Launch Flags** in the run configuration (Run → Edit Configurations → mobile → General).
   Then run `./ez60-emu half driver` after each Run.

## Commands

| Command | What it does |
| --- | --- |
| `create [--yes]` | Downloads the Android 14 image (asks first) and creates the `DiPlay_EZ60` emulator |
| `start` | Starts the emulator with its writable system. A full start takes about a minute. |
| `setup` | First time only: model `J90K`, windows, HUD display, the car's settings keys, screen always on |
| `install <apk>` | Installs or updates a DiPlay APK |
| `launch [driver\|passenger]` | Opens DiPlay in a 2560×1440 window on one half (default: driver) |
| `half driver\|passenger` | Moves DiPlay's windows to that half |
| `swipe` | Moves DiPlay to the other half, like the car's two-finger swipe |
| `panel on\|off` | Opens or closes the car's vehicle panel on the driver half |
| `assistant driver\|passenger on\|off` | Opens or closes the car's voice assistant panel on one half |
| `bt-music on\|off` | Pretends the car's Bluetooth music player holds audio focus (`bt_source_focus_key`) |
| `key next\|prev\|play\|voice\|back\|home\|<keycode>` | Presses a steering-wheel or system key |
| `night on\|off\|auto` | Switches between night and day mode |
| `font <scale>` | Sets the font scale, for example `1.3` |
| `status` | Shows the model, the displays, DiPlay's windows and the car's settings keys |
| `screenshot [file.png]` | Saves the whole 5120×1440 screen |
| `doctor` | Checks the Android tools and the free disk space |
| `stop` | Shuts the emulator down |
| `delete [--yes]` | Deletes the emulator. The downloaded image stays. |

`EZ60_PKG` chooses the DiPlay package when more than one is installed.
By default the script uses the first installed package in this order: `.next`, `.hudtest`, release.

## When something looks wrong

- **The model is not `J90K`.** The emulator was started from Android Studio, or `setup` was not run.
  Run `./ez60-emu stop`, then `./ez60-emu start`, then `./ez60-emu status`.
- **DiPlay fills the whole screen.** It was opened outside `launch`, for example by Android Studio or the app list.
  `./ez60-emu launch` restarts it in a window.
- **There is no HUD display.** `status` lists the displays.
  If the emulator's second display is not a presentation display, `setup` adds an 800×480 overlay display instead. The overlay is a small window on the main screen.
- **After a swipe, DiPlay looks different from the car.** In the car the swipe moves DiPlay's task to the other half's task.
  In the emulator it is a window move. Check the behavior in the car before you treat it as a DiPlay bug.
- **The emulator window is very large.** The emulator scales the 5120×1440 screen to fit the Mac's display. Drag a corner of the window to resize it.

## Remove it

```bash
./ez60-emu stop
```

```bash
./ez60-emu delete
```

`delete` prints the command that also removes the downloaded image.

## How it works

Nothing in DiPlay changes. The script uses only Android's own tools:

- **Hardware.** `create` copies [avd-config.ini](avd-config.ini) into the emulator's `config.ini`.
  It sets the 5120×1440 screen at density 160, and the 800×480 second display with presentation flags.
- **Model.** DiPlay checks `Build.MODEL == "J90K"` (`MazdaPlatform.isEz60`).
  `setup` gets root, makes the system partition writable (`-writable-system`, `adb remount`) and adds `ro.product.model=J90K` to `build.prop`.
  The image must be a Google APIs image, because Google Play images refuse root.
- **Halves.** `setup` turns on freeform windows (`enable_freeform_support`).
  `launch` opens DiPlay with `am start --windowingMode 5`, and `half` moves it with `am task resize` to `[0,0][2560,1440]` or `[2560,0][5120,1440]`.
- **HUD.** DiPlay looks for an 800×480 presentation display (`MazdaHudScreen.findDisplay`).
  The emulator's second display uses flag 75 (public, presentation, own content only, touch).
  If that doesn't produce a presentation display, `setup` falls back to an overlay display.
- **The car's settings keys.** These are the `Settings.Global` values that DiPlay reads on the car, written with `adb shell settings put global`.
