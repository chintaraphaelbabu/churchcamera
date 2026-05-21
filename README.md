# Phone Webcam Bridge

This workspace contains two connected pieces:

- the Android webcam bridge in [android-webcam-bridge](android-webcam-bridge)
- the OBS tally relay in [obs-relay](obs-relay)

The Android app is the primary camera surface. The relay watches OBS, tracks which phone is fresh, and pushes tally state back to each phone.

## What This Does

The full flow is:

1. The Android app runs on each phone and exposes a local HTTP bridge on port `8787`.
2. The relay registers each phone with its callback URL.
3. OBS sends scene and source state to the relay through `obs-websocket` v5 on port `4455`.
4. The relay posts `PROGRAM`, `PREVIEW`, or `IDLE` back to each phone.
5. The admin page shows registered phones, freshness, latency, and live diagnostics.

## Fast Start

If you only want the tally path working, follow this order:

1. Start OBS and enable `obs-websocket` v5 on port `4455`.
2. Start the relay in [obs-relay](obs-relay).
3. Open the relay admin page at `http://localhost:3000`.
4. Open the Android app on each phone and register it to the relay host.
5. Make sure the phone source name matches the Browser Source name in OBS.

## Relay Setup

The relay watches OBS and broadcasts tally state to all registered phones.

### Relay behavior

- `PROGRAM` means the source is live in OBS program.
- `PREVIEW` means the source is visible in OBS preview.
- `IDLE` means the source is in neither place.
- Each phone keeps a heartbeat so the relay knows whether it is fresh.
- The admin UI includes a live Diagnostic Assistant and real-time device updates.

### Relay environment variables

- `OBS_ADDRESS` - OBS websocket address, default `ws://localhost:4455`
- `OBS_PASSWORD` - OBS websocket password if enabled
- `SOURCE_NAME` - default Browser Source name, default `Browser Full`
- `ADMIN_PORT` - relay admin port, default `3000`
- `POLL_ON_START` - set to `false` to skip the first tally scan
- `DEVICE_STALE_MS` - freshness window in milliseconds, default `10000`
- `DEVICE_LATENCY_HISTORY_LIMIT` - number of latency samples kept per device, default `12`

### Relay files

- [obs-relay/relay.js](obs-relay/relay.js) - OBS connection, device registry, tally posting, diagnostics, live admin events
- [obs-relay/admin/index.html](obs-relay/admin/index.html) - admin dashboard
- [obs-relay/devices.json](obs-relay/devices.json) - local device registry storage
- [obs-relay/start-relay.bat](obs-relay/start-relay.bat) - Windows launch helper

### Relay admin page

Open:

```text
http://localhost:3000
```

From there you can:

- register a phone manually
- change its source name
- view freshness and latency
- see a live diagnostic summary
- delete stale devices

## Android App

The Android app is the phone-side camera bridge and local tally endpoint.

### Android behavior

- It hosts a local HTTP server on port `8787`.
- It registers itself with the relay using its callback URL.
- It sends heartbeat pings so the relay can keep the device fresh.
- It receives tally updates from the relay and updates the phone UI.
- It supports the native camera, exposure, focus, framing, and MJPEG output for OBS.

### Android files

- [android-webcam-bridge](android-webcam-bridge) - Android project root
- [android-webcam-bridge/app/src/main/java/com/raphael/androidwebcambridge/bridge/BridgeViewModel.kt](android-webcam-bridge/app/src/main/java/com/raphael/androidwebcambridge/bridge/BridgeViewModel.kt) - relay registration and heartbeat logic
- [android-webcam-bridge/app/src/main/java/com/raphael/androidwebcambridge/bridge/LocalBridgeServer.kt](android-webcam-bridge/app/src/main/java/com/raphael/androidwebcambridge/bridge/LocalBridgeServer.kt) - local HTTP bridge server

### Android setup notes

- Use the relay host in the form `http://<relay-ip>:3000`.
- Each phone can keep its own relay registration.
- If the phone changes IP, reopen the app or register again.

## OBS Setup

Use OBS WebSocket v5 and set the Browser Source name you want the relay to track.

### Recommended OBS settings

- OBS websocket port: `4455`
- Browser Source name: a stable name like `Browser Full` or `phone1`
- Each phone may map to a different source name if you want separate tallies

## Latency

The admin table now shows a per-device tally latency value and a compact history sparkline.

If you need very low latency, keep in mind the full path includes:

- OBS scene change detection
- relay processing
- network transfer to the phone
- phone-side HTTP handling and camera/UI work

The relay now uses keep-alive HTTP connections, and the phone bridge answers tally requests immediately before applying the update in the background.

## Troubleshooting

- If the relay does not connect to OBS, confirm websocket v5 is enabled and the port is `4455`.
- If a phone does not register, confirm the relay host is reachable from the phone.
- If a phone is stale, save the relay host again on the phone.
- If tally never changes, check that the source name matches the OBS Browser Source exactly.
- If latency is still high, the bottleneck is usually the phone-side bridge or the network path rather than the admin UI.

## Repo Layout

- [android-webcam-bridge](android-webcam-bridge) - Android camera bridge app
- [obs-relay](obs-relay) - OBS tally relay and admin UI
- [server.mjs](server.mjs) - top-level helper script
- [public](public) - browser prototype assets

## Notes

This repository now treats the root README as the main documentation entry point. The old per-folder README and HOWTO files have been folded into this file so there is one place to start.