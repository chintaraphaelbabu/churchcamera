const { OBSWebSocket } = require('obs-websocket-js');
const http = require('http');
const https = require('https');
const WebSocket = require('ws');

const OBS_ADDRESS = process.env.OBS_ADDRESS || 'ws://localhost:4455';
const OBS_PASSWORD = process.env.OBS_PASSWORD || '';
const SOURCE_NAME = process.env.SOURCE_NAME || 'Browser Full';
const POLL_ON_START = process.env.POLL_ON_START !== 'false';
const DEVICE_STALE_MS = Number(process.env.DEVICE_STALE_MS || 10000);
const DEVICE_GC_AGE_MS = Number(process.env.DEVICE_GC_AGE_MS || 300000); // 5min, remove if no ping

const ADMIN_PORT = process.env.ADMIN_PORT || 3000;

// devices store
const fs = require('fs');
const path = require('path');
const DEVICES_FILE = path.join(__dirname, 'devices.json');
let devices = {};
function loadDevices(){
  try{
    const txt = fs.readFileSync(DEVICES_FILE,'utf8');
    devices = JSON.parse(txt||'{}');
  }catch(_){devices={}}
}
function saveDevices(){
  try{fs.writeFileSync(DEVICES_FILE, JSON.stringify(devices,null,2));}catch(e){console.warn('failed to save devices',e.message)}
}
function upsertDevice(payload) {
  const now = Date.now();
  const incomingId = payload && payload.id ? String(payload.id) : null;
  const incomingUrl = payload && payload.url ? String(payload.url) : null;
  const incomingName = payload && payload.name ? String(payload.name) : null;
  const incomingSourceName = payload && payload.sourceName ? String(payload.sourceName) : null;

  let recordId = incomingId && devices[incomingId] ? incomingId : null;

  if (!recordId && incomingUrl) {
    recordId = Object.keys(devices).find((id) => devices[id] && devices[id].url === incomingUrl) || null;
  }

  if (!recordId) {
    recordId = (new Date().getTime()).toString(36) + '-' + Math.floor(Math.random() * 10000);
  }

  const previous = devices[recordId] || {};

  // ponytail: rtt from phone (previous cycle), half is one-way latency — no clock drift
  let latencyMs = null;
  if (payload && payload.rtt !== undefined) {
    const rtt = Number(payload.rtt);
    if (Number.isFinite(rtt) && rtt > 0) latencyMs = Math.round(rtt / 2);
  }

  devices[recordId] = {
    id: recordId,
    name: incomingName || previous.name || '',
    sourceName: incomingSourceName || previous.sourceName || '',
    url: incomingUrl || previous.url || '',
    lastSeen: now,
    latencyMs,
    batteryLevel: payload && payload.batteryLevel !== undefined ? Number(payload.batteryLevel) : (previous.batteryLevel !== undefined ? previous.batteryLevel : -1),
  };
  saveDevices();
  return devices[recordId];
}
function getDeviceSourceName(device) {
  const sourceName = device && device.sourceName ? String(device.sourceName).trim() : '';
  if (sourceName) return sourceName;
  return SOURCE_NAME;
}

function getDeviceAgeMs(device) {
  const lastSeen = Number(device && device.lastSeen);
  if (!Number.isFinite(lastSeen) || lastSeen <= 0) return null;
  return Date.now() - lastSeen;
}

function serializeDevice(device) {
  return {
    id: device.id,
    name: device.name,
    sourceName: device.sourceName,
    url: device.url,
    lastSeen: device.lastSeen,
    latencyMs: device.latencyMs || null,
    batteryLevel: device.batteryLevel !== undefined ? device.batteryLevel : -1,
  };
}

function isDeviceFresh(device) {
  const ageMs = getDeviceAgeMs(device);
  return ageMs !== null && ageMs < DEVICE_STALE_MS;
}

function summarizeSceneMatch(items, sourceName) {
  return items.some((item) => item.sourceName === sourceName && item.sceneItemEnabled !== false);
}

async function collectDiagnostics() {
  let currentProgramScene = programScene;
  let currentPreviewScene = previewScene;

  if (connected) {
    if (!currentProgramScene) {
      currentProgramScene = await getProgramSceneName();
    }
    if (!currentPreviewScene) {
      currentPreviewScene = await getPreviewSceneName();
    }
  }

  const programItems = currentProgramScene ? await getSceneItems(currentProgramScene) : [];
  const previewItems = currentPreviewScene ? await getSceneItems(currentPreviewScene) : [];
  const deviceList = Object.values(devices)
    .filter((device) => device && device.url)
    .map((device) => {
      const sourceName = getDeviceSourceName(device);
      const inProgram = summarizeSceneMatch(programItems, sourceName);
      const inPreview = summarizeSceneMatch(previewItems, sourceName);
      const ageMs = getDeviceAgeMs(device);
      return {
        id: device.id,
        name: device.name,
        sourceName,
        url: device.url,
        lastSeen: device.lastSeen,
        latencyMs: device.latencyMs || null,
        fresh: isDeviceFresh(device),
        ageMs,
        state: inProgram ? 'PROGRAM' : inPreview ? 'PREVIEW' : 'IDLE',
        inProgram,
        inPreview,
      };
    });

  const sourceCounts = deviceList.reduce((counts, device) => {
    const key = device.sourceName || '';
    counts[key] = (counts[key] || 0) + 1;
    return counts;
  }, {});

  const issues = [];
  if (!connected) {
    issues.push('Relay is not connected to OBS.');
  }
  if (deviceList.length === 0) {
    issues.push('No phones are registered yet.');
  }
  const staleDevices = deviceList.filter((device) => !device.fresh);
  if (staleDevices.length > 0) {
    issues.push(`${staleDevices.length} phone(s) have not pinged recently.`);
  }
  const missingSourceNames = deviceList.filter((device) => !device.sourceName || device.sourceName === SOURCE_NAME);
  if (missingSourceNames.length > 0) {
    issues.push('One or more phones are still using the default source name.');
  }
  const duplicateSourceNames = Object.entries(sourceCounts)
    .filter(([name, count]) => name && count > 1)
    .map(([name, count]) => `${name} (${count})`);
  if (duplicateSourceNames.length > 0) {
    issues.push(`Duplicate source names detected: ${duplicateSourceNames.join(', ')}.`);
  }
  if (connected && !currentProgramScene && !currentPreviewScene) {
    issues.push('OBS is connected but no current program or preview scene was read.');
  }

  const unmatched = deviceList.filter((device) => !device.inProgram && !device.inPreview);
  if (connected && unmatched.length > 0) {
    issues.push(`${unmatched.length} phone source(s) are not present in the current OBS scenes.`);
  }

  return {
    connected,
    obsAddress: normalizeObsUrl(OBS_ADDRESS),
    relayAddress: lanIp,
    deviceStaleMs: DEVICE_STALE_MS,
    programScene: currentProgramScene,
    previewScene: currentPreviewScene,
    totalDevices: deviceList.length,
    freshDevices: deviceList.filter((device) => device.fresh).length,
    devices: deviceList,
    issues,
  };
}

// ponytail: 50ms debounce, no async queue
let adminStateDebounce = null;
function publishAdminState() {
  if (adminStateDebounce) clearTimeout(adminStateDebounce);
  adminStateDebounce = setTimeout(async () => {
    adminStateDebounce = null;
    try {
      const diagnostics = await collectDiagnostics();
      lastAdminState = {...diagnostics, publishedAt: Date.now()};
      const payload = `event: state\nid: ${lastAdminState.publishedAt}\ndata: ${JSON.stringify(lastAdminState)}\n\n`;
      for (const client of adminClients) {
        if (client.destroyed) { adminClients.delete(client); continue; }
        try { client.write(payload); } catch (_) { adminClients.delete(client); }
      }
    } catch (error) {
      console.warn('publishAdminState error', error.message || error);
    }
  }, 50);
}

const obs = new OBSWebSocket();
let programScene = null;
let previewScene = null;
let connected = false;
const adminClients = new Set();
let lastAdminState = null;

function normalizeObsUrl(address) {
  if (/^wss?:\/\//i.test(address)) return address;
  return `ws://${address}`;
}

// ponytail: tally pushed via WebSocket instead of HTTP — persistent phone→relay connection
function pushTallyViaWs(device, state) {
  const ws = phoneSockets.get(device.id);
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'tally', tallyState: state, sourceName: device.sourceName }));
  }
}

async function getSceneItems(sceneName) {
  try {
    const resp = await obs.call('GetSceneItemList', { sceneName });
    return resp.sceneItems || [];
  } catch (err) {
    console.warn('GetSceneItemList failed for', sceneName, err.message);
    return [];
  }
}

async function getProgramSceneName() {
  try {
    const resp = await obs.call('GetCurrentProgramScene');
    return resp.currentProgramSceneName || null;
  } catch (err) {
    console.warn('GetCurrentProgramScene failed', err.message);
    return null;
  }
}

async function getPreviewSceneName() {
  try {
    const resp = await obs.call('GetCurrentPreviewScene');
    return resp.currentPreviewSceneName || null;
  } catch (_) {
    return null;
  }
}

async function evaluateTally() {
  if (!connected) return;
  try {
    if (!programScene) {
      programScene = await getProgramSceneName();
    }
    if (!previewScene) {
      previewScene = await getPreviewSceneName();
    }

    const programItems = programScene ? await getSceneItems(programScene) : [];
    const previewItems = previewScene ? await getSceneItems(previewScene) : [];
    const deviceList = Object.values(devices).filter((device) => device && device.url && isDeviceFresh(device));

    deviceList.forEach((device) => {
      const sourceName = getDeviceSourceName(device);
      const programHas = programItems.some((item) => item.sourceName === sourceName && item.sceneItemEnabled !== false);
      const previewHas = previewItems.some((item) => item.sourceName === sourceName && item.sceneItemEnabled !== false);
      const state = programHas ? 'PROGRAM' : previewHas ? 'PREVIEW' : 'IDLE';
      pushTallyViaWs(device, state);
    });
    void publishAdminState('tally');
  } catch (e) {
    console.warn('evaluateTally error', e.message);
  }
}

async function connectOnce() {
  const url = normalizeObsUrl(OBS_ADDRESS);
  console.log('Connecting to OBS at', url);
  const result = await obs.connect(url, OBS_PASSWORD || undefined);
  console.log('Connected to OBS:', result);
  connected = true;

  if (POLL_ON_START) {
    programScene = await getProgramSceneName();
    previewScene = await getPreviewSceneName();
    await evaluateTally();
  }

  void publishAdminState('connected');
}

function installEventHandlers() {
  obs.on('CurrentProgramSceneChanged', async (event) => {
    programScene = event.sceneName;
    console.log('CurrentProgramSceneChanged', programScene);
    await evaluateTally();
  });

  obs.on('CurrentPreviewSceneChanged', async (event) => {
    previewScene = event.sceneName;
    console.log('CurrentPreviewSceneChanged', previewScene);
    await evaluateTally();
  });

  obs.on('SceneItemEnableStateChanged', async (event) => {
    console.log('SceneItemEnableStateChanged', event);
    await evaluateTally();
  });

  obs.on('InputShowStateChanged', async (event) => {
    console.log('InputShowStateChanged', event);
    await evaluateTally();
  });

  obs.on('ConnectionClosed', (data) => {
    console.log('OBS connection closed', data);
    connected = false;
    void publishAdminState('disconnected');
  });
  obs.on('AuthenticationFailure', (err) => console.warn('OBS auth failure', err));
  obs.on('error', (err) => console.warn('OBS error', err));
}

// ponytail: WebSocket tally push — phone→relay persistent connection, no more failed HTTP tally
const phoneSockets = new Map(); // deviceId → WebSocket

// ponytail: WS keepalive pings every 15s, drop stale sockets after 3 missed pongs
const WS_PING_INTERVAL = 15000;
const WS_PONG_TIMEOUT = 45000;
setInterval(() => {
  const now = Date.now();
  for (const [id, ws] of phoneSockets) {
    if (ws._lastPong && now - ws._lastPong > WS_PONG_TIMEOUT) {
      console.log('Tally WebSocket stale, terminating:', id);
      ws.terminate();
      phoneSockets.delete(id);
      continue;
    }
    if (ws.readyState === WebSocket.OPEN) ws.ping();
  }
}, WS_PING_INTERVAL);

// ponytail: GC stale device entries every 60s — phone may have been shut down without de-registering
setInterval(() => {
  const now = Date.now();
  let removed = 0;
  for (const [id, device] of Object.entries(devices)) {
    if (device.lastSeen && (now - device.lastSeen) > DEVICE_GC_AGE_MS) {
      delete devices[id];
      phoneSockets.delete(id);
      removed++;
    }
  }
  if (removed > 0) { saveDevices(); console.log('GC removed', removed, 'stale devices'); }
}, 60000);

// --- Admin server (Express) + WebSocket server
const express = require('express');
function startAdmin(){
  loadDevices();
  console.log('Loaded devices:', Object.keys(devices).length);
  const app = express();
  app.use(express.json({ limit: '1mb' }));
  app.use('/', express.static(path.join(__dirname,'admin')));
  app.get('/events', (req, res) => {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    });
    res.write('\n');
    adminClients.add(res);

    if (lastAdminState) {
      res.write(`event: state\nid: ${lastAdminState.publishedAt}\ndata: ${JSON.stringify(lastAdminState)}\n\n`);
    } else {
      void publishAdminState('client-connect');
    }

    const heartbeat = setInterval(() => {
      try {
        res.write(': ping\n\n');
      } catch (_) {
        clearInterval(heartbeat);
      }
    }, 15000);

    req.on('close', () => {
      clearInterval(heartbeat);
      adminClients.delete(res);
    });
  });
  app.get('/api/devices', (req,res)=>{
    const list = Object.values(devices).map((device) => serializeDevice(device));
    res.json(list);
  });
  app.post('/api/register', (req,res)=>{
    const {id,name,sourceName,url,batteryLevel,rtt} = req.body || {};
    if(!url) return res.status(400).json({error:'url required'});
    const device = upsertDevice({ id, name, sourceName, url, batteryLevel, rtt });
    console.log('REGISTER', { id: device.id, name: device.name, sourceName: device.sourceName, url: device.url });
    void evaluateTally();
    void publishAdminState('register');
    res.json({ok:true,id:device.id});
  });
  app.post('/api/ping', (req,res)=>{
    const {id,name,sourceName,url,batteryLevel,rtt} = req.body || {};
    if(!url) return res.status(400).json({error:'url required'});
    const device = upsertDevice({ id, name, sourceName, url, batteryLevel, rtt });
    console.log('PING', { id: device.id, name: device.name, sourceName: device.sourceName, url: device.url });
    void evaluateTally();
    void publishAdminState('ping');
    res.json({ok:true,id:device.id,lastSeen:device.lastSeen});
  });
  app.delete('/api/devices/:id', (req,res)=>{
    const id = req.params.id; delete devices[id]; saveDevices(); void evaluateTally(); void publishAdminState('delete'); res.json({ok:true});
  });
  app.get('/api/diagnostics', async (req, res) => {
    try {
      const diagnostics = await collectDiagnostics();
      res.json(diagnostics);
    } catch (error) {
      res.status(500).json({ error: error?.message || 'diagnostics unavailable' });
    }
  });

  // ponytail: proxy MJPG from phone over plain HTTP — OBS Browser Source rejects self-signed HTTPS
  app.get('/api/stream/:id', async (req, res) => {
    const device = devices[req.params.id];
    if (!device || !device.url) return res.status(404).end();
    const streamUrl = device.url.replace('http://', 'https://').replace(/\/+$/, '') + '/stream.mjpg';
    let proxyRes = null;
    try {
      proxyRes = await new Promise((resolve, reject) => {
        https.get(streamUrl, { rejectUnauthorized: false }, resolve).on('error', reject);
      });
      res.writeHead(200, {
        'Content-Type': 'multipart/x-mixed-replace; boundary=frame',
        'Cache-Control': 'no-cache',
      });
      proxyRes.pipe(res);
      // ponytail: destroy phone connection when OBS disconnects — leak turned into stale connections
      res.on('close', () => { proxyRes?.destroy(); proxyRes = null; });
      res.on('error', () => { proxyRes?.destroy(); proxyRes = null; });
      proxyRes.on('error', () => { res.destroy(); proxyRes = null; });
    } catch (e) { console.warn('stream proxy error', e?.message); res.status(502).end(); }
  });

  const server = http.createServer(app);
  const wss = new WebSocket.Server({ server });

  wss.on('connection', (ws) => {
    let deviceId = null;
    ws._lastPong = Date.now();
    ws.on('pong', () => { ws._lastPong = Date.now(); });
    ws.on('message', (data) => {
      try {
        const msg = JSON.parse(data);
        if (msg.type === 'auth' && msg.deviceId) {
          deviceId = msg.deviceId;
          phoneSockets.set(deviceId, ws);
          console.log('Tally WebSocket connected:', deviceId);
        }
      } catch (e) { console.warn('WS message parse error', e?.message); }
    });
    ws.on('close', () => {
      if (deviceId) { phoneSockets.delete(deviceId); console.log('Tally WebSocket disconnected:', deviceId); }
    });
    ws.on('error', (e) => { console.warn('Tally WS error', e?.message); });
  });

  server.listen(ADMIN_PORT, () => console.log('Admin UI + WebSocket on http://localhost:'+ADMIN_PORT));
  void publishAdminState('startup');
}

async function connectLoop() {
  installEventHandlers();
  while (true) {
    try {
      await connectOnce();
      await new Promise((resolve) => obs.once('ConnectionClosed', resolve));
    } catch (e) {
      console.warn('OBS connect failed:', e.message || e);
    }

    connected = false;
    programScene = null;
    previewScene = null;
    void publishAdminState('retry');
    console.log('Retrying connection in 3s...');
    await new Promise((resolve) => setTimeout(resolve, 3000));
  }
}

// start admin UI
startAdmin();

// ponytail: UDP broadcast discovery — no deps, works on all Windows WiFi
const dgram = require('dgram');
const os = require('os');
const udpSocket = dgram.createSocket('udp4');
const BROADCAST_PORT = 9999;
function getLanIp() {
  for (const iface of Object.values(os.networkInterfaces())) {
    if (!iface) continue;
    for (const addr of iface) {
      if (addr.family === 'IPv4' && !addr.internal && !addr.address.startsWith('169.254')) return addr.address;
    }
  }
  return '127.0.0.1';
}
const lanIp = getLanIp();
const discoveryPayload = JSON.stringify({ url: `http://${lanIp}:${ADMIN_PORT}` });
udpSocket.on('error', (e) => console.warn('UDP socket error', e.message));
udpSocket.bind(() => udpSocket.setBroadcast(true));
setInterval(() => {
  const buf = Buffer.from(discoveryPayload);
  udpSocket.send(buf, 0, buf.length, BROADCAST_PORT, '255.255.255.255');
}, 2000);
console.log(`UDP broadcast on port ${BROADCAST_PORT}: ${discoveryPayload}`);

// ponytail: top-level error boundary — log, don't crash
process.on('uncaughtException', (e) => console.error('[FATAL]', e));
process.on('unhandledRejection', (e) => console.error('[FATAL]', e));
connectLoop().catch((e) => console.error(e));
