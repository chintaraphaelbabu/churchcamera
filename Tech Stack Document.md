CamBridge — Tech Stack Document
---
Overview

CamBridge is split into three distinct layers: the Android camera node running on the phone, the relay and control server running on the laptop, and the OBS integration layer also on the laptop. Each layer has its own responsibilities and communicates with the others over local WiFi only.

---
Layer 1 — Android Camera Node

Language: Kotlin. No Java, no React Native, no cross-platform framework. Native Android only for full access to low level camera and media APIs.

Camera capture: CameraX for lifecycle management, preview surface, and session handling. Camera2 API directly for manual control of ISO, shutter speed, white balance, and focus. CameraX wraps Camera2 but exposes manual controls through Camera2Interop, which allows injecting Camera2 capture request parameters into a CameraX session without managing the full Camera2 session lifecycle manually.

Encoding: MediaCodec with hardware H264 encoder. Configured with no B-frames, small GOP size (around 30 frames), CBR rate control, and BITRATE_MODE_CBR. Surface input mode so frames go directly from the camera to the encoder without a CPU copy. Low latency encoder flag enabled where the device supports it.

Muxing: MPEG-TS mux written manually or using a lightweight library. MPEG-TS is the standard container for SRT transport and is what OBS expects on ingest.

Transport: libsrt for SRT streaming. Phone operates in caller mode, connecting to the OBS listener. Use a prebuilt Android AAR if available, otherwise compile Haivision libsrt via CMake with the Android NDK. SRT configured in low latency mode with minimal receive buffer.

Control and tally messaging: OkHttp WebSocket client. Persistent connection to the Next.js relay server for the duration of the session. Receives camera control commands, sends acknowledgements and status updates back.

Pairing endpoint: Ktor embedded server or NanoHTTPD. Lightweight HTTP server running on the phone serving the pairing info — device name, IP address, SRT port — and generating the QR code for the laptop to scan. This is the only server component on the phone and it only handles the initial pairing handshake.

Minimum Android version: Android 12 (API 31). Camera2 manual controls, MediaCodec low latency mode, and hardware H264 are reliable from this version onwards.

Build system: Gradle with Kotlin DSL.

---
Layer 2 — Relay and Control Server

Runtime: Node.js 20 LTS.

Framework: Next.js 14 with the App Router. Serves the operator control panel UI and hosts the WebSocket relay server in the same process.

WebSocket server: ws library running as a custom server alongside Next.js. Handles persistent connections from all phones and from the browser dock UI. Routes camera control messages from the operator to the correct phone. Broadcasts tally state changes to all relevant devices. Maintains a device registry of connected phones including name, IP, SRT port, latency, and connection state.

UI framework: React (via Next.js). Tailwind CSS for styling. The control panel is designed for the narrow vertical layout of an OBS browser dock.

Overlay rendering: HTML Canvas for rule of thirds and safe area guide overlays rendered on top of the video preview thumbnail in the dock UI.

QR code scanning: a browser based QR code library such as jsQR or html5-qrcode for scanning the phone pairing QR code from the laptop camera or for decoding a QR code image.

State management: React state and context for UI state. No Redux or external state library needed given the scope.

Package manager: pnpm.

TypeScript: yes, throughout. Strict mode enabled.

---
Layer 3 — OBS Integration

OBS version: OBS Studio 30 or later.

Video ingest: Media Source per connected phone. Each Media Source configured with an SRT listener URL in the format srt://0.0.0.0:PORT?mode=listener. Default ports start at 9998 and increment per device: 9998, 9999, 10000, 10001 for up to four phones.

Control panel integration: Custom Browser Dock inside OBS pointing at http://localhost:3000 (or whichever port Next.js runs on). Set via View > Docks > Custom Browser Docks. The dock renders the full Next.js control panel UI inside OBS as a native docked panel.

No OBS plugin required. Everything is handled through native OBS Media Sources and the built-in browser dock feature.

---
Communication Architecture

Video: Phone → SRT (UDP) → OBS Media Source. Direct, no relay, no intermediate processing.

Control commands: Operator UI (OBS dock) → WebSocket → Next.js relay server → WebSocket → Phone → Camera2 API.

Tally: Operator toggles in UI → Next.js relay → WebSocket → Phone → full screen overlay.

Status and heartbeats: Phone → WebSocket → Next.js relay → UI update every second.

Pairing: Phone HTTP server serves pairing data → operator scans QR code in dock UI → dock UI connects WebSocket to relay → relay registers phone.

---
Key Libraries and Dependencies

Android side: androidx.camera (CameraX), android.hardware.camera2 (Camera2), android.media.MediaCodec, libsrt (Haivision, via NDK or prebuilt AAR), OkHttp (WebSocket client), Ktor server core (pairing endpoint), ZXing or QRGen (QR code generation).

Laptop side: Next.js 14, React 18, Tailwind CSS, ws (WebSocket server), jsQR or html5-qrcode (QR scanning), pnpm.

---
Development Environment

Android development: Android Studio Hedgehog or later. Android 12+ physical device for testing — emulator cannot test camera or SRT transport reliably.

Laptop development: VS Code or any Node-compatible editor. Node.js 20 LTS. pnpm installed globally. OBS Studio installed for integration testing.

Version control: Git. Monorepo structure with two top level directories — android for the Kotlin app and web for the Next.js app.

---
What This Stack Deliberately Avoids
No React Native. Native Kotlin only for reliable Camera2 and MediaCodec access.
No WebRTC. SRT gives lower and more consistent latency on a LAN and OBS supports it natively.
No external media server such as MediaMTX or FFmpeg process management. Video goes directly from the phone to OBS over SRT.
No cloud services, no internet dependency, no external APIs.
No OBS plugin development. The browser dock and Media Source cover everything needed.
---