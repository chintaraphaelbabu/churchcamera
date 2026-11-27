const { OBSWebSocket } = require('obs-websocket-js');
const http = require('http');
const https = require('https');
const WebSocket = require('ws');
const fs = require('fs');
const path = require('path');
const { Bonjour } = require('bonjour-service');
const {
  normalizeObsUrl,
  summarizeSceneMatch,
  hostFromUrl,
  isDefaultSourceName,
  getDeviceSourceName,
  getDeviceAgeMs,
  isDeviceFresh,
  buildDeviceRecord,
} = require('./lib/pure');
const { publishRelayService } = require('./lib/discovery');

const OBS_ADDRESS = process.env.OBS_ADDRESS || 'ws://localhost:4455';
const OBS_PASSWORD = process.env.OBS_PASSWORD || '';
const SOURCE_NAME = process.env.SOURCE_NAME || 'Browser Full';
const POLL_ON_START = process.env.POLL_ON_START !== 'false';
const DEVICE_STALE_MS = Number(process.env.DEVICE_STALE_MS || 10000);
const DEVICE_GC_AGE_MS = Number(process.env.DEVICE_GC_AGE_MS || 300000); // 5min, remove if no ping

const ADMIN_PORT = process.env.ADMIN_PORT || 3000;
const RELAY_HOST = process.env.RELAY_HOST || '';
const HTTPS_PORT = process.env.HTTPS_PORT || 3443;
const TLS_PFX = process.env.TLS_PFX || path.join(__dirname, 'certs', 'relay.p12');
const TLS_PASSWORD = process.env.TLS_PASSWORD || 'changeit';

// devices store
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

  let recordId = incomingId && devices[incomingId] ? incomingId : null;

  if (!recordId && incomingUrl) {
    recordId = Object.keys(devices).find((id) => devices[id] && devices[id].url === incomingUrl) || null;
  }

  if (!recordId) {
    recordId = (new Date().getTime()).toString(36) + '-' + Math.floor(Math.random() * 10000);
  }

  const previous = devices[recordId]
    ? { ...devices[recordId], id: recordId }
    : { id: recordId };

  devices[recordId] = buildDeviceRecord(payload, previous, now, SOURCE_NAME);
  saveDevices();
  return devices[recordId];
}
function serializeDevice(device) {
  return {
    id: device.id,
    name: device.name,
    sourceName: device.sourceName,
    sourceNameAuto: !!device.sourceNameAuto,
    url: device.url,
    lastSeen: device.lastSeen,
    latencyMs: device.latencyMs || null,
    batteryLevel: device.batteryLevel !== undefined ? device.batteryLevel : -1,
  };
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

  const programItems = currentProgramScene ? await getSceneItemsCached(currentProgramScene) : [];
  const previewItems = currentPreviewScene ? await getSceneItemsCached(currentPreviewScene) : [];
  const deviceList = Object.values(devices)
    .filter((device) => device && device.url)
    .map((device) => {
      const sourceName = getDeviceSourceName(device, SOURCE_NAME);
      const inProgram = summarizeSceneMatch(programItems, sourceName);
      const inPreview = summarizeSceneMatch(previewItems, sourceName);
      const ageMs = getDeviceAgeMs(device, Date.now());
      return {
        id: device.id,
        name: device.name,
        sourceName,
        sourceNameAuto: !!device.sourceNameAuto,
        autoMatched: !!device.sourceNameAuto,
        sourceDefault: isDefaultSourceName(device, undefined, SOURCE_NAME),
        url: device.url,
        lastSeen: device.lastSeen,
        latencyMs: device.latencyMs || null,
        fresh: isDeviceFresh(device, Date.now(), DEVICE_STALE_MS),
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
  const missingSourceNames = deviceList.filter((device) => device.sourceDefault);
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

// ponytail: tally pushed via WebSocket instead of HTTP — persistent phone→relay connection
function pushTallyViaWs(device, state) {
  const ws = phoneSockets.get(device.id);
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'tally', tallyState: state, sourceName: getDeviceSourceName(device, SOURCE_NAME) }));
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

// ponytail: cache scene item lists — OBS event bursts reuse one call; 750ms TTL is only a safety net
const SCENE_ITEM_CACHE_TTL_MS = 750;
const sceneItemCache = new Map(); // sceneName → { items, at }

function invalidateSceneCache(sceneName) {
  if (sceneName) sceneItemCache.delete(sceneName);
}

function clearSceneItemCache() {
  sceneItemCache.clear();
}

async function getSceneItemsCached(sceneName) {
  if (!sceneName) return [];
  const cached = sceneItemCache.get(sceneName);
  if (cached && Date.now() - cached.at < SCENE_ITEM_CACHE_TTL_MS) return cached.items;
  const items = await getSceneItems(sceneName);
  sceneItemCache.set(sceneName, { items, at: Date.now() });
  return items;
}

// ponytail: map browser-source hostname -> inputName. 5s TTL is only a safety
// net; OBS input events invalidate it immediately.
const BROWSER_SOURCE_CACHE_TTL_MS = 5000;
let browserSourceHostsCache = null; // { hosts: Map<host, inputName>, at }
let browserSourceHostsInFlight = null; // { generation, promise }
let browserSourceCacheGeneration = 0;

function clearBrowserSourceCache() {
  browserSourceCacheGeneration++;
  browserSourceHostsCache = null;
  browserSourceHostsInFlight = null;
}

async function getBrowserSourceHosts() {
  const cached = browserSourceHostsCache;
  if (cached && Date.now() - cached.at < BROWSER_SOURCE_CACHE_TTL_MS) return cached.hosts;
  if (browserSourceHostsInFlight) return browserSourceHostsInFlight.promise;

  const generation = browserSourceCacheGeneration;
  const promise = (async () => {
    const hosts = new Map();
    let ok = true;
    try {
      const list = await obs.call('GetInputList', { inputKind: 'browser_source' });
      for (const input of (list && list.inputs) || []) {
        const inputName = input && input.inputName;
        if (!inputName) continue;
        try {
          const settings = await obs.call('GetInputSettings', { inputName });
          const host = hostFromUrl(settings && settings.inputSettings && settings.inputSettings.url);
          if (host && !hosts.has(host)) hosts.set(host, inputName); // first input wins per host
        } catch (err) {
          console.warn('GetInputSettings failed for', inputName, err.message || err);
        }
      }
    } catch (err) {
      ok = false;
      console.warn('GetInputList failed', err.message || err);
    }
    // Only cache successful reads, and only if the cache was not cleared while
    // we were talking to OBS (avoids resurrecting pre-invalidation data).
    if (ok && generation === browserSourceCacheGeneration) {
      browserSourceHostsCache = { hosts, at: Date.now() };
    }
    return hosts;
  })();

  const entry = { generation, promise };
  browserSourceHostsInFlight = entry;
  try {
    return await promise;
  } finally {
    if (browserSourceHostsInFlight === entry) browserSourceHostsInFlight = null;
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

// ponytail: serialize tally eval — bursts collapse into one follow-up pass, no debounce latency
let evaluating = false;
let evaluateAgain = false;

async function evaluateTally() {
  if (evaluating) {
    evaluateAgain = true;
    return;
  }
  if (!connected) return;
  evaluating = true;
  try {
    if (!programScene) {
      programScene = await getProgramSceneName();
    }
    if (!previewScene) {
      previewScene = await getPreviewSceneName();
    }

    const programItems = programScene ? await getSceneItemsCached(programScene) : [];
    const previewItems = previewScene ? await getSceneItemsCached(previewScene) : [];
    const deviceList = Object.values(devices).filter((device) => device && isDeviceFresh(device, Date.now(), DEVICE_STALE_MS));

    deviceList.forEach((device) => {
      const sourceName = getDeviceSourceName(device, SOURCE_NAME);
      const programHas = summarizeSceneMatch(programItems, sourceName);
      const previewHas = summarizeSceneMatch(previewItems, sourceName);
      const state = programHas ? 'PROGRAM' : previewHas ? 'PREVIEW' : 'IDLE';
      pushTallyViaWs(device, state);
    });
    void publishAdminState('tally');
  } catch (e) {
    console.warn('evaluateTally error', e.message);
  } finally {
    evaluating = false;
    if (evaluateAgain) {
      evaluateAgain = false;
      void evaluateTally();
    }
  }
}

// ponytail: serialize auto-match, 500ms debounce — register/ping bursts should
// not spam OBS with GetInputList/GetInputSettings. Tally itself stays undebounced.
const AUTO_MATCH_DEBOUNCE_MS = 500;
let autoMatchTimer = null;
let autoMatchInFlight = false;
let autoMatchQueued = false;

function scheduleAutoMatch() {
  if (autoMatchTimer) clearTimeout(autoMatchTimer);
  autoMatchTimer = setTimeout(() => {
    autoMatchTimer = null;
    void autoMatchDeviceSources();
  }, AUTO_MATCH_DEBOUNCE_MS);
}

async function autoMatchDeviceSources() {
  if (!connected) return;
  if (autoMatchInFlight) {
    autoMatchQueued = true;
    return;
  }
  autoMatchInFlight = true;
  let changed = false;
  try {
    const hosts = await getBrowserSourceHosts();
    for (const device of Object.values(devices)) {
      if (!device || !device.url) continue;
      const sourceIsDefault = isDefaultSourceName(device, undefined, SOURCE_NAME);
      // Explicit source names (not default, not auto-matched) are never touched.
      if (!sourceIsDefault && !device.sourceNameAuto) continue;

      const host = hostFromUrl(device.url);
      const match = host ? hosts.get(host) : undefined;
      if (match) {
        if (device.sourceName !== match || !device.sourceNameAuto) {
          device.sourceName = match;
          device.sourceNameAuto = true;
          changed = true;
        }
      } else if (sourceIsDefault) {
        // No browser source for this phone. Keep the default name so the admin
        // UI can show a hint, but an old auto flag is no longer valid.
        if (device.sourceNameAuto) {
          device.sourceNameAuto = false;
          changed = true;
        }
      }
      // Auto-matched name with no match right now: keep it. A transient OBS
      // read failure must not wipe a working match.
    }
  } catch (error) {
    console.warn('autoMatchDeviceSources error', error.message || error);
  } finally {
    autoMatchInFlight = false;
    if (changed) {
      saveDevices();
      console.log('AUTO-MATCH updated device source names');
      void publishAdminState('auto-match');
      void evaluateTally();
    }
    if (autoMatchQueued) {
      autoMatchQueued = false;
      scheduleAutoMatch();
    }
  }
}

async function connectOnce() {
  const url = normalizeObsUrl(OBS_ADDRESS);
  console.log('Connecting to OBS at', url);
  const result = await obs.connect(url, OBS_PASSWORD || undefined);
  console.log('Connected to OBS:', result);
  connected = true;
  clearSceneItemCache(); // ponytail: never reuse items from a previous OBS session
  clearBrowserSourceCache(); // fresh input list after every (re)connect
  await autoMatchDeviceSources();

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
    invalidateSceneCache(event.sceneName);
    await evaluateTally();
  });

  obs.on('SceneItemCreated', async (event) => {
    invalidateSceneCache(event.sceneName);
    await evaluateTally();
  });

  obs.on('SceneItemRemoved', async (event) => {
    invalidateSceneCache(event.sceneName);
    await evaluateTally();
  });

  obs.on('SceneItemListReindexed', async () => {
    clearSceneItemCache();
    await evaluateTally();
  });

  obs.on('InputShowStateChanged', async (event) => {
    console.log('InputShowStateChanged', event);
    await evaluateTally();
  });

  // Browser source list/URLs changed — drop the host cache. A new or renamed
  // input may also be the one a waiting phone should auto-match to.
  obs.on('InputCreated', () => {
    clearBrowserSourceCache();
    scheduleAutoMatch();
  });

  obs.on('InputRemoved', () => {
    clearBrowserSourceCache();
  });

  obs.on('InputNameChanged', () => {
    clearBrowserSourceCache();
    scheduleAutoMatch();
  });

  obs.on('ConnectionClosed', (data) => {
    console.log('OBS connection closed', data);
    connected = false;
    clearSceneItemCache();
    clearBrowserSourceCache();
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
    scheduleAutoMatch();
    void evaluateTally();
    void publishAdminState('register');
    res.json({ok:true,id:device.id});
  });
  app.post('/api/ping', (req,res)=>{
    const {id,name,sourceName,url,batteryLevel,rtt} = req.body || {};
    if(!url) return res.status(400).json({error:'url required'});
    const device = upsertDevice({ id, name, sourceName, url, batteryLevel, rtt });
    console.log('PING', { id: device.id, name: device.name, sourceName: device.sourceName, url: device.url });
    scheduleAutoMatch();
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
  app.post('/api/audio/broadcast', (req, res) => {
    const data = typeof req.body?.data === 'string' ? req.body.data : '';
    if (!data) return res.status(400).json({ error: 'audio data required' });
    let sent = 0;
    for (const ws of phoneSockets.values()) {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'audio', data }));
        sent++;
      }
    }
    res.json({ ok: true, sent });
  });

  const attachWebSocket = (server) => {
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
          console.log('Tally WebSocket auth:', deviceId);
          // ponytail: auto-register on auth — eliminates separate POST /api/register
          const payload = {
            id: msg.deviceId,
            name: msg.name || msg.deviceName || '',
            sourceName: msg.sourceName || '',
            url: msg.url || msg.deviceUrl || '',
            batteryLevel: msg.batteryLevel !== undefined ? msg.batteryLevel : -1,
            rtt: msg.rtt !== undefined ? msg.rtt : undefined,
          };
          const device = upsertDevice(payload);
          console.log('AUTO-REGISTER via auth:', { id: device.id, name: device.name, url: device.url || '(none)' });
          scheduleAutoMatch();
          void evaluateTally();
          void publishAdminState('auth-register');
        }
      } catch (e) { console.warn('WS message parse error', e?.message); }
    });
    ws.on('close', () => {
      if (deviceId) { phoneSockets.delete(deviceId); console.log('Tally WebSocket disconnected:', deviceId); }
    });
    ws.on('error', (e) => { console.warn('Tally WS error', e?.message); });
    });
  };

  const server = http.createServer(app);
  attachWebSocket(server);

  server.listen(ADMIN_PORT, () => console.log('Admin UI + WebSocket on http://localhost:'+ADMIN_PORT));
  if (fs.existsSync(TLS_PFX)) {
    const secureServer = https.createServer({ pfx: fs.readFileSync(TLS_PFX), passphrase: TLS_PASSWORD }, app);
    attachWebSocket(secureServer);
    secureServer.listen(HTTPS_PORT, () => console.log('Secure admin UI + WebSocket on https://localhost:'+HTTPS_PORT));
  } else {
    console.warn('HTTPS disabled; TLS certificate not found at', TLS_PFX);
  }
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
  if (RELAY_HOST.trim()) return RELAY_HOST.trim().replace(/^https?:\/\//i, '').replace(/\/+$/, '');
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

try {
  const bonjour = new Bonjour({}, (e) => console.warn('mDNS error', e.message));
  const service = publishRelayService(bonjour, ADMIN_PORT);
  console.log(`DNS-SD service advertised: ${service.fqdn} on port ${service.port}`);
} catch (e) {
  console.warn('DNS-SD advertisement failed:', e.message);
}

// ponytail: top-level error boundary — log, don't crash
process.on('uncaughtException', (e) => console.error('[FATAL]', e));
process.on('unhandledRejection', (e) => console.error('[FATAL]', e));
connectLoop().catch((e) => console.error(e));
