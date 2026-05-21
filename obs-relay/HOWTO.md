# OBS Tally Relay Setup

This guide is only for the tally relay part.

## What you need

- OBS with `obs-websocket` v5 enabled
- Node.js installed on the streaming PC
- Your Android app running on the phone
- Both devices on the same network

## 1. Set up the relay

```bash
cd obs-relay
npm install
```

Start it:

```bash
npm start
```

Default ports:

- relay admin UI: `3000`
- phone HTTP server: `8787`
- OBS websocket: `4455`

## 2. Configure OBS

In OBS:

- open WebSocket Server Settings
- make sure the server is enabled
- confirm the port is `4455`
- note the Browser Source name you want to track, for example `Browser Full`

If your Browser Source has a different name, set `SOURCE_NAME` in `relay.js` or as an environment variable.

## 3. Register the phone

Open the relay admin page:

```bash
http://localhost:3000
```

Then either:

- enter the phone callback URL manually, or
- use the Android app dashboard relay field

The callback URL should look like this:

```text
http://<phone-ip>:8787
```

The phone will register itself and keep heartbeating every 30 seconds.

## 4. Add more phones

For a second or third phone, repeat the same steps:

1. Open the Android app.
2. Set the same relay host, for example `http://192.168.1.100:3000`.
3. Tap Register.
4. Leave heartbeat enabled.

Each phone gets its own record in `devices.json`, and the relay sends tally updates to all of them.

## 5. Check that it works

You should see the phone border change:

- green for idle
- yellow for preview
- red for program

You can also open the relay admin page and confirm the device is marked fresh.

## Troubleshooting

- If the relay cannot connect to OBS, check that OBS websocket v5 is enabled and the port is correct.
- If the phone does not register, confirm the relay host is reachable from the phone.
- If a phone changes IP, just reopen the app or save the relay host again; heartbeat will refresh the record.