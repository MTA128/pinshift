# GooseRoute 🪿
### Map-based GPS simulation and road-route testing for Android

**GooseRoute 3.1 beta** is an independent, open-to-try Android app by **Goose / MTA128**. Choose a point, search for an address, or preview a snapped-to-road walking, cycling or driving route before simulating location on your own device.

[![Build GooseRoute APK](https://github.com/MTA128/pinshift/actions/workflows/build-apk.yml/badge.svg)](https://github.com/MTA128/pinshift/actions/workflows/build-apk.yml)
[![Download latest beta](https://img.shields.io/badge/Get_GooseRoute-beta_APK-5DE3BC?style=flat-square)](https://github.com/MTA128/pinshift/releases/latest)

<p align="center">
  <img src="docs/logo.svg" alt="GooseRoute goose on a location pin" width="146" /><br/>
  <strong>Pick a place. Plan a route. Test the journey.</strong>
</p>

## Download and install

**Start here: [Latest GooseRoute downloads](https://github.com/MTA128/pinshift/releases/latest)**. If there is no release yet, download the latest successful [GitHub Actions artifact](https://github.com/MTA128/pinshift/actions/workflows/build-apk.yml). A GitHub sign-in may be required for Actions artifacts.

1. On your Android 13+ phone, download **GooseRoute-beta.apk** from the release page, or download and extract the build ZIP to find `app-debug.apk`.
2. Open the APK in Downloads or My Files and choose **Install**. Only grant install permission to a source you trust.
3. Go to **Settings → Developer options → Select mock location app → GooseRoute**. To enable Developer options on Samsung, tap the Build number seven times under **About phone → Software information**.
4. Enable Location and grant GooseRoute precise location permission.
5. Pick a location or tap **ROUTE SIMULATOR**, select **Walk / Cycle / Drive**, an average speed and target duration, edit up to five optional waypoints and then **GENERATE FITTED ROAD ROUTE**.
6. Review the snapped route on the map, including its **actual** predicted duration, then start it. Use **STOP** to restore normal location providers.

> Important: each debug-signed beta APK may have a different signing key. Android can reject installing a newer build over an older one; you may need to uninstall first (which also erases app-local favourites). A stable release-signing key is required before promising seamless updates.

## Features

| Feature | Description |
| --- | --- |
| Interactive map | Tap or drag the goose's location pin; detailed coordinate input |
| Address lookup | Search addresses, places and UK postcodes |
| Route modes | Walking, cycling and driving via network routing profiles |
| Route duration | Choose 5–240 minutes and a fixed average speed; planner looks for actual road/path routes with matching distance |
| Custom route editing | Select start/end from the map; insert up to five draggable stops, reorder, remove and clear them |
| Automatic detour fitting | Optionally insert left, right or either-side detours, using mapped road routes until one fits within 5% or attempts are exhausted |
| Real mapped geometry | OSRM snaps selected waypoints to routable roads and paths; preview displayed before starting |
| Speed changes | Simulated speed fluctuates smoothly while retaining the trip-average speed |
| Saved and recent places | Device-local favourites and history |
| Privacy-minded | No ad SDKs, analytics SDKs or user accounts |
| Sharing | In-app share link, plus a public source repository |

### How duration and speed are handled

When you request **40 minutes at 5 km/h**, the target route length is **3.33 km**. GooseRoute uses your custom waypoints and asks the routing service for genuine walking, cycling or driving routes, iteratively adjusting extra road-snapped detours to get closer to that distance. **It does not secretly change your average speed**. When a close match isn't available, it displays the actual distance and duration instead of claiming an exact fit.

Automatic fitting evaluates a limited number of candidate routes (up to eleven; the public demo server is rate-limited). You can turn it off to use only your specified stops, or choose left/right detours. Shortening a route below the shortest road path through your required stops is impossible; the app tells you this instead of altering speed.  Pedestrian routes follow available mapped paths, not a guarantee of safe access in the real world. Simulation is for testing, not real navigation.

## Limitations and responsible use

- **No mock-location detection bypass**: Android marks generated locations as mock. Snapchat, WhatsApp and other apps may detect or ignore them or use other signals.
- **Route service**: The beta uses the community-operated [routing.openstreetmap.de](https://routing.openstreetmap.de/about.html) demo endpoints for walking, cycling and driving. It's rate-limited (≤1 request/sec) and **not an unlimited production service**. For a wider public launch, provide your own hosted routing backend or licensed routing provider.
- **Map tiles**: © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright). The map needs internet access. Community tile service usage rules apply.
- **On-device testing**: GitHub CI verifies compilation, not live GPS behaviour on every Android device.
- Google Play Services fused locations and device integrity checks are not promised to be overridden.
- The repository is public, but no open-source licence has been selected yet. Redistribution rights are not automatically granted.

## Privacy

Favourites and recent destinations are saved in Android app-local storage. Map tiles and route requests reach third-party infrastructure and include map/route coordinates. Android's address lookup may contact a backend chosen by the device. There is no built-in account registration or analytics SDK. See [Privacy](PRIVACY.md).

## Development and feedback

[Report bugs](https://github.com/MTA128/pinshift/issues/new/choose) • [See builds](https://github.com/MTA128/pinshift/actions) • [Release notes](CHANGELOG.md) • [How to contribute](CONTRIBUTING.md)

This is a **public beta**. Feedback and device screenshots are welcome, but do not include precise home coordinates or other personal location data in public bug reports.

### Project identity

- **App:** GooseRoute
- **Creator:** Goose / [@MTA128](https://github.com/MTA128)
- **Android package:** `com.goose.pinshift` (kept for upgrade compatibility)
- **Repository:** `MTA128/pinshift` (legacy project URL)
