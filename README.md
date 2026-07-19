# StreamCam Pro

Android phone → professional OBS camera with tally, remote control, and focus peaking.

## Features

### Camera
- **Resolution presets**: 720p, 1080p, 1440p, 4K — analysis pipeline matches selected preset
- **Lens switching**: front/rear cameras, any physical lens on the device
- **Manual ISO**: 50–3200, slider on phone and relay admin panel
- **Manual shutter**: 1–1000 ms, slider on phone and relay admin panel
- **White balance**: Kelvin slider 2500–10000K on phone, Planckian approximation via `kelvinToRggbGains()`
- **Manual focus**: distance in diopters, auto-focus toggle
- **Exposure compensation**: +/- 6 EV
- **Dual-layer zoom**: physical lens zoom + independent digital crop
- **Face tracking**: ML Kit face detection at 30 FPS, click-to-follow in dashboard
- **Focus peaking**: real-time edge detection overlay on phone preview (4-direction Sobel-like, ~2ms per frame)
- **Volume key control**: assign volume keys to focus/zoom/ISO/shutter rails

### Streaming & OBS
- **MJPEG stream** at `/stream.mjpg` — drop into OBS Browser Source
- **Tally bridge**: OBS → relay → phone, borders turn red (PROGRAM) / yellow (PREVIEW) / green (IDLE)
- **obs-websocket v5** integration via Node.js relay
- **OBS Lua auto-start script**: `obs-relay.lua` launches relay when OBS starts

### Remote Control
- **Phone web dashboard** at `http://phone-ip:8787/dashboard`: live preview, all camera settings, relay registration
- **Relay admin panel** at `http://localhost:3000`: OBS status, per-phone tally, per-phone camera controls (ISO, WB, shutter, focus, AF toggle)
- **SSID-aware relay discovery**: caches relay host per WiFi network, auto-redirects when switching networks
- **UDP broadcast discovery** (port 9999) for zero-config phone finding on same subnet

### Networking
- SSID cache: remembers relay IP per WiFi network — switch between home and church without re-entering IP
- LAN IP detection skips 169.254.x.x (Tailscale) addresses
- Diagnostics endpoint at `/api/diagnostics` returns relay address, OBS status, device list

## Quick start

### Phone app
```powershell
cd android-webcam-bridge
./gradlew.bat assembleDebug
# install app/build/outputs/apk/debug/app-debug.apk on phone
```

### Relay
```powershell
cd obs-relay
npm install
npm start
```

Open `http://localhost:3000` to see the admin panel.

### OBS
1. Enable **obs-websocket v5** (Tools → WebSocket Server Settings, port 4455)
2. Add a **Browser Source** → URL: `http://phone-ip:8787/stream.mjpg`
3. For tally: point each phone at `http://relay-ip:3000` in the app's relay field, then tap Register

### Optional: auto-start relay with OBS
In OBS → Tools → Scripts → Add Scripts → select `obs-relay/obs-relay.lua`. Uses `script_path()` — no hardcoded paths.

## Repository layout

```
obs-relay/                          # Node.js relay
  relay.js                          #   main relay process
  obs-relay.lua                     #   OBS Lua auto-start script
  admin/index.html                  #   status panel + camera controls
  devices.json                      #   registered phone persistence

android-webcam-bridge/              # Android app (Kotlin/Compose)
  app/src/main/java/com/raphael/androidwebcambridge/
    bridge/
      BridgeViewModel.kt            #   state machine coordinating camera + relay + server
      CameraSessionController.kt    #   Camera2 pipeline: capture, analysis, focus peaking, WB gains
      LocalBridgeServer.kt          #   HTTP server: MJPEG stream + settings API
      BridgeModels.kt               #   data models: settings, state, tally, resolution presets
      RelayManager.kt               #   relay registration, heartbeat, SSID cache
      BridgeHtmlAssets.kt           #   web dashboard HTML (Kotlin strings)
    ui/
      CameraScreen.kt               #   main camera UI, Kelvin slider, focus peaking overlay
      components/
        BottomStrip.kt              #   status bar: tally, ISO, shutter, WB, lens
        SettingsTray.kt             #   settings drawer with all toggles
```

## API endpoints (phone, port 8787)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/state` | GET | Full bridge state + settings JSON |
| `/api/settings?iso=800` | GET | Update any setting field |
| `/api/tally?state=PROGRAM` | GET | Tally update from relay |
| `/stream.mjpg` | GET | MJPEG video stream |
| `/dashboard` | GET | Web dashboard |
| `/obs-bridge` | GET | OBS overlay page with tally indicator |

## Relay environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `OBS_ADDRESS` | `ws://localhost:4455` | OBS websocket address |
| `OBS_PASSWORD` | `` | OBS websocket password |
| `SOURCE_NAME` | `Browser Full` | Default OBS source to track |
| `ADMIN_PORT` | `3000` | Admin UI port |
| `POLL_ON_START` | `true` | Skip initial tally scan if `false` |

## Network requirements

- Phone and laptop on the **same WiFi subnet** (UDP broadcast + HTTP reachability)
- Works on home and church single-subnet WiFi out of the box
- Switching networks: SSID cache auto-redirects to known relay IP for current network
- Mobile hotspot: phone becomes a different subnet → use manual relay IP entry
