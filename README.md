# Phone Webcam Bridge

This repository contains two cooperating components for turning Android phones into OBS camera sources with tally support:

- `android-webcam-bridge` — Android app that exposes a local HTTP bridge (port `8787`), serves MJPEG frames, and accepts tally updates.
- `obs-relay` — Node.js relay that watches OBS (via obs-websocket v5), tracks registered phones, and posts tally state back to each phone.

This README consolidates setup, run instructions, and troubleshooting in one place.

**Key facts**
- OBS WebSocket v5 default port: `4455`
- Relay admin UI (default): `http://localhost:3000`
- Android bridge local port: `8787`
- Runtime device state is stored in `obs-relay/devices.json` (this file is gitignored)

## Quick start

1. Start OBS and enable obs-websocket v5 on port `4455`.
2. Start the relay:

```powershell
cd obs-relay
npm install
npm start
```

On Windows there is a helper `obs-relay/start-relay.bat` you can use.

3. Open the relay admin UI at `http://localhost:3000` and confirm the dashboard loads.
4. Open the Android app on each phone and register the relay host (use `http://<relay-ip>:3000`).
5. In OBS, create a Browser Source and use the same source name that you configure for the phone(s).

## Running the relay

- The relay uses these environment variables (defaults shown):

```
OBS_ADDRESS=ws://localhost:4455
OBS_PASSWORD=
SOURCE_NAME=Browser Full
ADMIN_PORT=3000
DEVICE_STALE_MS=10000
DEVICE_LATENCY_HISTORY_LIMIT=12
```

Adjust values by exporting environment variables before starting the relay.

## Admin UI and diagnostics

- The admin UI uses Server-Sent Events (`/events`) for live updates and includes a Diagnostic Assistant (`/api/assistant`).
- The device table shows current latency (ms) and a small history sparkline. Aim for <50ms for very low-latency setups, but hardware/network factors dominate.

## Android app (developer notes)

- Project: `android-webcam-bridge`
- The app runs a small local HTTP server on port `8787` and registers itself with the relay.
- To build locally (Windows):

```powershell
cd android-webcam-bridge
.\gradlew.bat assembleDebug
```

Notes: you must have a JDK installed and `JAVA_HOME` set for Gradle builds.

If you don't want to build, install the debug APK on a device/emulator and open the app.

## OBS configuration

- Use obs-websocket v5 on port `4455`.
- Use a stable Browser Source name (e.g., `Browser Full`) and ensure it matches the phone's configured source name.

## Latency and performance

Latency depends on OBS event propagation, relay processing, network RTT, and phone-side handling. Improvements in this repo include:
- Relay reuses HTTP keep-alive connections to phones.
- Phone bridge returns HTTP responses immediately and applies updates asynchronously to reduce request latency.

If latency remains high, profile the phone's bridge and the network path.

## Troubleshooting

- Relay won't connect to OBS: verify obs-websocket v5 and port `4455`.
- Phones not registering: verify the relay host is reachable from the phone and the phone's saved host includes protocol (e.g., `http://`).
- Phone appears stale: reopen the Android app or re-register; ensure heartbeats reach the relay.

## Repo layout

- `android-webcam-bridge/` — Android app project
- `obs-relay/` — Node relay and admin UI
- `public/` — browser prototype assets
- `server.mjs` — helper scripts

## Repository hygiene

- The repository ignores build artifacts and IDE settings via `.gitignore` (it includes `.idea/`, `**/build/`, and `obs-relay/devices.json`).
- You may delete `.idea/` safely; keep `app.iml`/`modules.xml` if you rely on IntelliJ module metadata or back them up.

---

If you'd like, I can back up `workspace.xml`, `modules.xml`, and `app.iml` before you delete `.idea/`, or I can stage a commit with the README update. Which would you prefer?