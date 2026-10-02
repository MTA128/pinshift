# PinShift for Android

Personal map-based mock-location application for Android 13+ (including Samsung Galaxy S21 on Android 15).

Features:
- OpenStreetMap map; tap or drag the pin.
- Search for a street address or place using Android's Geocoder.
- Enter exact latitude and longitude.
- Move here starts or updates the mock GPS.
- Stop button and a persistent notification to restore real GPS.

## Installation

1. Open Actions, select the most recent successful Build PinShift APK run, and download PinShift-Android-APK.
2. Unzip the artifact and install app-debug.apk on your phone.
3. Enable Developer options: Settings > About phone > Software information > tap Build number seven times.
4. Go to Developer options > Select mock location app > PinShift.
5. Enable Android Location; open PinShift, allow location permission, select a point and tap Move here.

Android marks injected locations as mock. Snapchat, WhatsApp and other apps may detect or ignore them, so those apps are not guaranteed to show the selected point.

## Build

GitHub Actions uses Gradle 8.9, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Java 17 and Android SDK 35. For local building use Gradle task :app:assembleDebug.

Source is experimental; validate on the device.
