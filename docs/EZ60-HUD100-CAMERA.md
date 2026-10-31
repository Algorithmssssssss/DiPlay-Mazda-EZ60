# EZ-60 100" AR-HUD (Option 3) vs the 360 camera / DVR

Working notes for branch `ez60-hud100-camera` (off `ez60-upstream-0.2.22`).
Sources: EZNav's write-up `EZNav-Problems/ez60-100inch-minimap.md` (their §8 table) and `Note-From-EZNAV.md` §2,
read against our own code in `common/src/main/java/com/shilapi/xcertplay/MazdaArHud.kt`,
`MazdaHudScreen.kt` and `CarPlayHostActivity.kt`. This is diagnosis + a test plan, no behaviour change yet.

## The fault EZNav sees

On the 100" AR-HUD EZ-60 (`hud_type 3`), the head unit exposes a second virtual display `MeterDisplay#0`
(4032×845). Its buffers are consumed by the OEM service `com.raite.meterdisplay.server` and shipped over a
socket to the **Raite SoC**, which also drives the **exterior camera HAL** (`/dev/raite_ipc_1`) for the 360
surround view and the dashcam.

Any `Presentation` **attached** to `MeterDisplay#0` keeps that link running at a constant **1 fps with 4
display-sized buffers held by the Raite side** — the same whether the window paints, is paused, or runs at 12 fps.
A **dismissed** window costs 0 fps / 0 buffers.

After a deep sleep (IPO, starts ~5 min after screen-off), the OEM consumer **leaks**: it holds
`mAcquiredBufs(size=4)` for the rest of the boot. The first camera open at the next wake times out
(`Rpc callback buffer timeout` → `Wait first frame deliver timeout (2000 ms)`) → **"DVR system failure"**; the
360 view and dashcam are dead until a real head-unit restart (an ignition cycle, so the fault can last days).
It is an OEM consumer-side leak an unprivileged app cannot reach.

**DiPlay (our app) runs the AR-HUD map on the same car and does not trigger this.** EZNav has tried ~14 builds
(their §8 table); the only clean states are "no 100" window/ask at all" (car pack v8) and "the link is not taken
again after a sleep" (vc274, held ~3 days then relapsed). Mirroring our link rules did **not** fix it:
vc236 copied DiPlay 0.2.19's rules, vc277 copied 0.2.22's — both still dead.

## What is NOT the difference (they already copied it)

Our control-plane behaviour, which their doc already matched:

- **Window follows the grant, dismissed not paused.** `MazdaArHudLink.hide()` calls `ui.hide()` then
  `setVideoStatus(6)`, and `MazdaHudScreen.dismiss()` tears the `Presentation` down — never a paused-but-attached
  window. EZNav matched this at vc205. Still dead.
- **Same link allowlist and handshake.** `setVideoStatus 72` (5 show / 6 hide), `register 73`, `unregister 74`,
  `sendSwitchLVDSFinishEvent 1(0)`; confirm every 19 and every 6; recheck the gear on an unasked 6. EZNav's
  `VideoLinkDiscipline` is "DiPlay 0.2.22's rules" as of vc277. Still dead.

So the link state machine is a dead end — it's already identical on both apps.

## The three real differences (their §8), mapped to our code

| # | DiPlay | EZNav | Lead |
|---|---|---|---|
| 1 | TextureView fed by a **hardware MediaCodec** decoder surface (the CarPlay video stream), ~30 fps | MapLibre **GL** surface capped at 12 fps | **renderer / BufferQueue producer** |
| 2 | **Killed** by the OEM IPO sweep ~1–3 s after switch-off (not on `persist.ipo.shutdown.process.wl`) | **Survives** the sleep (whitelisted to keep its accessibility grant) | **producer process lifecycle** |
| 3 | `setVideoStatus(6)` + link closed at once on ACC off | sends feed status `CLOSE = 0` (raises "Status 1" as a maybe) | weakest; cheap to vary |

Where our code does #1 and #2:

- **#1 renderer.** `MazdaHudScreen` (`MazdaHudScreen.kt:68`) is a `TextureView`; its `SurfaceTexture` is wrapped in
  a `Surface` and handed to the decoder sink via `onClusterSurface`. The BufferQueue producer on `MeterDisplay#0`
  is therefore a **video-codec output surface**, not an EGL/GL surface. The OEM consumer's buffer accounting may
  simply behave differently (or not strand buffers) against a codec producer. This is the lead EZNav themselves
  flag first, with a fast, no-sleep check.
- **#2 process death.** We are not whitelisted, so when the car switches off the OS force-stops DiPlay within a
  couple of seconds (`CarPlayHostActivity` logs the force-stop ~1 s after `vehicle_acc_status=2`). The window, its
  surface and its BufferQueue **producer all die before deep sleep** — the consumer sees the producer disconnect
  and (apparently) releases cleanly. EZNav surviving the sleep means their producer side is still alive across it,
  which is exactly the condition under which the 4 buffers get stranded. **This is the most structural reason our
  app can't reproduce the fault: a dead producer can't hold the consumer's buffers across a sleep.**

On top of the OS kill we also dismiss explicitly, belt-and-suspenders: `onCarPower()`
(`CarPlayHostActivity.kt:6577`) runs `closeMazdaArHud()` and `dismissMazdaHudScreen()` the moment
`vehicle_acc_status=2`, with the comment *"No HUD window may survive into the head unit's sleep (EZNav: it broke
the 360 camera across one)."* `MazdaHudScreen`'s own KDoc records the same reason.

## Reading

The link rules are not it (copied, still dead). The remaining suspects are the **renderer** (#1) and **whether the
producer process is still alive across the sleep** (#2). #2 best explains why DiPlay never reproduces the fault:
we are killed; EZNav persists. If that holds, the fix on EZNav's side is to let the window's producer process die
before deep sleep (or guarantee the display/surface is fully released, not just dismissed in-process — which they
have tried, so process-kill is the stronger version). #1 would explain it if the GL producer strands buffers that
a codec producer does not.

## Experiments (run on the car; the first needs no sleep)

1. **Decisive, seconds long (EZNav's own suggestion).** With adb, show the AR-HUD map once then dismiss it, and
   read the meter-display sink's `mAcquiredBufs` from `dumpsys SurfaceFlinger` — DiPlay vs EZNav. If DiPlay returns
   to 0 acquired buffers and EZNav does not, the renderer/producer difference (#1) is proven and we can hand EZNav
   a precise cause.
2. **Isolate #2 on EZNav's side (no change on ours).** Have them ship one test build that does **not** whitelist on
   `persist.ipo.shutdown.process.wl` (accepting the lost accessibility grant across sleep), and check the camera
   after a long park. If it survives, "producer process survives the sleep" is the cause.
3. **#3, cheapest.** Vary only the ACC-off feed status value on EZNav's side. Low expectation; rule it out last.

## For our side (why this branch exists)

We don't crash, so the work here is (a) to prove **why** via experiment 1 so we can give EZNav an exact cause, and
(b) a regression guard: the fault's precondition is "an AR-HUD window's producer alive across a deep sleep." If we
ever keep DiPlay alive across switch-off (e.g. whitelisting ourselves for some other feature) **and** a HUD window
could still exist, we would walk into EZNav's bug.

**Guard added** (this branch): `MazdaPowerWatch.ipoWhitelisted()` reads `persist.ipo.shutdown.process.wl`, and
`CarPlayHostActivity.warnIfIpoWhitelistedWithHud()` logs a loud WARNING at switch-off (`vehicle_acc_status=2`) if
DiPlay is ever both on that whitelist and running with the EZ-60 HUD enabled — so the IPO whitelist and a
`MeterDisplay#0` window can never combine silently. It is diagnostic only: today DiPlay is not whitelisted, so the
guard never fires, and the OS force-stop plus our at-once `dismissMazdaHudScreen()` remain the real protection. The
pure membership check is covered by `MazdaPowerTest.ipoWhitelistMembershipIgnoresSeparatorsAndSpacing`.

Still pending: the car-side `dumpsys SurfaceFlinger` measurement (experiment 1) to turn "we think it's the
renderer/process-death" into a proven cause we can hand EZNav.
