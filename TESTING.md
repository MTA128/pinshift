# GooseRoute test plan

Automated testing is split into two workflows.

**Build GooseRoute Android Beta:** compiles the debug APK and runs JUnit timing tests before publishing its output.

**GooseRoute Emulator Tests:** installs debug app and Android test APK in a clean Android 15 (API 35) x86_64 emulator, grants mock-location app-op, then tests:
- Launch and Compose controls (map screen, route mode toggling).
- Static GPS at two locations using Android's LocationManager and isMock flag.
- Following a multi-point road geometry, arriving at destination, and holding.
- Invalid coordinate rejection and explicit stop.
- Locally saved places and recent history.

External dependencies not completely automatable: address geocoder accuracy, OpenStreetMap tiles, OSRM routing demo service (availability / quota), Google fused locations, Snapchat/WhatsApp behaviour, battery restrictions on Samsung One UI, outdoor GPS, permissions for installing third-party APK. Device-specific testing still needs a Galaxy S21.

Test reports and screenshots are published to the workflow run under **Artifacts**. A successful build is *not* proof that every external provider or app accepts mock locations.
