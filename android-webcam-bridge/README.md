# StreamCam Pro

This project turns your Android phone into a remote-controlled webcam for OBS and live streaming. Camera controls and preview are available from a browser-based dashboard.

## Key Features

### 1. Remote Dashboard
- **Live Preview**: MJPEG monitor directly in your browser.
- **Instant Controls**: Settings updates (ISO, shutter, focus, zoom) are sent to the phone as you change them.
- **Stable Sync**: Dashboard sliders won't "jump back" while you are interacting with them.

### 2. Software Framing
- **Digital Zoom**: The dashboard slider crops the image in software from 1x to 10x. Cropping reduces effective resolution; physical lens zoom is a separate control.
- **Digital Pan & Tilt**: Click and drag on the preview window to move your framing to any part of the sensor.
- **Software Processing**: Panning and zooming happen in software, bypassing system-level hardware limits.

### 3. AI Face Tracking (Follow Faces)
- **Detection**: Uses Google ML Kit to identify faces. Detection runs on a dedicated executor after the analysis frame is JPEG-encoded and its `ImageProxy` is closed. It runs on every fifth frame and is skipped while a previous detection is in flight, so it does not cap the streaming frame rate.
- **Dynamic Lock**: Click on a person's face in the dashboard preview to make the camera follow them automatically.
- **Smooth Framing**: The camera pans and tilts the software crop to keep the selected subject centered.

### 4. Full Manual Hardware Control
- **Manual Focus**: Set physical lens distance in diopters (or lock to Infinity) with an Auto Focus toggle.
- **Manual Exposure**: Dedicated ISO (100–3200 on the dashboard slider, up to 6400 via volume keys) and shutter speed controls (1–250 ms on the dashboard slider, up to 500 ms via volume keys). `0` means Auto.
- **Exposure Compensation**: Fine-tune brightness (-6 to +6 EV) when ISO and shutter are both in Auto mode.

### 5. Audio Intercom
- **Push-to-Talk (Phone)**: Hold the mic button on the phone to transmit audio to the browser dashboard through `/api/audio/out` (SSE).
- **Push-to-Talk (Browser)**: Hold the mic button on the dashboard to send audio to the phone speaker through `/api/audio/in`.
- **Director Push-to-Talk**: Speak to all phones from the relay admin page; audio goes through the relay WebSocket.
- **Half-Duplex**: Playback is automatically muted while transmitting to prevent echo.
- **Sample Rate**: 44100 Hz PCM 16-bit mono.
- **OBS**: Intercom audio is not routed into OBS as an audio source by default.

### 6. Streaming
- **MJPEG Output**: The actual transport is MJPEG over HTTP at `/stream.mjpg`, compatible with OBS "Browser Source".
- **Single JPEG encode**: Each frame is JPEG-encoded once at the crop size and sent directly; OBS scales it. Output dimensions follow the crop (`cw x ch`), while the resolution preset sets the analysis target resolution.
- **Background streaming**: Camera analysis is bound to the ViewModel's process lifetime; only the preview detaches when the Activity stops. Home or screen-off keeps MJPEG and tally alive. Process death, OEM battery killers, missing notification permission, and some camera HALs can still interrupt it (see [problems.md](problems.md)).
- **Ports**: Plain HTTP on `8787`; HTTPS on `8788` when TLS is available.
- **H.264/WebRTC**: Not implemented.

## Architecture

- **ViewModel**: State machine that coordinates camera hardware, AI detection, and network commands.
- **Camera Controller**: Video pipeline that performs software cropping, a single JPEG encode per frame, and AI face detection on a dedicated executor.
- **Local Server**: Lightweight HTTP server that delivers the dashboard, handles the MJPEG stream, and routes audio via SSE and HTTP endpoints.
- **Relay Manager**: Registers with the relay, heartbeats, caches the relay host per SSID, and receives tally over WebSocket.

## Permissions

The app requires the following runtime permissions:
- **Camera**: Required for video streaming.
- **Record Audio**: Required for Push-to-Talk intercom.

## How to use

1.  Open the app on your Android phone and grant camera and microphone permissions.
2.  Open the **Settings** panel to find your local IP address.
3.  On your laptop, go to `http://<phone-ip>:8787/dashboard` (or `https://<phone-ip>:8788/dashboard` when TLS is available).
4.  In OBS, add a **Browser Source** pointing to `http://<phone-ip>:8787/stream.mjpg`. Use `https://<phone-ip>:8788/stream.mjpg` only when TLS is available.
5.  Use the **Hold to Speak** buttons on both the phone and dashboard for two-way audio intercom.

For tally, open the dashboard's **Relay** section and enter the relay host. Source names are matched automatically: the relay maps each OBS Browser Source URL hostname to the phone with the same callback URL hostname, and the admin page shows an `AUTO` badge. If no source matches, the admin page shows an amber hint; set **Source Name** manually in the dashboard as the fallback.

Streaming continues when the app is backgrounded or the screen is off: camera analysis is bound to the ViewModel's process lifetime, only the preview detaches from the Activity, and a foreground service with a Stop action keeps the process alive. See [problems.md](problems.md) for remaining caveats (process death, OEM battery killers, notification permission, camera HAL behavior).

## Device Support
- **Resolution**: Supports up to 4K internal capture.
- **Hardware**: Manual focus and exposure support depends on your specific device's camera module.
- **Multi-Lens**: Switch between front and rear cameras directly from the dashboard.
