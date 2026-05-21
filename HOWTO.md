# Tally Setup HOWTO

This guide covers only the tally path: OBS to relay to Android phones.

## What this setup does

The relay reads tally state from OBS and sends it to your phones.
Each phone runs the Android bridge app, hosts a local HTTP server, and exposes a callback URL that the relay can POST to.
Each phone is mapped to its own OBS source name.

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
- All devices on the same network

## 1. Start OBS and enable websocket

In OBS:

- open the websocket server settings
- make sure the server is enabled
- confirm the port is `4444`
- note the Browser Source names you want to track, such as `phone1` and `phone2`

Each phone/source pair is configured in the relay admin page, not in OBS itself.

## 2. Start the relay

In the relay folder:

```powershell
cd C:\Users\Raphael\Documents\raphael\projects\app\obs-relay
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

On the phone, open the Android app and find the relay host field in the dashboard.
Enter the relay host, for example:

```text
http://192.168.1.100:3000
```

Then tap Register.

The phone will:

- save the relay host
- register its callback URL with the relay
- start heartbeating every 30 seconds

The callback URL is the phone’s local bridge server, usually like this:

```text
http://<phone-ip>:8787
```

The app stores the relay device id so it can keep the same registration even if the phone IP changes.

After the phone is registered, open the relay admin page and set the phone’s OBS source name there, for example `phone1`.

## 4. Verify it works

In the relay admin page you should see the phone listed as fresh.
Then switch scenes in OBS and confirm the tally border changes on the matching phone.

If the source is live in Program, that phone should turn red.
If it is only in Preview, it should turn yellow.
If it is in neither, it should turn green.

## 5. Add a second or third phone

Repeat the same registration steps on each additional phone:

1. Open the Android app.
2. Set the same relay host.
3. Tap Register.
4. Leave the app running so heartbeat keeps it fresh.

The relay broadcasts tally updates to the matching registered phone for each source name.
That means phone1 can follow source `phone1` and phone2 can follow source `phone2` at the same time.

Important:

- give each phone its own OBS Browser Source name if you want individual tally
- register each phone separately on the relay
- enter the matching source name for each phone in the relay admin page

If you want the same tally on every phone, give every phone the same source name.

## 6. What to do when a phone IP changes

Usually nothing special.

Because the phone heartbeats to the relay, it keeps updating its current callback URL.
If the Wi-Fi network changes or the phone gets a new IP, the next heartbeat refreshes the record.
If needed, just open the app and tap Register again.

## 7. Clean up old devices

Use the relay admin page to delete phones you are no longer using.
This keeps `devices.json` tidy and avoids stale entries.

## Troubleshooting

- If the relay does not connect to OBS, check the websocket port and that OBS v5 websocket is enabled.
- If the phone does not register, make sure the relay host is reachable from the phone.
- If tally state never changes, confirm the OBS Browser Source name matches the source name entered for that phone in the relay admin page.
- If you see stale devices in the admin page, delete them and register the phone again.

## Files involved

- [obs-relay/relay.js](obs-relay/relay.js)
- [obs-relay/admin/index.html](obs-relay/admin/index.html)
- [obs-relay/devices.json](obs-relay/devices.json)
- [android-webcam-bridge/app/src/main/java/com/raphael/androidwebcambridge/bridge/BridgeViewModel.kt](android-webcam-bridge/app/src/main/java/com/raphael/androidwebcambridge/bridge/BridgeViewModel.kt)
- [android-webcam-bridge/app/src/main/java/com/raphael/androidwebcambridge/bridge/LocalBridgeServer.kt](android-webcam-bridge/app/src/main/java/com/raphael/androidwebcambridge/bridge/LocalBridgeServer.kt)
