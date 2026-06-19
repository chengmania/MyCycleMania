# My Cycle Mania

**Fully offline cycling navigation for Android.**
Maps, turn-by-turn routing, ride recording, GPX export — no accounts, no ads, no internet required after setup.

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%2013%2B-brightgreen)
![Status](https://img.shields.io/badge/status-active-success)

---

## What It Does

My Cycle Mania is a passion project built for cyclists who want a clean, no-nonsense offline navigation and ride recording app. No Google Maps, no cloud, no data plan required once you've downloaded your region.

| Feature | Details |
|---|---|
| Offline maps | OpenStreetMap tiles downloaded to your device |
| Turn-by-turn navigation | GraphHopper bicycle routing, fully on-device |
| Voice guidance | Announces street names, distances, and turns |
| Waypoint planning | Long-press to drop waypoints, see distance + time |
| Ride recording | GPS track in background, survives screen-off |
| Live stats | Distance, duration, speed while riding |
| Elevation tracking | Gain tracked throughout the ride |
| GPX export | Auto-saved after every ride |
| Ride summary | Stats + map thumbnail + gallery screenshot |
| Cycle map | CyclOSM overlay showing bike lanes and trails |
| Terrain overlay | OpenTopoMap contour layer |
| Routing modes | Fastest / Balanced / Safest |

---

## Screenshots

*(coming soon)*

---

## First-Time Setup

My Cycle Mania is fully offline. Before your first ride you need to download two things — both can be done at home on Wi-Fi.

### Step 1 — Download Map Tiles
In the app: menu → **Download Map Tiles**

Select your region. For Eastern PA this is roughly 30 MB. The tiles are the visual map you see on screen.

### Step 2 — Download Routing Data
In the app: menu → **Download Routing Data**

Select your state. Pennsylvania is ~120 MB. This is the OpenStreetMap road network file (`.pbf`) that GraphHopper uses to calculate routes.

### Step 3 — Wait for the Index Build
After the routing data downloads, the app builds a routing index on your device. You'll see a notification: **"Building routing index…"**

This takes a few minutes the first time — GraphHopper is processing the entire road network for your state and saving it in an optimized format. This only happens once. You'll get a notification when it's done: **"My Cycle Mania is ready!"**

After that, every route calculation is instant.

---

## Requirements

- Android 13 (API 33) or higher
- ~500 MB free storage for tiles + routing data (varies by region)
- Wi-Fi for the one-time downloads

---

## Tech Stack

| Library | Purpose |
|---|---|
| [OSMDroid](https://github.com/osmdroid/osmdroid) | Offline OpenStreetMap tile rendering |
| [GraphHopper](https://github.com/graphhopper/graphhopper) | Offline bicycle routing engine |
| [Google Play Services Location](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient) | GPS / FusedLocationProvider |
| [OSMBonusPack](https://github.com/MKergall/osmbonuspack) | Route overlay helpers |

Map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright)

---

## Building from Source

```bash
git clone https://github.com/chengmania/MyCycleMania.git
cd MyCycleMania
./gradlew assembleDebug
```

Requires Android Studio or the Android SDK command-line tools. The Java toolchain is configured in `gradle.properties` — update `org.gradle.java.home` to point to your local JDK if needed.

```
compileSdk  36
targetSdk   34
minSdk      33
Language    Kotlin
Build       Gradle (Kotlin DSL)
```

---

## Privacy

My Cycle Mania collects **no personal data**. GPS stays on your device. Ride files stay on your device. No analytics, no crash reporting, no tracking.

Full privacy policy: [PRIVACY_POLICY.txt](PRIVACY_POLICY.txt)

---

## Contributing

Bug reports and pull requests are welcome. Open an issue first for anything significant so we can discuss the approach.

Please keep the spirit of the project intact:
- No internet required for core features
- No accounts or login
- No ads or tracking
- No Jetpack Compose (standard XML layouts)

---

## License

MIT — see [LICENSE](LICENSE)

Copyright (c) 2026 KC3SMW

---

## Support the Project

If this app has made a ride more fun, consider buying the developer a coffee.
Available inside the app via Google Play, or open an issue if you just want to say thanks.

*Built by KC3SMW — ride safe, obey traffic laws, enjoy the road.*
