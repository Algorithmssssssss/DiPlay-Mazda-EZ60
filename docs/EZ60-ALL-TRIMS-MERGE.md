# Merging the Option 3 (AR-HUD) work into the Option 1/2 code

Branch `ez60-0.2.19-all-trims`, 2026-10-18. It is ready to merge: it brings the Option 3 work, tested on the
AR-HUD, together with the Option 1/2 code. Testers reported the result working on all three EZ-60 trims on
2026-10-18.

## What the branch holds

| Step | Commit | What it is |
| --- | --- | --- |
| Start | `bbc140f1` | `ez60-upstream-0.2.19`: 0.2.19-ez60.1, the Option 1/2 code |
| Merge | `bb616dcc` | `ez60-variant-check` at `4db10827`: the Option 3 work, 12 commits |
| Docs | the commit after the merge | this file, `CHANGELOG.md`, `README.md`, `docs/RELEASE-NOTES-EZ60.md`, `docs/INSTALL.md`, `docs/MAZDA_EZ60.md` |

The Option 3 branch was started from `bbc140f1`, and nothing has landed on `ez60-upstream-0.2.19` since. The merge
had no conflicts and changed nothing in either side's code. The app code on this branch is the same, file for file, as
commit `540c405c`. That commit built the last Option 3 test APK (`DiPlay-EZ60-0.2.19-arhud-map-6.apk`); the commits
after it change documentation only.

The version stays `0.2.19-ez60.1` (versionCode 38), which has not been released. Raise it when you publish a
release.

## Branches left as they were

This branch was created in its own worktree. No existing branch was committed to, rebased or pushed, locally or on
`origin`:

| Branch | Head |
| --- | --- |
| `main` | `73749aa5` |
| `ez60-upstream-0.2.17` | `b1e86107` |
| `ez60-upstream-0.2.19` (Option 1/2) | `bbc140f1` |
| `ez60-variant-check` (Option 3) | `4db10827` |
| `ez60-hud-song` | `b3ea90a1` |
| `ez60-emulator` | `03181dd8` |
| `ez60-hud-practice-route` | `2f61f410` |

## How to merge

Both `ez60-upstream-0.2.19` and `main` are ancestors of this branch, so either one fast-forwards to it:

```sh
git switch ez60-upstream-0.2.19
git merge --ff-only ez60-0.2.19-all-trims
```

Fast-forwarding `main` makes it 0.2.19-ez60.1 with all of the above. That is the release step, so do it only when
you mean to release. A pull request into either branch on GitHub shows the same changes.

## The Option 3 commits

| Commit | Date | Change |
| --- | --- | --- |
| `3fe43bc0` | 2026-10-16 | Adapt the EZ-60 HUD to the car's trim |
| `394d97f7` | 2026-10-16 | Mask the car's VIN in diagnostic and probe reports |
| `51eeb450` | 2026-10-16 | Start Bonjour at once on the AR-HUD EZ-60 and fix its report lines |
| `5a39fd6a` | 2026-10-17 | Keep the EZ-60 "Car features:" line in the session log |
| `ed1ba862` | 2026-10-17 | Show CarPlay's map on the EZ-60 AR-HUD (experimental, off by default) |
| `3247e381` | 2026-10-17 | End DiPlay's EZ-60 HUD state when the car is switched off |
| `6b6130f2` | 2026-10-17 | Record what the EZ-60 camera apps do and do not use |
| `907accd3` | 2026-10-17 | AR-HUD map: come back after Park, add a size, ask for 60 fps |
| `be54480f` | 2026-10-17 | AR-HUD map: follow every HUD mode, survive the car's home screen |
| `624bdd5e` | 2026-10-17 | Hear the EZ-60 switch-off through all global settings |
| `540c405c` | 2026-10-17 | Apply the EZNav developers' HUD measurements |
| `4db10827` | 2026-10-17 | Record our AR-HUD camera results next to EZNav's report |

## What changes on each trim

DiPlay reads the car's HUD type at each connection: `tinnove_config` key `car_feature_hud_type`, read-only, the
same way the car's own navigation reads it (`MazdaCarFeatures.hud()`). The value 3 takes the AR-HUD path. Any other
value, or none, takes the 2D HUD path. Options 1 and 2 take the 2D HUD path, and Option 3 the AR-HUD path.

### Options 1 and 2 (2D HUD path)

The HUD calls and turn ids are unchanged. What a driver of an Option 1 or 2 car notices:

- **The HUD map box.** CarPlay's map is drawn in the car's own 178×178 box at (584,179), or at (38,179) when the
  driver swaps the HUD panels. Before, it was drawn in 227×188 at (573,167), the Deepal S05's box, whose edges were
  cropped (`MazdaHud.VISIBLE_*`, `MazdaHudScreen.standardArea`).
- **The HUD window's lifetime.** DiPlay's window on the HUD display exists only while DiPlay guides on the HUD, and
  is removed when the car is switched off (`CarPlayHostActivity.onMazdaHudGuiding`). Before, it stayed for the
  whole connection.
- **Switching the car off** ends DiPlay's HUD guidance and gives back the HUD's navigation focus at once
  (`MazdaPowerWatch`, `MazdaHud.setCarOff`), as the car's own navigation does.
- **Settings.** Turn-by-turn on the HUD and HUD map view moved from Settings → Navigation, and the ambient lights
  from Settings → Advanced, to the new **Settings → Mazda EZ-60** page. Their saved values carry over.

### Option 3 (AR-HUD path)

- Turn-by-turn on the HUD works as on the 2D HUD, with the same calls. The AR-HUD draws its own arrow, distance and
  arrival time, except in its Beyond-Class 3D mode.
- **AR-HUD map (experimental), off by default** (`MazdaArHud.kt`). While CarPlay guides out of Park in a HUD mode
  that shows a map, DiPlay switches the AR-HUD's video link (`IDouInteractiveManager` calls 72–74 and 1). It then
  shows CarPlay's map in the car's map box on `MeterDisplay#0` at the chosen size. It gives the link back in Park,
  when the route ends, in a HUD mode without a map, at disconnect and at switch-off. It steps aside when another app
  takes the link, and turns the map off for the connection on any failure. The window exists only while DiPlay holds
  the link.
- The testers reported no fault in the 360 camera (`com.tinnove.evs`) or the dash camera (`com.incall.dvr`).

### Every EZ-60

- The **Settings → Mazda EZ-60** page: first in Settings, shown only on the EZ-60 (`SettingsCategory.MAZDA_EZ60`).
- The session log gets a "Car features:" line, and the probe gets the per-trim feature flags. The head unit's ADB
  serial is left out of the probe.
- The switch-off watch (`MazdaPowerWatch`) listens to all global settings and filters for `vehicle_acc_status`,
  `vs_power_status_feedback_value` and `VEHICLE_SETTING_OFF_TIME_KEY`. On the AR-HUD car's firmware, a watch on the
  key alone never heard the switch-off.
- If a run was stopped while guiding on the HUD, the next run says so in the session log.

### Every head unit, not only the EZ-60

- **Bonjour** (`shared/.../network/CarPlayBonjour.kt`). The mDNS service starts at once on networks that do not
  answer DNS. Each address is now given its host name before `JmDNS.create`, so JmDNS no longer reverse-resolves it.
  This waited 10–20 s on an AR-HUD EZ-60.
- **The cluster or HUD map stream** (stream 111) belongs to the CarPlay session, not to one CarPlay screen
  (`CarPlaySessionCluster.streamOnDisplay`). A CarPlay screen that adopts a running session no longer gives that
  stream the main picture's surface. This applies to BYD clusters as well.
- **Reports** mask vehicle identification numbers (`DiagnosticRedactor`).
- `CarPlayClusterDisplay.config` takes an `fps` parameter, which defaults to the previous value.
  `MazdaPlatform.ez60ForTests` lets tests render the EZ-60 page and is null outside tests.

## Settings and saved values

| Setting | Where now | Saved as (`SharedPreferences`) | Default |
| --- | --- | --- | --- |
| Turn-by-turn on the HUD (experimental) | Mazda EZ-60 → Windshield HUD | `diplay_mazda_hud` / `turn_by_turn`, unchanged | on |
| HUD map view | Mazda EZ-60 → Windshield HUD | `diplay_mazda_hud` / `map_view`, unchanged | Normal |
| AR-HUD map (experimental), Option 3 only | Mazda EZ-60 → Windshield HUD | `diplay_mazda_hud` / `ar_hud_map`, new | off |
| AR-HUD map size, Option 3 only | Mazda EZ-60 → Windshield HUD | `diplay_mazda_hud` / `ar_hud_map_size`, new | the car's size |
| Ambient lights | Mazda EZ-60 → Ambient lighting (experimental) | unchanged | off |

`diplay_mazda_hud_state` / `guiding_since` is new and internal. It marks a run that was stopped while guiding on
the HUD.

## Checks

- **Tests added or changed:** `MazdaArHudTest`, `MazdaCarFeaturesTest`, `MazdaPowerTest` (new);
  `AdaptiveSettingsUiTest`, `CarPlayHostClusterSurfaceTest`, `ClusterMapScaleCapabilityTest`,
  `MazdaCockpitGeometryTest`, `MazdaHudTest`, `MazdaProbeTest`, `CarPlayBonjourTest`.
- **CI** (`.github/workflows/android.yml`) on this branch, 2026-10-18: build successful. Unit tests of `shared`, `common` and `home` ran 2,717 tests with 0 failures (1 skipped). Lint and the `mobile`, `home` and `maphost` debug builds passed.
- **In the car:**
  - Option 1: the owner's car.
  - Option 3: probe and session reports and windshield photos from a tester's car, 2026-10-16 and 17. They covered
    the trim detection, the Bonjour fix, the AR-HUD map, D → P → D, the HUD modes, the car's home screen and
    switch-off.
  - All three trims: reported working on 2026-10-18.

## Safety

DiPlay only feeds the HUD's display. It reads the gear, HUD mode and map layout as read-only vehicle properties, and
has no path to the car's own navigation or driving assistance. The AR-HUD map is off by default, and turns itself
off for the connection on any failure.
