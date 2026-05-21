# OBS Tally Relay

This folder contains the small Node relay that watches OBS and pushes tally state to one or more Android phones.

The relay is the source of truth for tally state. It uses `obs-websocket` v5, tracks a Browser Source named `SOURCE_NAME`, and posts:

- `PROGRAM` when the source is active in Program
- `PREVIEW` when the source is visible in Preview
- `IDLE` when it is in neither

It also includes a minimal admin UI for registering phones and watching which devices are still fresh.

## Components

- `relay.js` - main relay process and OBS connection logic
- `admin/index.html` - tiny browser UI for registered devices
- `devices.json` - local persistence for registered phones
- `REGISTER_SNIPPET.md` - optional Android registration snippet

## How it works

1. OBS connects to the relay through `obs-websocket`.
2. The relay inspects the current Program and Preview scenes.
3. If the configured Browser Source is present in Program, the relay sends `PROGRAM`.
4. If it is only in Preview, the relay sends `PREVIEW`.
5. Each phone registers its own callback URL with `/api/register` and keeps it fresh with `/api/ping`.
6. The relay broadcasts tally updates to every registered phone.

## Admin UI

Open the relay admin page in a browser:

```bash
http://localhost:3000
```

On Windows, you can also start the relay by double-clicking `start-relay.bat` in this folder.

The admin page includes a built-in Diagnostic Assistant below the device list. It checks OBS connection, phone registration, stale heartbeats, and source-name mismatches.

From there you can:

- register a phone URL manually
- view registered phones
- delete old entries
- see which devices are stale or fresh

## Multiple phones

You can register as many phones as you want. Each phone should:

- point to the same relay host
- register once
- keep heartbeat enabled so its IP can change without breaking the setup

The relay sends tally updates to all registered devices, so a second or third phone works the same way as the first.

## Environment variables

- `OBS_ADDRESS` - OBS websocket address, for example `ws://localhost:4455`
- `OBS_PASSWORD` - password if you configured one in OBS
- `SOURCE_NAME` - Browser Source name in OBS
- `ADMIN_PORT` - admin UI port, default `3000`
- `POLL_ON_START` - set to `false` to skip the first tally scan

## Files you may care about

- [relay.js](relay.js)
- [admin/index.html](admin/index.html)
- [REGISTER_SNIPPET.md](REGISTER_SNIPPET.md)
