# GooseRoute 3.1 beta 3 — Custom Route Planner

Choose start and destination pins on the map, then add up to five draggable waypoints. Move, reorder, remove or clear them to customise your journey.

Specify a journey duration (5–240 minutes) and desired average speed (1–110 km/h). GooseRoute computes the required distance and searches for a real walking, cycling or driving route. With **Fit route to required distance** enabled it adjusts temporary road-snapped detours (either side, left, or right), keeping manual stops in order and the requested average speed unchanged.

The planner displays mapped distance, estimated duration, and the error percentage. If an exact match is unavailable it shows the closest result rather than inventing an exact time. A target shorter than the shortest road route is not possible.

**Install:** Download `GooseRoute-v3.1-beta.3.apk` from the tagged release and install on Android 13+. Choose GooseRoute in Developer options → Select mock location app. Debug signatures can differ between releases; uninstalling an old build may be necessary, which removes local favourites.

**Limits:** Uses a rate-limited, community-run routing service. Network access is required to plan roads and paths. No guarantee all locations support a matched duration, and Android flags all mock locations. Snapchat and other third-party apps can detect or ignore them. Tested by CI and Android emulator; Samsung physical-device testing is still required.
