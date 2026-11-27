# Tally Setup HOWTO

This guide covers only the tally path: OBS to relay to Android phones.

## What this setup does

The relay reads tally state from OBS and sends it to your phones.
Each phone runs the Android bridge app, hosts a local HTTP server, and exposes a callback URL that the relay can reach.
The relay maps each phone to its own OBS Browser Source by matching the source URL's hostname to the phone's callback URL hostname. A manual source name remains the fallback.

The tally states are:

- `PROGRAM` - the source is live/on air
- `PREVIEW` - the source is visible in OBS preview
- `IDLE` - the source is not active in either place

The phone UI shows this as:

- red border for `PROGRAM`
- yellow border for `PREVIEW`
- green border for `IDLE`

## What you need

- OBS installed on the streaming PC
- `obs-websocket` v5 enabled in OBS
- Node.js installed on the streaming PC
- The Android app installed on each phone you want to use
- All devices on the same network (or manual relay IP entry if broadcast is blocked)

## 1. Start OBS and enable websocket

In OBS:

- open the websocket server settings
- make sure the server is enabled
- confirm the port is `4455`
- copy each phone's authenticated OBS Stream URL from its **Connection Details** drawer

Each OBS Browser Source should use that phone's full authenticated OBS Stream URL, including its `token` query parameter. The relay matches the phone and source by URL hostname, so you do not need to pre-configure the source names in the phone dashboard.

## 2. Start the relay

In the relay folder (relative to the repo root):

```powershell
cd obs-relay
npm install
npm start
```

The relay starts two things:

- OBS connection logic on the Node side
- a small admin page on port `3000`

Open the admin page in a browser:

```text
http://localhost:3000
```

## 3. Register the first phone

Registration is automatic by default:

1. Open the phone dashboard at `http://<phone-ip>:8787/dashboard` (or `https://<phone-ip>:8788/dashboard` when TLS is available).
2. In the **Relay** section, enter the relay host, for example:

```text
http://192.168.1.100:3000
```

3. Tap **Save**.

The phone will:

- save the relay host
- register its callback URL with the relay over HTTP (`/api/register`)
- heartbeat to the relay every 10 seconds (`/api/ping`)
- open the relay WebSocket at `/tally` and send an `auth` message, which the relay also uses to register/refresh the device automatically

The callback URL is the phone's local bridge server, usually like this:

```text
http://<phone-ip>:8787
```

The app stores the relay device id so it can keep the same registration even if the phone IP changes.

Manual fallback: the relay still accepts direct `POST /api/register` and `POST /api/ping` calls. You do not need a separate register button for normal use.

### Source names match automatically

A new phone registers with the Android model as a placeholder source name. The relay discovers OBS `browser_source` inputs, reads each source URL's hostname, and maps it to the phone whose callback URL has the same hostname. When it finds a match, the relay stores the OBS input name and marks it as auto-matched.

You do not need to set a source name first. The admin page shows an `AUTO` badge for matched phones. If no OBS Browser Source matches, it shows an amber "set source name in phone dashboard" hint.

Manual fallback: in the phone dashboard's **Relay** section, set **Source Name** to the exact OBS Browser Source name, for example `phone1`, then tap **Save**.

The relay matches a manual name exactly (case-sensitive) against sources in the current OBS Program and Preview scenes. A heartbeat that only sends the Android model does not overwrite an auto-matched name.

## 4. Verify it works

In the relay admin page you should see the phone listed as fresh.
Then switch scenes in OBS and confirm the tally border changes on the matching phone.

If the source is live in Program, that phone should turn red.
If it is only in Preview, it should turn yellow.
If it is in neither, it should turn green.

## 5. Add a second or third phone

Repeat the same registration steps on each additional phone:

1. Open the phone dashboard.
2. Set the same relay host and tap **Save**.
3. Make sure that phone has its own OBS Browser Source pointing at the authenticated OBS Stream URL from its **Connection Details** drawer. Source names are auto-matched by hostname; set a **Source Name** manually only if the admin page shows the amber hint.
4. Leave the app running so the heartbeat keeps it fresh.

The relay sends tally updates to the matching registered phone for each source name.
That means phone1 can follow source `phone1` and phone2 can follow source `phone2` at the same time.

Important:

- give each phone its own OBS Browser Source pointing at that phone's authenticated OBS Stream URL
- register each phone separately on the relay (automatic when it saves the relay host)
- rely on auto-matching, or set the matching source name manually in that phone's dashboard if no source matched

If you want the same tally on every phone, give every phone the same source name.

## 6. What to do when a phone IP changes

Usually nothing special.

Because the phone heartbeats to the relay every 10 seconds, it keeps updating its current callback URL.
If the Wi-Fi network changes or the phone gets a new IP, the next heartbeat refreshes the record.
If needed, re-save the relay host in the phone dashboard, or call `POST /api/register` / `POST /api/ping` manually.

## 7. Clean up old devices

The relay removes devices that have not pinged for 5 minutes (`DEVICE_GC_AGE_MS`).

The admin page does not have a delete control. To remove a device immediately, call:

```text
DELETE http://<relay-ip>:3000/api/devices/<device-id>
```

## Troubleshooting

- If the relay does not connect to OBS, check that OBS websocket v5 is enabled and the port is `4455`.
- If the phone does not register, make sure the relay host is reachable from the phone. Guest WiFi may block broadcast and client isolation, so enter the relay IP manually.
- If tally state never changes, check that an OBS Browser Source URL points at that phone's IP/host and that the admin page shows either an `AUTO` badge or the correct source name. If it shows the amber "set source name in phone dashboard" hint, set the phone's **Source Name** to the exact OBS Browser Source name.
- If you see stale devices in the admin page, wait for the 5-minute cleanup or delete them through the API.

## Files involved

- [obs-relay/relay.js](obs-relay/relay.js)
- [obs-relay/admin/index.html](obs-relay/admin/index.html)
- [obs-relay/devices.json](obs-relay/devices.json)
- [android-webcam-bridge/app/src/main/java/com/raphael/androidwebcambridge/bridge/BridgeViewModel.kt](android-webcam-bridge/app/src/main/java/com/raphael/androidwebcambridge/bridge/BridgeViewModel.kt)
- [android-webcam-bridge/app/src/main/java/com/raphael/androidwebcambridge/bridge/LocalBridgeServer.kt](android-webcam-bridge/app/src/main/java/com/raphael/androidwebcambridge/bridge/LocalBridgeServer.kt)
