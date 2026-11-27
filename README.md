# ChurchCam

Android phone → professional OBS camera with tally, remote control, and focus peaking.

## Features

### Camera
- **Resolution presets**: 720p, 1080p, 1440p, 4K
- **Frame rate**: 24, 30, or 60 FPS
- **Lens switching**: front/rear cameras and any physical lens on the device
- **Manual ISO**: 100–3200 on the dashboard slider (step 50); the volume-key rail goes up to 6400. `0` = AUTO.
- **Manual shutter**: 1–250 ms on the dashboard slider; the volume-key rail steps 0, 1, 2, 4, 8, 15, 30, 60, 120, 250, 500 ms. `0` = AUTO. Values display as e.g. `60ms`.
- **White balance**: Kelvin slider 2500–10000 K on the phone; `0` = auto. Uses a Planckian approximation (`kelvinToRggbGains()`).
- **Manual focus**: distance in diopters with an auto-focus toggle
- **Exposure compensation**: -6 to +6 EV (only applied when ISO and shutter are both AUTO)
- **Dual-layer zoom**: separate physical lens zoom and digital crop. The dashboard digital slider runs 1–10x; the phone volume rail caps combined zoom at 10x.
- **Face tracking**: ML Kit face detection, throttled to every 5th analysis frame; click-to-follow in the dashboard
- **Focus peaking**: edge-detection overlay on the phone preview
- **Volume key control**: assign volume keys to focus/zoom/ISO/shutter rails
- **Background streaming**: camera analysis runs on the ViewModel's process lifetime, so MJPEG and tally continue with the app in the background or the screen off (see the caveats in `problems.md`)

### Streaming & OBS
- **Authenticated MJPEG stream** at `/stream.mjpg` on plain HTTP port 8787 and HTTPS port 8788 when TLS is available. The per-install token is included in the OBS Stream URL shown in the phone app.
- **Frame delivery**: each client receives each new frame once; slow clients skip intermediate frames instead of queuing duplicates.
- **OBS connects directly to the phone**; the relay does not proxy video.
- **Tally bridge**: OBS scene events → relay → phone; borders turn red (PROGRAM), yellow (PREVIEW), green (IDLE)
- **Auto source matching**: the relay discovers OBS `browser_source` inputs, reads each URL's hostname, and maps it to the phone whose callback URL has the same hostname. A manual source name remains the fallback.
- **obs-websocket v5** integration via the Node.js relay
- **OBS Lua auto-start script**: `obs-relay.lua` launches the relay when OBS starts

### Remote Control
- **Phone web dashboard** at `http://<phone-ip>:8787/dashboard` (or `https://<phone-ip>:8788/dashboard` when TLS is available): live preview, camera settings, relay host, and OBS source name
- **Relay admin panel** at `http://localhost:3000`: OBS connection/scene status, device cards with tally/latency/battery, and a director push-to-talk button. It has no per-phone camera controls; copy authenticated stream URLs from each phone's Connection Details drawer.
- **SSID → relay-host cache**: remembers a relay host per WiFi network
- **UDP broadcast discovery** on port 9999. Real mDNS/DNS-SD is not implemented; manual relay IP entry is the fallback.

### Audio intercom
- **Format**: PCM16 mono, 44.1 kHz
- **Phone mic → browser**: `/api/audio/out` SSE
- **Browser dashboard mic → phone**: `/api/audio/in`
- **Director mic → all phones**: relay WebSocket (`/api/audio/broadcast`)
- **Not routed into OBS** as an audio source by default

### Networking
- SSID → relay-host cache: remembers the relay IP per WiFi network; used when no relay host is saved and as a registration fallback
- LAN IP detection skips 169.254.x.x (Tailscale) addresses
- Relay diagnostics endpoint at `/api/diagnostics` (port 3000) returns relay address, OBS status, and device list

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
$env:RELAY_HOST = "192.168.1.11"  # laptop Wi-Fi address
npm start
```

Open `http://localhost:3000` to see the admin panel.
For the director microphone over a LAN IP, use `https://<laptop-ip>:3443` and accept the certificate warning (requires the relay TLS certificate).

### OBS
1. Enable **obs-websocket v5** (Tools → WebSocket Server Settings, port 4455)
2. Add a **Browser Source** → use the authenticated OBS Stream URL shown in the phone app's **Connection Details** drawer. Keep its `token` query parameter; the stream connects directly to the phone, without a relay proxy.
3. For tally: in the phone dashboard's **Relay** section, enter `http://<relay-ip>:3000` and save. The phone registers automatically and keeps a relay WebSocket open for tally; `/api/register` and `/api/ping` remain available for manual use.
4. Source names match automatically: the relay reads each OBS Browser Source URL hostname and maps it to the phone whose callback URL has the same hostname. A manual **Source Name** in the phone dashboard is the fallback. The admin panel shows an `AUTO` badge for matched phones, or an amber "set source name in phone dashboard" hint when no source matched.

### Optional: auto-start relay with OBS
In OBS → Tools → Scripts → Add Scripts → select `obs-relay/obs-relay.lua`. Uses `script_path()` — no hardcoded paths.

## Repository layout

```
obs-relay/                          # Node.js relay
  relay.js                          #   main relay process
  lib/pure.js                       #   pure helpers: URL/hostname, device record, freshness
  test/unit.js, test/smoke.js       #   unit + smoke tests run by npm test
  obs-relay.lua                     #   OBS Lua auto-start script
  admin/index.html                  #   status panel, tally cards, director mic
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

## API endpoints (phone)

Plain HTTP on port 8787. When TLS is available, the same endpoints are also served on HTTPS port 8788.
Phone server endpoints require the per-install access token. Copy the full authenticated URL from the phone app's **Connection Details** drawer; do not share it beyond trusted OBS clients.

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/state?token=<token>` | GET | Full bridge state + settings JSON |
| `/api/settings?token=<token>&iso=800` | GET | Update any setting field |
| `/api/tally?token=<token>&tallyState=PROGRAM` | GET | Tally update (the relay normally pushes tally over WebSocket) |
| `/stream.mjpg?token=<token>` | GET | MJPEG video stream |
| `/dashboard?token=<token>` | GET | Web dashboard |
| `/obs-bridge?token=<token>` | GET | OBS overlay page with tally indicator |
| `/api/audio/in?token=<token>&d=<base64>` | GET | Browser/dashboard mic audio into the phone speaker |
| `/api/audio/out?token=<token>` | GET | SSE audio from the phone mic to browser clients |

## Relay environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `OBS_ADDRESS` | `ws://localhost:4455` | OBS websocket address |
| `OBS_PASSWORD` | `` | OBS websocket password |
| `SOURCE_NAME` | `Browser Full` | Fallback OBS source name when a device has none |
| `ADMIN_PORT` | `3000` | Admin UI + relay WebSocket port |
| `HTTPS_PORT` | `3443` | Secure admin UI + relay WebSocket port (when TLS is configured) |
| `TLS_PFX` | `certs/relay.p12` | PKCS12 certificate for the secure relay server |
| `TLS_PASSWORD` | `changeit` | PKCS12 password |
| `RELAY_HOST` | auto-detected LAN IP | Laptop LAN IP advertised to phones |
| `POLL_ON_START` | `true` | Skip initial tally scan if `false` |
| `DEVICE_STALE_MS` | `10000` | Age after which a device is considered stale |
| `DEVICE_GC_AGE_MS` | `300000` | Age after which an unresponsive device is removed |

## Network requirements

- Phone and laptop on the **same WiFi subnet** (UDP broadcast + HTTP reachability)
- Works out of the box on home/guest-friendly single-subnet WiFi
- Church, guest, and enterprise WiFi often blocks broadcast and enables client isolation; enter the relay IP manually in that case
- Switching networks: the SSID cache can reuse the relay host known for the current network
- Mobile hotspot: phone becomes a different subnet → use manual relay IP entry
- Discovery uses UDP broadcast on port 9999 plus the SSID cache. There is no real mDNS/DNS-SD resolver.

## Known limitations

See [android-webcam-bridge/problems.md](android-webcam-bridge/problems.md). Highlights: process death or OEM battery killers can still stop background streaming, there is no adaptive quality, MJPEG is bandwidth-heavy, and the relay/phone endpoints have no authentication.
