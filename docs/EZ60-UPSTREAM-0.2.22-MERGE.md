# Merging upstream DiPlay 0.2.22 into the EZ-60 fork

Branch `ez60-upstream-0.2.22`, 2026-10-29, made from `ez60-hud-map-style` (`fe7e906c`). It merges upstream tag
`v0.2.22` (upstream 0.2.20, 0.2.21 and 0.2.22: 487 commits since `v0.2.19`). Version `0.2.22-ez60.1`, versionCode 41.
It has not been tested on the car.

Upstream `main` is 3 commits past `v0.2.22`; they change only the website and upstream's own docs, so the tag holds all of the app.

## Left out, as in the 0.2.17 and 0.2.19 merges

Upstream's BYD-only docs, release notes, validation records, website and its build scripts, Traditional Chinese docs,
tracked screenshots (the tests write them to `DIPLAY_SCREENSHOT_DIR` when it is set) and the Korean `app_name` of
`:mobile` (the fork's app name is `app_name_ez60` and is not translated). `README.md`, `README.zh-CN.md` and
`docs/INSTALL.md` stay the fork's own. Upstream's 0.2.20–0.2.22 entries are in `CHANGELOG.md` under "Upstream DiPlay
history", with links to upstream's release notes.

## Where the fork's code and upstream's met

| Area | Upstream 0.2.20–0.2.22 | Kept for the EZ-60 |
| --- | --- | --- |
| Call microphone | Car-phone-state calls record from VOICE_RECOGNITION; restart a recorder that returns no audio (#566); name the audio mode and other recordings in silent windows (#987); capture at audio priority | The fork's call sources (VOICE_RECOGNITION, VOICE_COMMUNICATION, MIC), the switch while Android silences one, no communication mode, and `silenced=` / `recordings=` in the stats line. Both stats details are printed. |
| Touch | Each finger keeps one HID slot (#444); with the settings swipe on, a new touch waits up to 80 ms (#922) | The cockpit view-area touch mapping, now also applied to a held touch when it is sent |
| Stream 111 surface | Given only after the main picture's first frame | The HUD keeps its own surface (EZ-60 report 19). The EZ-60 skips upstream's cluster setup, so the HUD and AR-HUD map windows now get their surface at the first frame there; without this the HUD map would stay dark after a reconnect. |
| CarPlay task | New `SameTaskCarPlayHostActivity` and "Keep CarPlay in the launcher card" (#463) | `CarPlayHostActivity` stays in DiPlay's task (the car's two-finger swipe moves a whole task). Both declarations are in DiPlay's task, so the new option changes nothing on the EZ-60. |
| Opening screens | `OwnScreenStart` retries in a new task when a start is refused | The fork's no-animation flags and `EXTRA_CONNECT` |
| Car hotspot interface | A VLAN fallback (#573) and mobile-data exclusion (#921): when the platform lists no tethered interface, a wired interface with an address may stand in | Off on the EZ-60 (`Build.MODEL` J90K): car-output9 shows `wt_eth2_4…10` holding IPv4, and every report from both cars lists `ap0` or `ap_br_ap0` as tethered. `ManualHotspotEz60Test` checks this. |
| Bonjour | Inline reverse-DNS fix | The fork's `CarPlayBonjourProtocol.namedAddress`, confirmed on the AR-HUD car (40 ms) |
| Settings backup | Typed allow-lists replace "every key" | `diplay_mazda_hud` gets its own typed list (a STRING type was added) |
| Media keys | Retry focus after a transient loss only on a new iPhone play | The fork's rule that leaves focus to the car's Bluetooth player, and the ambient-light updates |
| Ambient lights | Band analysis for the car's own rhythm (BYD) | The EZ-60 lights page and the `MusicLevel` tap |
| Settings | Car rhythm controls on the ambient card (BYD) | Only on the non-EZ-60 ambient card |

Three fork tests were changed to match upstream's new behavior: the HUD surface test now renders a main frame first
(and two tests were added for the first-frame gate), the cockpit touch test waits out the 80 ms hold, and the task test
now checks that both CarPlay declarations are in DiPlay's task.

## To check on the car

1. Wireless over the car hotspot: CarPlay connects as before, and the log's "manual hotspot interface" line says
   `selectedAp=[ap0]` or `[ap_br_ap0]`.
2. Music over wireless for a few songs: no speed-up, stutter or drift (upstream's playback-rate adjustment is new here).
3. A Messenger call and a Siri request: the other side hears you (the call microphone code was merged by hand).
4. HUD map (Options 1/2) and AR-HUD map (Option 3): shows while guiding, and comes back after a wireless drop.
5. Swipe CarPlay to the other half and open and close the vehicle panel: touches land where you tap.
6. Taps, drags and long presses in CarPlay feel the same (the 80 ms hold applies only before a move or a lift).
7. Settings → Mazda EZ-60 and Settings → Diagnostics → EZ-60 probe open and work.

## JulyStar-Lv/DiPlay

JulyStar-Lv/DiPlay (the original cockpit port) now builds a separate `:mazda` app (`com.thunder.carplay`) with its own
`Mzd*` classes, 16 commits on upstream 0.2.21 (to 2026-10-24, tags `v0.2.21-mazda.20261020` and `.20261024`). It is a
parallel port of the same car, not a base for this fork, so none of it was merged. Findings worth testing here later:

- **Passenger half:** their panel is (2592,192)–(5088,1272), 32 px in from both sides of the half, like the car's own
  apps; this fork assumes a mirror of the driver's half, (2560,192)–(5056,1272), and has not measured it.
- **CarPlay's car icon:** a generic HOME without `launcher_display_area` left the car launcher's cache for the other
  half stale, so the next tap in the app list could fail to reopen DiPlay. They now close DiPlay's windows instead
  and keep the session.
- **Narrow view area seam:** a coloured band at the right edge of the 1728 px view area; they crop 12 source pixels
  (at a 1080-high stream).
- **Audio:** RTP redundancy recovery, a jitter buffer and paced AudioTrack writes on top of 0.2.21. Upstream 0.2.22
  fixed 0.2.21's music problems its own way, and the two overlap, so this is left for later.

## Branches left as they were

No other branch was committed to, rebased or pushed. `origin/ez60-hud-map-style` is still `fe7e906c`.
