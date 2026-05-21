# OBS Plugin for WebRTC

This folder is an initial scaffold for a native OBS source plugin that can consume a WebRTC stream from the phone bridge.

## Goal

Create an OBS source that:

- connects directly to a WebRTC-capable phone bridge endpoint
- performs SDP offer/answer signaling with `/api/webrtc/offer`
- receives a remote video track and renders it inside OBS
- avoids the Browser Source layer for lower latency

## Structure

- `CMakeLists.txt` — plugin build script
- `src/plugin-main.cpp` — OBS module registration
- `src/WebRTCSource.h` — source type definition
- `src/WebRTCSource.cpp` — plugin source implementation skeleton

## Next steps

1. Add native WebRTC client logic using a suitable library.
2. Implement signaling and ICE support.
3. Render decoded video frames into OBS through the source pipeline.
4. Add plugin settings for bridge URL, offer timeout, and logging.

## Notes

This is only a starting point. OBS plugins are typically written in C/C++ and depend on the OBS plugin API.

If you want, I can continue by adding the actual WebRTC integration plan and a minimal code design for the bridge endpoint.
