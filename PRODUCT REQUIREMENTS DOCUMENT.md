CamBridge — Product Requirements Document

---

Overview
CamBridge is a local network wireless camera monitoring and control system built for professional and semi-professional video production. It uses Android phones as wireless cameras, streaming H264 video over SRT directly into OBS on a laptop. A companion web control panel is embedded inside OBS as a browser dock, giving the operator full remote control over camera settings, tally state, and connection monitoring without leaving OBS.

---
Problem Statement

Current solutions for monitoring and controlling a phone camera from a laptop introduce significant lag, require physical access to the phone, or depend on expensive dedicated hardware. CamBridge solves this by combining low latency SRT video ingest with a fully integrated OBS control panel, running entirely on a local WiFi network with no internet dependency.

---
Target Users

Primary: Videographers and directors who use Android smartphones as cameras on professional or semi-professional shoots and run OBS as their production software.

Secondary: Content creators, live streamers, and journalists who need a wireless monitor and control setup without expensive dedicated hardware.

---
Goals

Stream phone camera feed into OBS with under 80ms end to end latency on a local WiFi network. Give the laptop operator full remote control over camera settings from inside OBS. Support up to four phones simultaneously. Run entirely on local WiFi with no internet connection required. Be simple enough to set up on a live set in under two minutes.

---
Non Goals

This system will not stream over the internet or mobile data. It will not record or store any video footage — that is OBS's responsibility. It will not support non-phone cameras. It will not replace a full broadcast tally system with hardware integration. It will not support iOS in v1.

---
System Architecture

The phone is the camera node and stream source. OBS on the laptop is the video ingest and display layer. A Next.js app running on the laptop is the relay and control server. The operator interacts with the Next.js control panel embedded as a browser dock inside OBS.

Video path: Phone → SRT → OBS Media Source → OBS scene.

Control path: Operator UI (OBS browser dock) → WebSocket → Next.js relay server → WebSocket → Phone → Camera2 API.

Tally path: Operator toggles tally in UI → Next.js relay → WebSocket → Phone → full screen tally overlay on phone display.

---
Core Features

Feature 1 — Low Latency SRT Video

The phone captures video using Camera2, encodes H264 in hardware via MediaCodec, muxes into MPEG-TS, and streams over SRT in caller mode to OBS. OBS listens on a configurable SRT port and ingests the stream as a Media Source. Target end to end latency is under 80ms on a local WiFi network. Each phone streams to its own dedicated OBS Media Source on a separate port. Stream adapts bitrate to network conditions, prioritising latency over quality when bandwidth drops.

Feature 2 — Camera Controls from OBS Dock

The operator controls the following camera settings from the OBS browser dock in real time. All changes are sent over WebSocket and applied immediately on the phone via Camera2.

ISO: slider with manual value input and common preset values.

Shutter speed: slider with standard cinematic values such as 1/50, 1/100, 1/250.

White balance: slider with Kelvin value and presets such as daylight, tungsten, cloudy.

Focus: manual focus slider. Operator can also click a point on the OBS video feed to set focus to that area — coordinates are mapped back to camera sensor space and sent to the phone.

Zoom: digital zoom slider. Zoom is applied as a CSS transform on the OBS video display only. The full frame is always streamed from the phone.

Control changes are reflected in the UI immediately and show a confirmation state when the phone acknowledges the change.

Feature 3 — Tally Light

A tally indicator is visible on both the phone screen and the OBS dock at all times. Two states: live shown in red, standby shown in green. The operator toggles tally state from the dock. Tally syncs instantly to the phone over WebSocket. The phone displays a large full width colour banner so it is visible at a distance. Tally state is independent per connected phone.

Feature 4 — Connection Status

Both the phone and the OBS dock display live connection status at all times. Shows current latency in milliseconds updated every second. Shows WiFi signal strength. If latency exceeds 80ms a visible warning appears on both devices. If connection drops both sides show a disconnected state and automatically attempt to reconnect. A small per-session latency graph is shown in the dock so the operator can spot network degradation over time.

Feature 5 — Multiple Phone Support

Up to four phones can connect simultaneously. The OBS dock shows thumbnail previews of all connected phone feeds. The operator selects the active control target with a single click — camera controls and tally apply to the selected phone. Each phone is identified by a custom name set at connection time. Each phone streams to its own OBS Media Source on its own SRT port.

Feature 6 — Grid Overlays

The operator can toggle the following overlays on the OBS dock video preview: rule of thirds grid and broadcast safe area guides. Overlays are rendered via Canvas or CSS on the dock UI only and have zero effect on the SRT stream or OBS scene. Overlay opacity is adjustable. Multiple overlays can be active simultaneously.

---
User Flow

Phone side: Operator opens CamBridge app. Connection screen shows a QR code and the relay server IP and port. Once connected, the phone begins streaming SRT to OBS and enters camera mode showing tally state and connection status.

Laptop side: Operator starts the Next.js relay server. Opens OBS. Adds a Media Source per phone pointing at the SRT listener port. Opens the CamBridge browser dock inside OBS pointing at localhost. Scans the QR code or enters IP to pair each phone. Live feed appears in OBS, controls appear in the dock. Additional phones can be added at any time during the session.

---

Tech Stack

Phone: Kotlin. Camera2 API for manual camera controls. CameraX for preview and lifecycle management. MediaCodec for hardware H264 encoding. MPEG-TS muxer. libsrt for SRT transport. WebSocket client for control and tally messages. Ktor or NanoHTTPD for the local pairing endpoint and QR code serving.

Laptop relay and control server: Next.js. WebSocket server handling control message routing, tally state, and device registry. Serves the control panel UI. No video handling — video goes directly phone to OBS over SRT.

OBS integration: Media Source per phone with SRT listener URL. Browser dock pointing at localhost Next.js app for the control panel.

---

Technical Requirements

All communication is local WiFi only. No internet dependency. WebSocket connections between relay and phones are persistent for the session duration. SRT streams use low latency mode with minimal buffering. H264 encoder configured with no B-frames, small GOP, CBR rate control, and hardware low latency mode. OBS machine must be connected via ethernet, not WiFi. Phones connect over WiFi on the same local network. Next.js relay server runs on the OBS laptop.

---

Risks and Mitigations

WiFi congestion spikes latency above 80ms. Mitigation: visible warning shown immediately, dynamic bitrate reduction prioritises latency.

Camera2 manual control availability varies across Android devices. Mitigation: query camera characteristics at startup and disable unavailable controls in the UI gracefully.

SRT port conflicts on the OBS machine. Mitigation: ports are configurable per phone, defaults starting at 9998 incrementing per device.

OBS browser dock has limited screen real estate. Mitigation: control panel uses a compact layout with collapsible sections.

libsrt compilation and integration on Android adds build complexity. Mitigation: use a prebuilt AAR or evaluate third party wrappers before building from source.
---

Success Metrics

End to end latency consistently under 80ms on a standard local WiFi network. Camera control changes reflect on the phone within 50ms of operator input. Four phone session runs stably for a minimum of two hours. Setup time from app open to live view under two minutes. Runs without issues on Android 12 and above.
---