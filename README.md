# PinShift 2.0 — Location Studio

Android 13+ location testing app for your own phone. Built with Kotlin, Material 3 and OpenStreetMap.

## Features

- Polished native dark interface with live map pin and fine coordinate inputs.
- Search for street addresses, postcodes and place names with Android Geocoder.
- Exact coordinate entry, coordinate clipboard copying and remembered last position.
- Locally saved favourite spots, recent selections and one-tap recall.
- Static mock GPS and configurable-speed **straight-line** movement between two points.
- Start/stop controls and a persistent foreground notification.
- Help for choosing PinShift as the system mock-location app.
- Android's documented test-provider API; no root access required.

**Limitations:** Android marks mock locations. Other apps may detect, ignore or supplement them using other signals. PinShift does not try to hide mock status or defeat integrity checks. A straight-line simulation is not a real street route. Device and third-party app compatibility require testing.

## Installation

1. Open the latest **successful** workflow in [GitHub Actions](https://github.com/MTA128/pinshift/actions).
2. Download the **PinShift-Android-APK** artifact ZIP and extract its `app-debug.apk`.
3. Install on your Galaxy S21; you might need to allow installation from your file manager.
4. Enable Developer options, then choose **PinShift** under **Select mock location app**.
5. Turn on Location. Launch PinShift, allow precise location permission, and try **Move here**.

The app doesn't collect user analytics. Map tiles are downloaded from OpenStreetMap and address lookup is performed by the Android Geocoder service. If the current build's debug signing key differs from an earlier version, Android may require uninstalling the old APK first. Uninstalling removes your app-local saved places.

## Build

The project uses Java 17, AGP 8.7.3, Kotlin 2.0.21, Compose Material 3, osmdroid and Android API 35. GitHub Actions compiles and publishes a debug APK on each push.

The repository's current code is experimental, and successful compilation does not constitute testing on a Samsung device.
