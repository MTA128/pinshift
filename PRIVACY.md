# GooseRoute privacy notice

**Last updated:** 3 October 2026

GooseRoute is a GPS-simulation beta. It does not require an account and does not include advertising, analytics or tracking SDKs.

- **Saved places and recent locations:** stored on your device in app-private SharedPreferences. Uninstalling GooseRoute removes them.
- **Route preview:** the start, destination, intermediate waypoint coordinates and travel mode are sent to the third-party OSRM demo server at `routing.openstreetmap.de`. The server can see these coordinates and the IP address used for the request. Its own privacy and retention rules apply.
- **Map loading:** map tile requests are made to OpenStreetMap tile providers, which see normal request metadata. Do not treat map interactions as entirely offline.
- **Address lookup:** Android's Geocoder service may use device/provider online services to resolve addresses. Their privacy rules apply.
- **Mock location:** when simulation is active, other apps that request location from Android may receive simulated coordinates. Android marks mock locations; third-party apps may detect them.
- **User control:** Press **STOP** to remove mock providers; uninstall to remove the app's local data.

This is a community beta. No server-side GooseRoute accounts or user profile database are operated.
