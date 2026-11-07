# OBS Tally Relay

This folder contains the Node relay that watches OBS and pushes tally state to one or more Android phones. OBS reads each phone's MJPEG stream directly; the relay does not proxy video.

The relay uses `obs-websocket` v5 and evaluates each registered phone's OBS source name:

- `PROGRAM` when the source is active in Program
- `PREVIEW` when the source is visible in Preview
- `IDLE` when it is in neither

Each device uses its own `sourceName`. If a device has none, the relay falls back to `SOURCE_NAME`.

## Components

- `relay.js` - main relay process, OBS connection, tally evaluation, relay WebSocket, discovery
- `admin/index.html` - single-page status UI: OBS connection/scene, device cards (tally, latency, battery), director push-to-talk, stream links
- `devices.json` - local persistence for registered phones
- `obs-relay.lua` - OBS Lua script that starts the relay with OBS
- `start-relay.bat` - Windows launcher

## How it works

1. The relay connects to OBS through `obs-websocket`.
2. It subscribes to OBS scene/source events (Program/Preview scene changes, scene item enable/create/remove/reindex, input show state).
3. For each fresh device it checks whether the device's source name is present and enabled in Program and Preview.
4. The phone opens a WebSocket at `/tally` and sends `{"type":"auth","deviceId":"..."}`. The relay upserts the device from that message and pushes tally updates over the socket.
5. The relay also accepts `POST /api/register` and `POST /api/ping`; the phone heartbeats every 10 seconds.

A device is considered stale after 10 seconds without a ping (`DEVICE_STALE_MS`). Devices are removed after 5 minutes without a ping (`DEVICE_GC_AGE_MS`).

## Admin UI

Open the relay admin page in a browser:

```bash
http://localhost:3000
```

If a TLS certificate exists at `TLS_PFX` (default `certs/relay.p12`), the same admin UI and relay WebSocket are also served on HTTPS port `3443`.

The page shows:

- OBS connection state and current Program/Preview scene
- One card per registered phone: tally state, latency, battery, and a link to that phone's `/stream.mjpg`
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
- set its **Source Name** in the phone dashboard to the exact OBS Browser Source name

A new phone's source name defaults to the Android device model, which usually does not match OBS. Tally stays `IDLE` until the exact source name is set.

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
