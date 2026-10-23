# Spec: Low-Latency Direct-Stream + Auto-Tally ChurchCam

## Problem Statement

ChurchCam (Android → OBS camera) currently has ~250 ms video latency (vdo.ninja baseline) through the relay's MJPEG proxy (`/api/stream/:id`) and requires manual relay discovery (UDP broadcast on port 9999) plus manual device registration (`/api/register`). The user wants under 100 ms latency and fully automatic tally, eliminating the manual setup steps.

## Solution

Replace the relay MJPEG proxy with a direct stream protocol, switch relay discovery from UDP broadcast to mDNS (`_churchcam._tcp`), and make tally automatic via the existing WebSocket connection once the phone discovers the relay automatically.

## User Stories

1. As a director, I want the camera stream to reach OBS with under 100 ms latency, so that live direction feels real-time.
2. As a director, I want to remove the relay's MJPEG proxy (`/api/stream/:id`), so that OBS connects directly to the phone and latency drops.
3. As a camera operator, I want the phone to discover the relay via mDNS (`_churchcam._tcp.local`) instead of UDP broadcast, so that switching networks is automatic.
4. As a camera operator, I want the phone to register with the relay automatically upon WebSocket auth, so that I never have to tap "Register" or enter an IP.
5. As a director, I want tally (PROGRAM / PREVIEW / IDLE) pushed automatically via the persistent WebSocket, so that the tally border updates without manual configuration.
6. As a developer, I want the stream protocol to be the most reliable option (WebSocket binary frames), so that we avoid complex WebRTC signaling while staying under 100 ms.
7. As an admin, I want the relay admin panel (`/api/devices`, `/api/diagnostics`) to work unchanged, so that monitoring is preserved.
8. As a developer, I want the phone's `LocalBridgeServer.kt` stream endpoint updated to serve the new protocol, so that the change is localized.
9. As a user, I want the existing `obs-relay.lua` and OBS Browser Source setup preserved, so that migration is minimal.
10. As a camera operator, I want volume-key focus/zoom controls and face tracking unchanged, so that core camera features are unaffected.

## Implementation Decisions

- **Protocol choice**: WebSocket binary frames (recommended: reliable, simple signaling, <100ms achievable). The stream endpoint in `LocalBridgeServer.kt` will serve binary frames over a persistent WebSocket or optimized binary stream instead of multipart MJPEG. This avoids WebRTC STUN/ICE complexity.
- **Relay proxy removal**: Remove `/api/stream/:id` endpoint in `relay.js`. OBS Browser Source points directly at `https://<phone-ip>:8787/stream` or a new WebSocket endpoint.
- **Discovery change**: Replace UDP broadcast (`port 9999`, `RelayDiscovery.kt`) with mDNS advertisement on relay (`_churchcam._tcp`) and mDNS scanning on phone (`RelayManager.kt`).
- **Auto-registration**: Once the phone connects its WebSocket to the relay (`auth` message with `deviceId`), the relay treats the connection as registered (no separate `/api/register` HTTP call required). The `upsertDevice` logic stays but is triggered by `auth` rather than `POST /api/register`.
- **Tally mechanism**: Keep `pushTallyViaWs()` and `evaluateTally()` unchanged in logic; they just operate over the auto-connected WebSocket instead of a manually registered one.
- **Latency measurement**: The existing `rtt` / `latencyMs` tracking in `relay.js` (`upsertDevice`) is preserved and used to verify <100ms targets.
- **Direct stream architecture**: OBS connects directly to the phone stream URL (cached via SSID-aware `RelayManager.kt` or discovered via mDNS). The relay no longer proxies video frames.
- **Backup compatibility**: If direct stream fails, the relay's existing proxy can remain as a fallback (optional, but recommended to keep for debugging).
- **Module changes**:
  - `LocalBridgeServer.kt`: Stream endpoint protocol change.
  - `relay.js`: Remove proxy endpoint, add mDNS (`mdns` package or native `dns-sd`), keep WebSocket tally.
  - `RelayDiscovery.kt`: Replace UDP listener with mDNS scanner.
  - `RelayManager.kt`: Update to use `.local.` URLs and automatic auth.
  - `README.md`: Update OBS Browser Source instructions.

## Testing Decisions

- A good test verifies the external behavior: OBS receives frames with latency <100ms (measured by `relay.js` `latencyMs` or external timestamp comparison), tally updates within 50ms of scene change, and phone connects without manual IP entry.
- Test seams:
  - Phone server (`LocalBridgeServer.kt`) — external behavior is stream response and WebSocket auth.
  - Relay (`relay.js`) — external behavior is mDNS advertisement, WebSocket tally push, and removed proxy.
  - Phone discovery (`RelayDiscovery.kt`) — external behavior is finding relay `.local.` service.
- Prior art: Existing smoke tests (`SmokeTest.kt`, `smoke.js`) test basic connectivity. Add latency assertions there.

## Out of Scope

- Changing camera pipeline (Camera2, focus peaking, ISO/shutter, white balance) — these stay unchanged.
- Changing OBS WebSocket integration (`obs-websocket v5`) — tally source tracking remains the same.
- Changing audio broadcast (`/api/audio/broadcast`) — stays unchanged.
- Changing mobile hotspot/network switch behavior — SSID cache logic preserved, just augmented with mDNS.
- Changing dashboard UI (`/dashboard`, admin HTML) — preserved.
- Changing TLS/HTTPS setup — preserved.

## Further Notes

- Protocol recommendation: WebSocket binary frames over `wss://` or `ws://`. Most reliable for LAN use without ICE/STUN overhead.
- Issue tracker: Not configured (`/setup-matt-pocock-skills` not completed). Spec written locally as `spec.md`.
- Recommended stream endpoint: Replace `/stream.mjpg` with `/stream.ws` or binary `/stream` served by `LocalBridgeServer.kt`.
- If WebSocket binary proves unreliable, fallback is optimized MJPEG with reduced proxy overhead (direct connection, no relay hop).
