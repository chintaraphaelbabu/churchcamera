CamBridge — System Architecture Document

---
Overview

CamBridge is a three-layer system. The Android phone is the camera node and stream source. The laptop runs OBS for video display and a Next.js relay server for control and tally. The operator interacts with a control panel embedded as a browser dock inside OBS.

There are two completely separate communication paths: the video path and the control path. They never share a transport layer.

---
High-Level System Diagram
┌─────────────────────────────────────────────────────────────────┐
│                        LOCAL WIFI NETWORK                       │
│                                                                 │
│  ┌──────────────────┐                ┌───────────────────────┐  │
│  │   ANDROID PHONE  │                │       LAPTOP          │  │
│  │                  │                │                       │  │
│  │  Camera2 / CameraX│──SRT (UDP)───>│  OBS Media Source     │  │
│  │  MediaCodec H264  │                │  OBS Scene / Display  │  │
│  │  MPEG-TS muxer    │                │                       │  │
│  │  libsrt           │                │  ┌─────────────────┐  │  │
│  │                  │                │  │  Next.js Relay  │  │  │
│  │  OkHttp WS client│<──WebSocket───>│  │  Server         │  │  │
│  │  Tally overlay   │                │  │  WebSocket hub  │  │  │
│  │  Camera controls │                │  │  Device registry│  │  │
│  │                  │                │  └────────┬────────┘  │  │
│  │  Ktor pairing    │                │           │           │  │
│  │  server (HTTP)   │                │  ┌────────▼────────┐  │  │
│  └──────────────────┘                │  │  OBS Browser    │  │  │
│                                      │  │  Dock (UI)      │  │  │
│                                      │  │  localhost:3000  │  │  │
│                                      │  └─────────────────┘  │  │
│                                      └───────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
---

Multi-Phone Diagram (up to 4 cameras)

┌──────────────┐  SRT :9998  ┌─────────────────────────────────┐
│   PHONE 1    │────────────>│  OBS Media Source 1             │
│  Camera 1    │             │                                 │
└──────┬───────┘             │  OBS Media Source 2             │
       │ WebSocket           │                                 │
┌──────┴───────┐  SRT :9999  │  OBS Media Source 3             │
│   PHONE 2    │────────────>│                                 │
│  Camera 2    │             │  OBS Media Source 4             │
└──────┬───────┘             └─────────────────────────────────┘
       │ WebSocket                          │
┌──────┴───────┐  SRT :10000               │ OBS Browser Dock
│   PHONE 3    │────────────>  ┌───────────▼──────────────────┐
│  Camera 3    │               │     Next.js Relay Server      │
└──────┬───────┘               │                              │
       │ WebSocket             │  Device registry             │
┌──────┴───────┐  SRT :10001  │  Control message routing     │
│   PHONE 4    │────────────>  │  Tally state management      │
│  Camera 4    │  WebSocket    │  Heartbeat and status        │
└──────────────┘               └──────────────────────────────┘


---
Communication Paths

Path 1 — Video (Phone → OBS)

Camera2 sensor
     │
     ▼
CameraX capture session
     │  (Surface input — no CPU copy)
     ▼
MediaCodec H264 hardware encoder
  - No B-frames
  - Small GOP (~30 frames)
  - CBR rate control
  - LOW_LATENCY mode
     │
     ▼
MPEG-TS muxer
     │
     ▼
libsrt (caller mode)
  srt://LAPTOP_IP:PORT?mode=caller
     │
     │  UDP over WiFi
     ▼
OBS Media Source (listener mode)
  srt://0.0.0.0:PORT?mode=listener
     │
     ▼
OBS scene compositor
     │
     ▼
Operator display


This path is entirely UDP. No TCP, no relay, no intermediate processing. The video never touches the Next.js server.

---
Path 2 — Control (Operator → Phone)


Operator adjusts control in OBS dock UI
     │
     ▼
Next.js browser (OBS browser dock)
     │  WebSocket message
     ▼
Next.js relay server (ws server)
  - Identifies target phone by device ID
  - Routes message to correct WebSocket connection
     │  WebSocket message
     ▼
OkHttp WebSocket client on phone
     │
     ▼
Camera2 CaptureRequest update
  - ISO
  - Shutter speed
  - White balance
  - Focus distance
     │
     ▼
Acknowledgement sent back to relay
     │
     ▼
UI confirmation shown in dock


---

Path 3 — Tally (Operator → Phone)


Operator clicks tally toggle in dock UI
     │
     ▼
Next.js relay server
  - Updates tally state in device registry
  - Broadcasts to target phone (or all phones)
     │  WebSocket message { type: "tally", state: "live" | "standby" }
     ▼
Phone WebSocket client
     │
     ▼
Tally overlay updated on phone screen
  - LIVE = full width red banner
  - STANDBY = full width green banner


---

Path 4 — Pairing (Initial Connection)


Phone starts Ktor HTTP server on port 8080
  - Serves device name, IP, SRT port
  - Generates and serves QR code image

Operator opens OBS dock (localhost:3000)
  - Clicks "Add Camera"
  - Scans QR code using device camera
    OR manually enters phone IP

Dock sends pairing request to Next.js relay
  - Relay opens WebSocket connection to phone
  - Phone registers in device registry
  - Relay confirms to dock UI
  - Phone transitions to camera mode
  - SRT stream begins


---

Component Responsibilities

Android App

| Component | Responsibility |
|---|---|
| CameraX session | Lifecycle, preview surface, session management |
| Camera2Interop | Manual controls — ISO, shutter, WB, focus |
| MediaCodec | Hardware H264 encoding, surface input |
| MPEG-TS muxer | Packetising encoded frames for SRT transport |
| libsrt | SRT stream transport to OBS, caller mode |
| OkHttp WS | Persistent control and tally WebSocket connection |
| Ktor server | Pairing endpoint and QR code serving |
| Tally UI | Full screen overlay driven by WebSocket messages |

---

Next.js Relay Server

| Component | Responsibility |
|---|---|
| ws WebSocket server | Hub for all phone and dock connections |
| Device registry | Tracks connected phones, names, ports, status |
| Control router | Routes operator commands to the correct phone |
| Tally state manager | Holds and broadcasts tally state per device |
| Heartbeat monitor | Detects dropped connections, triggers reconnect |
| Pairing handler | Accepts new phone connections, registers devices |
| UI server | Serves the control panel to the OBS browser dock |

---

OBS

| Component | Responsibility |
|---|---|
| Media Source (×4) | SRT listener, one per phone, dedicated port |
| Browser Dock | Renders Next.js control panel inside OBS |
| Scene compositor | Displays video feeds in the production layout |

---

Port Map

| Service | Protocol | Port | Direction |
|---|---|---|---|
| SRT — Camera 1 | UDP | 9998 | Phone → OBS |
| SRT — Camera 2 | UDP | 9999 | Phone → OBS |
| SRT — Camera 3 | UDP | 10000 | Phone → OBS |
| SRT — Camera 4 | UDP | 10001 | Phone → OBS |
| Next.js web server | TCP | 3000 | Laptop (internal) |
| WebSocket relay | TCP | 3000 | Phones ↔ Relay |
| Pairing HTTP server | TCP | 8080 | Phone (internal) |

All ports are on the local network only. Nothing is exposed to the internet.

---

Data Flow Summary


                    VIDEO (UDP/SRT)
Phone ─────────────────────────────────────────> OBS

                    CONTROL (WebSocket)
Dock UI <──────> Next.js Relay <──────> Phone

                    TALLY (WebSocket)
Dock UI ───────> Next.js Relay ───────> Phone

                    PAIRING (HTTP)
Phone HTTP server <─────── Dock UI (QR scan)
        └──────────────> Relay (WebSocket register)


---

Startup Sequence


1. Operator starts Next.js relay server on laptop
        └── WebSocket server begins listening on port 3000

2. Operator opens OBS
        └── Adds Media Sources (one per phone, SRT listener URLs)
        └── Opens CamBridge browser dock → localhost:3000

3. Operator opens CamBridge app on phone
        └── Ktor pairing server starts on port 8080
        └── QR code displayed on phone screen

4. Operator scans QR code in dock UI
        └── Dock sends pairing request to relay
        └── Relay opens WebSocket to phone
        └── Phone registers in device registry
        └── Phone begins SRT stream to OBS port
        └── OBS Media Source receives stream
        └── Dock UI shows phone as connected with live status

5. Repeat steps 3–4 for each additional phone

6. Session is live — operator controls cameras from dock,
   video plays in OBS scene


---

Failure Modes and Recovery

| Failure | Detection | Recovery |
|---|---|---|
| Phone WiFi drops | Heartbeat timeout (5s) | Phone auto-reconnects WebSocket, resumes SRT |
| SRT stream freezes | OBS Media Source shows no signal | Phone detects encoder stall, restarts stream |
| WebSocket drops | Heartbeat timeout on relay | Both sides attempt reconnect with exponential backoff |
| Latency exceeds 80ms | RTT measurement in heartbeat | Warning shown in dock, bitrate reduced automatically |
| Phone thermal throttle | Frame rate drop detected | Warning shown in dock |

---

What Deliberately Does Not Exist
There is no video processing in the relay server. Video goes directly phone to OBS and the relay never touches it.
There is no cloud component. No external API calls. No internet dependency of any kind.
There is no OBS plugin. Everything uses native OBS features — Media Source and Browser Dock.
There is no TCP in the video path. SRT uses UDP. TCP buffering would kill the latency target.