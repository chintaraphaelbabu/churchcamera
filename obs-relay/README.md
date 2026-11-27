# OBS Tally Relay

This folder contains the Node relay that watches OBS and pushes tally state to one or more Android phones. OBS reads each phone's MJPEG stream directly; the relay does not proxy video.

The relay uses `obs-websocket` v5 and evaluates each registered phone's OBS source name:

- `PROGRAM` when the source is active in Program
- `PREVIEW` when the source is visible in Preview
- `IDLE` when it is in neither

Each device stores its own `sourceName`. The relay auto-matches a phone to an OBS `browser_source` by comparing the source URL hostname with the phone's callback URL hostname. If a device has no usable name and no source matches, the relay falls back to `SOURCE_NAME`.

## Components

- `relay.js` - main relay process, OBS connection, tally evaluation, relay WebSocket, discovery
- `lib/discovery.js` - DNS-SD service advertisement configuration
- `lib/pure.js` - pure helpers for URL/hostname matching, device records, and freshness
- `test/unit.js`, `test/smoke.js` - unit and smoke tests run by `npm test`
- `admin/index.html` - single-page status UI: OBS connection/scene, device cards (tally, latency, battery), director push-to-talk, stream links
- `devices.json` - local persistence for registered phones
- `obs-relay.lua` - OBS Lua script that starts the relay with OBS
- `start-relay.bat` - Windows launcher

## How it works

1. The relay connects to OBS through `obs-websocket`.
2. It subscribes to OBS scene/source events (Program/Preview scene changes, scene item enable/create/remove/reindex, input show state, input create/remove/rename).
3. It discovers `browser_source` inputs, reads each input's URL hostname, and maps it to the device whose callback URL has the same hostname. Phones with a blank, Android-model, or `SOURCE_NAME` source name are auto-matched and persisted with `sourceNameAuto = true`.
4. For each fresh device it checks whether the device's source name is present and enabled in Program and Preview.
5. The phone opens a WebSocket at `/tally` and sends `{"type":"auth","deviceId":"..."}`. The relay upserts the device from that message and pushes tally updates over the socket.
6. The relay also accepts `POST /api/register` and `POST /api/ping`; the phone heartbeats every 10 seconds. A heartbeat that only sends the Android model does not overwrite an auto-matched source name.
7. The relay advertises its HTTP endpoint as a DNS-SD `_churchcam._tcp.local` service using the configured admin port. Android discovers and resolves this service through the platform NSD API; UDP broadcast on port `9999` remains as a fallback.

A device is considered stale after 10 seconds without a ping (`DEVICE_STALE_MS`). Devices are removed after 5 minutes without a ping (`DEVICE_GC_AGE_MS`).

## Admin UI

Open the relay admin page in a browser:

```bash
http://localhost:3000
```

If a TLS certificate exists at `TLS_PFX` (default `certs/relay.p12`), the same admin UI and relay WebSocket are also served on HTTPS port `3443`.

The page shows:

- OBS connection state and current Program/Preview scene
- One card per registered phone: tally state, latency, and battery. The authenticated stream URL stays on the phone and is copied from its Connection Details drawer.
- An `AUTO` badge when the relay matched the phone to an OBS Browser Source by URL hostname, or an amber "set source name in phone dashboard" hint when nothing matched
- A director push-to-talk button that sends audio to all connected phones

It does not expose per-phone camera controls (ISO, white balance, shutter, focus, AF). Use the phone dashboard for those. The `/api/diagnostics` endpoint returns the same diagnostics data as JSON.

On Windows, you can also start the relay by double-clicking `start-relay.bat` in this folder.

## Phone registration

Registration is automatic over the relay WebSocket: when a phone opens `/tally` and sends an `auth` message, the relay upserts the device. The phone also registers over HTTP during setup, and the HTTP endpoints remain available:

- `POST /api/register` with `{ id?, name, sourceName, url, batteryLevel, rtt }`
- `POST /api/ping` with the same fields
- `GET /api/devices`
- `DELETE /api/devices/:id`
- `GET /api/diagnostics`

## Multiple phones

You can register as many phones as you want. Each phone should:

- point to the same relay host
- keep heartbeating so its IP can change without breaking the setup
- have its own OBS Browser Source pointing at that phone's `/stream.mjpg`

Source names are matched automatically by URL hostname. A new phone registers with the Android model as a placeholder; once the relay finds an OBS Browser Source with the same hostname, it stores the real input name and shows an `AUTO` badge. Set a **Source Name** in the phone dashboard manually only when the admin page shows the amber hint.

## Development

```bash
npm install
npm test     # unit tests (node --test) + smoke test
npm run lint
```

## Environment variables

- `OBS_ADDRESS` - OBS websocket address, default `ws://localhost:4455`
- `OBS_PASSWORD` - password if you configured one in OBS
- `SOURCE_NAME` - fallback Browser Source name when a device has none, default `Browser Full`
- `ADMIN_PORT` - admin UI + WebSocket port, default `3000`
- `HTTPS_PORT` - secure admin/WebSocket port, default `3443`
- `TLS_PFX` - PKCS12 certificate path, default `certs/relay.p12`
- `TLS_PASSWORD` - PKCS12 password, default `changeit`
- `RELAY_HOST` - laptop LAN IP advertised to phones, useful when multiple adapters exist
- `POLL_ON_START` - set to `false` to skip the first tally scan
- `DEVICE_STALE_MS` - stale threshold, default `10000`
- `DEVICE_GC_AGE_MS` - device removal age, default `300000`

## Security

The relay admin UI, relay WebSocket, and director microphone listen on all interfaces with no authentication. The phones' HTTP APIs are also unauthenticated. Anyone on the LAN can view devices and tally, control or stream from phones, and speak through the director mic. Use this on trusted networks only.

## Files you may care about

- [relay.js](relay.js)
- [admin/index.html](admin/index.html)
- [obs-relay.lua](obs-relay.lua)
- [start-relay.bat](start-relay.bat)
