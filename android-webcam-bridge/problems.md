# Known Issues & Technical Limitations

This document tracks current known issues, hardware limitations, and behavior quirks of the Android Webcam Bridge.

## 1. OBS Studio Mode Tally
- **Kickstart Required**: On some OBS versions, the tally light (Red/Yellow border) may not appear immediately after adding the browser source.
  - **Workaround**: Click the "Eye" icon in OBS to hide and then show the source once to initialize the OBS visibility API.
- **Occlusion**: If the camera source is completely covered by a full-screen image or another video layer, OBS may stop sending "Active" signals to save power, which could cause the tally to turn yellow or green even while live.

## 2. Camera Lifecycle and Backgrounding
- **Process-lifetime capture**: Camera analysis is bound to the ViewModel's process lifecycle; only the preview is detached when the Activity stops. Pressing Home or turning the screen off keeps MJPEG streaming and the tally heartbeat alive. The foreground service keeps the process alive and its notification has a **Stop** action; the wake lock is held for the whole stream.
- **Process death**: If Android kills the process while backgrounded, the camera and stream stop until the app is reopened.
- **OEM battery killers**: Aggressive battery management (for example OxygenOS or MIUI) may still kill the process. Request the battery optimization exemption in the app.
- **Notification permission**: Android 13+ notification permission is not requested, so the foreground notification and its Stop action may be hidden.
- **Camera HAL behavior**: Some OEM camera HALs close the camera on screen-off despite the wake lock.

## 3. No Adaptive Quality
- **Fixed settings**: Resolution and frame rate are selected by the user. There is no adaptation to network conditions or device thermal state.

## 4. MJPEG Transport Costs
- **Bandwidth**: MJPEG sends every frame as a JPEG, so bandwidth use is high. H.264/WebRTC is not implemented.
- **Variable output size**: Each frame is JPEG-encoded once at the crop size and emitted directly; OBS scales it. Output dimensions therefore follow the crop (`cw x ch`), not always the selected resolution preset. The preset sets the analysis target resolution.

## 5. Face Detection Throttling
- **Every fifth frame**: Face detection runs on every fifth analysis frame, not at a literal 30 FPS.
- **Dedicated executor**: Detection runs on its own executor after the `ImageProxy` is closed and the frame is JPEG-encoded. If a detection is already in flight, new frames are skipped rather than queued, so it no longer caps the streaming frame rate.
- **Crop-relative coordinates**: Detection runs on the cropped/zoomed JPEG, so faces outside the current crop are not detected and face coordinates are relative to the crop, not the full sensor.

## 6. Manual Exposure Fallback
- **Forced values**: Turning AE off with only ISO or only shutter set forces the other value to a hardcoded fallback (ISO 400 / 20 ms).

## 7. No Authentication
- **Relay and phones are open on the LAN**: The relay admin UI, relay WebSocket, and director microphone listen on all interfaces with no authentication. The phone HTTP APIs have no authentication either. Anyone on the LAN can view, control, stream from, or speak through the devices.

## 8. Discovery Limitations
- **No real mDNS/DNS-SD**: The relay sends a non-standard plaintext packet to `224.0.0.251:5353`, but the phone has no real mDNS resolver. Discovery relies on UDP broadcast on port 9999 plus the SSID → relay-host cache.
- **Blocked networks**: Guest/enterprise WiFi often blocks broadcast and enables client isolation. Manual relay IP entry is the fallback.