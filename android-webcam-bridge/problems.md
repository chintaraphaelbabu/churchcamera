# Known Issues & Technical Limitations

This document tracks current known issues, hardware limitations, and behavior quirks of the Android Webcam Bridge.

## 1. OBS Studio Mode Tally
- **Kickstart Required**: On some OBS versions, the tally light (Red/Yellow border) may not appear immediately after adding the browser source.
  - **Workaround**: Click the "Eye" icon in OBS to hide and then show the source once to initialize the OBS visibility API.
- **Occlusion**: If the camera source is completely covered by a full-screen image or another video layer, OBS may stop sending "Active" signals to save power, which could cause the tally to turn yellow or green even while live.

## 2. Camera Lifecycle and Backgrounding
- **Camera is Activity-bound**: CameraX is bound to the Activity lifecycle. Backgrounding the app unbinds the camera and stops the stream. The foreground service keeps the process alive but does not keep the camera streaming.
- **Tally pauses in background**: `ON_STOP` pauses the relay heartbeat and closes the tally WebSocket. They restart on `ON_START`/`ON_RESUME`.

## 3. No Adaptive Quality
- **Fixed settings**: Resolution and frame rate are selected by the user. There is no adaptation to network conditions or device thermal state.

## 4. MJPEG Transport Costs
- **Bandwidth**: MJPEG sends every frame as a JPEG, so bandwidth use is high. H.264/WebRTC is not implemented.
- **Double encoding**: Each frame may be JPEG-encoded twice when the crop size differs from the target resolution (once for the crop, once after scaling).

## 5. Face Detection Throttling
- **Every fifth frame**: Face detection runs on every fifth analysis frame, not at a literal 30 FPS.
- **ImageProxy held**: The analysis ImageProxy is held until ML Kit finishes, which caps the analysis frame rate.

## 6. Manual Exposure Fallback
- **Forced values**: Turning AE off with only ISO or only shutter set forces the other value to a hardcoded fallback (ISO 400 / 20 ms).

## 7. No Authentication
- **Relay and phones are open on the LAN**: The relay admin UI, relay WebSocket, and director microphone listen on all interfaces with no authentication. The phone HTTP APIs have no authentication either. Anyone on the LAN can view, control, stream from, or speak through the devices.

## 8. Discovery Limitations
- **No real mDNS/DNS-SD**: The relay sends a non-standard plaintext packet to `224.0.0.251:5353`, but the phone has no real mDNS resolver. Discovery relies on UDP broadcast on port 9999 plus the SSID → relay-host cache.
- **Blocked networks**: Guest/enterprise WiFi often blocks broadcast and enables client isolation. Manual relay IP entry is the fallback.