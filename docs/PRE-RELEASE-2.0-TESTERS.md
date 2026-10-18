# Pre-release 2.0: notes for testers

DiPlay EZ-60 0.2.19-ez60.2, 2026-10-18, for the closed tester group. This APK can connect an iPhone, so it carries
a CarPlay accessory identity: keep it to yourself and don't share or upload it.

Safety first: change settings and send reports only while parked. Have a passenger take any photo of the HUD.
Don't start CarPlay directions while the car's own navigation is guiding, including with its driving assistance.

## Install

- The app is **DiPlay EZ-60** (`com.reachkim.diplay.ez60.next`). It installs over your earlier test build and
  keeps your settings.
- **Settings → About** shows `0.2.19-ez60.2-hud-test`. Check it before you test, so your reports match this build.
- Wireless: choose **Built-in car hotspot** under **Settings → Connection → Connection setup**, and keep the
  iPhone's Personal Hotspot off.
- Wired only: allow the VPN once over ADB, because the car has no approval screen:
  `adb shell appops set com.reachkim.diplay.ez60.next ACTIVATE_VPN allow`.

## What is in this build

DiPlay reads the car's HUD type at each connection. Options 1 and 2 use the 2D windshield HUD, and Option 3 the
AR-HUD.

- **All trims:**
  - New **Settings → Mazda EZ-60** page with the car's HUD and ambient-light switches. Each can be turned off
    there.
  - Switching the car off clears DiPlay from the HUD at once.
  - Wireless connects faster on cars whose network is slow to answer.
  - Reports hide the car's VIN.
- **Options 1 and 2:**
  - The HUD map sits in the car's own map box, so its edges are no longer cut off.
  - The map moves to the left when you swap the HUD panels.
- **Option 3:**
  - Turn-by-turn on the AR-HUD by default.
  - **AR-HUD map (experimental)**, off by default, shows CarPlay's map in the AR-HUD while you drive.
  - **AR-HUD map size** sets its size: the car's size, Large or Largest.

## What to test

Note your trim (Option 1, 2 or 3), what you did and the time.

### Every trim

- [ ] Connect wirelessly (and wired, if you use it). How long did it take?
- [ ] Start a route in CarPlay and drive: the HUD shows the turn arrow and distance.
- [ ] End the route, or arrive: the HUD clears.
- [ ] Swipe CarPlay to the other screen half and back: it follows without reconnecting.
- [ ] Music, a phone call and Siri.
- [ ] Switch the car off with a route running, lock it and wait at least 15 minutes. Then start the car and press the
      camera button: does the 360 camera open normally? Does the dash camera still record?

### Options 1 and 2

- [ ] The CarPlay map on the HUD is whole, with no edge cut off.
- [ ] In the car's HUD settings, swap the HUD panels: the map moves to the left.
- [ ] **HUD map view** (Far, Normal, Near) changes how much map the HUD shows after you reconnect.

### Option 3 (AR-HUD)

- [ ] With **AR-HUD map** off: the AR-HUD shows CarPlay's turn arrow and distance.
- [ ] Turn **AR-HUD map** on (**Settings → Mazda EZ-60 → Windshield HUD**) and reconnect:
  - the map shows while you drive in D;
  - it goes in Park;
  - it comes back after D → P → D.
- [ ] Each HUD mode in the car's settings:
  - Classic and Custom: the map shows.
  - Minimalist: no map, as with the car's own navigation.
  - Beyond-Class 3D: the map shows on the left.
- [ ] Each **AR-HUD map size**: is the map easy to read, and does it stay clear of the HUD's speed and gear?
- [ ] Press the car's home button while the map is showing, then open CarPlay again: the map comes back.
- [ ] The long-sleep check, with the AR-HUD map on, three times:
  - start a route;
  - switch the car off and lock it for at least 15 minutes;
  - start the car and press the camera button.

## Sending a report

1. Park safely as soon as you can after the problem.
2. Open **Settings → Diagnostics → Save diagnostic report**, or **Get the report on a phone** and scan the QR code.
   The phone page also offers the EZ-60 probe report.
3. Send the report with your trim, the time, what you did, and a photo if it helps.

Reports hide network addresses and the car's VIN. Still read them before sending.
