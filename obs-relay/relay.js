const { OBSWebSocket } = require('obs-websocket-js');
const fetch = require('node-fetch');
const http = require('http');
const https = require('https');

const OBS_ADDRESS = process.env.OBS_ADDRESS || 'ws://192.168.1.25:4455';
const OBS_PASSWORD = process.env.OBS_PASSWORD || '';
const SOURCE_NAME = process.env.SOURCE_NAME || 'Browser Full';
const POLL_ON_START = process.env.POLL_ON_START !== 'false';
const DEVICE_STALE_MS = Number(process.env.DEVICE_STALE_MS || 10000);
const DEVICE_LATENCY_HISTORY_LIMIT = Number(process.env.DEVICE_LATENCY_HISTORY_LIMIT || 12);

const ADMIN_PORT = process.env.ADMIN_PORT || 3000;
const keepAliveAgents = {
  http: new http.Agent({ keepAlive: true, maxSockets: 8 }),
  https: new https.Agent({ keepAlive: true, maxSockets: 8 }),
};

// devices store
const fs = require('fs');
const path = require('path');
const DEVICES_FILE = path.join(__dirname, 'devices.json');
let devices = {};
function loadDevices(){
  try{
    const txt = fs.readFileSync(DEVICES_FILE,'utf8');
    devices = JSON.parse(txt||'{}');
  }catch(e){devices={}}
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
  devices[recordId] = {
    id: recordId,
    name: incomingName || previous.name || '',
    sourceName: incomingSourceName || previous.sourceName || '',
    url: incomingUrl || previous.url || '',
    lastSeen: now,
  };
  saveDevices();
  return devices[recordId];
}
function pickDeviceUrl(){
  // pick most recently seen device
  const list = Object.values(devices).sort((a,b)=> (Number(b.lastSeen)||0) - (Number(a.lastSeen)||0));
  return list.length? list[0].url : null;
}

function getTargetUrls() {
  const urls = Object.values(devices)
    .map((device) => device && device.url ? String(device.url).trim() : '')
    .filter(Boolean);
  return [...new Set(urls)];
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

function getDeviceLatencyMs(device) {
  const latencyMs = Number(device && device.latencyMs);
  return Number.isFinite(latencyMs) && latencyMs >= 0 ? Math.round(latencyMs) : null;
}

function getDeviceLatencyHistory(device) {
  if (!device || !Array.isArray(device.latencyHistory)) return [];
  return device.latencyHistory
    .map((value) => Number(value))
    .filter((value) => Number.isFinite(value) && value >= 0)
    .slice(-DEVICE_LATENCY_HISTORY_LIMIT);
}

function recordDeviceLatency(device, latencyMs, errorMessage) {
  if (!device) return;

  const history = getDeviceLatencyHistory(device);
  if (Number.isFinite(latencyMs) && latencyMs >= 0) {
    history.push(Math.round(latencyMs));
  }
  while (history.length > DEVICE_LATENCY_HISTORY_LIMIT) {
    history.shift();
  }

  device.latencyMs = Number.isFinite(latencyMs) && latencyMs >= 0 ? Math.round(latencyMs) : getDeviceLatencyMs(device);
  device.latencyHistory = history;
  device.latencyUpdatedAt = Date.now();
  if (errorMessage) {
    device.latencyError = String(errorMessage);
  } else {
    delete device.latencyError;
  }
  saveDevices();
}

function serializeDevice(device) {
  return {
    id: device.id,
    name: device.name,
    sourceName: device.sourceName,
    url: device.url,
    lastSeen: device.lastSeen,
    latencyMs: getDeviceLatencyMs(device),
    latencyHistory: getDeviceLatencyHistory(device),
    latencyUpdatedAt: Number(device && device.latencyUpdatedAt) || null,
    latencyError: device && device.latencyError ? String(device.latencyError) : null,
  };
}

function isDeviceFresh(device) {
  const ageMs = getDeviceAgeMs(device);
  return ageMs !== null && ageMs < DEVICE_STALE_MS;
}

function summarizeSceneMatch(items, sourceName) {
  return items.some((item) => item.sourceName === sourceName && item.sceneItemEnabled !== false);
}

function formatTopIssue(diagnostics) {
  if (!diagnostics.issues || diagnostics.issues.length === 0) return 'No obvious problems detected.';
  return diagnostics.issues[0];
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
        latencyMs: getDeviceLatencyMs(device),
        latencyHistory: getDeviceLatencyHistory(device),
        latencyUpdatedAt: Number(device && device.latencyUpdatedAt) || null,
        latencyError: device && device.latencyError ? String(device.latencyError) : null,
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
    deviceStaleMs: DEVICE_STALE_MS,
    programScene: currentProgramScene,
    previewScene: currentPreviewScene,
    totalDevices: deviceList.length,
    freshDevices: deviceList.filter((device) => device.fresh).length,
    devices: deviceList,
    issues,
  };
}

function buildAssistantReply(message, diagnostics) {
  const query = String(message || '').trim().toLowerCase();
  const lines = [];

  if (!query) {
    lines.push('I can check OBS connection, device registration, stale phones, and source mapping.');
  } else if (query.includes('register') || query.includes('registration') || query.includes('phone')) {
    lines.push('Phone registration check:');
  } else if (query.includes('obs') || query.includes('scene') || query.includes('tally') || query.includes('live')) {
    lines.push('Tally check:');
  } else {
    lines.push('Diagnostics summary:');
  }

  lines.push(`- Status: ${diagnostics.connected ? 'OBS connected' : 'OBS disconnected'}${diagnostics.connected ? `, Program ${diagnostics.programScene || 'unknown'}${diagnostics.previewScene ? `, Preview ${diagnostics.previewScene}` : ''}` : ''}`);
  lines.push(`- Devices: ${diagnostics.freshDevices}/${diagnostics.totalDevices} fresh`);
  lines.push(`- Best guess: ${formatTopIssue(diagnostics)}`);

  if (diagnostics.totalDevices > 0) {
    const activeDevices = diagnostics.devices
      .slice()
      .sort((a, b) => {
        const rank = { PROGRAM: 0, PREVIEW: 1, IDLE: 2 };
        return (rank[a.state] || 9) - (rank[b.state] || 9);
      })
      .slice(0, 4);
    lines.push('- Current phones:');
    activeDevices.forEach((device) => {
      lines.push(`  - ${device.sourceName || device.id}: ${device.fresh ? 'fresh' : 'stale'}, ${device.state}`);
    });
  }

  if (diagnostics.issues.length > 0) {
    lines.push('- Important issues:');
    diagnostics.issues.slice(0, 4).forEach((issue) => lines.push(`  - ${issue}`));
  }

  if (query.includes('fix') || query.includes('help') || query.includes('what should i do')) {
    lines.push('- Suggested next steps:');
    if (!diagnostics.connected) {
      lines.push('  1. Confirm OBS is open and obs-websocket is enabled on port 4455.');
    }
    if (diagnostics.totalDevices === 0) {
      lines.push('  2. On a phone, save the relay IP again as http://<relay-ip>:3000.');
    } else if (diagnostics.devices.some((device) => !device.fresh)) {
      lines.push('  2. Re-save the relay host on any stale phone so it pings again.');
    }
    if (diagnostics.devices.some((device) => !device.inProgram && !device.inPreview)) {
      lines.push('  3. Make sure the phone source name matches a Browser Source name in OBS exactly.');
    }
  }

  lines.push('Ask me about registration, OBS connection, scene mapping, or stale phones if you want a deeper check.');
  return lines.join('\n');
}

async function publishAdminState(reason = 'update') {
  if (adminStatePublishPromise) {
    adminStatePublishQueued = true;
    return adminStatePublishPromise;
  }

  adminStatePublishPromise = (async () => {
    try {
      const diagnostics = await collectDiagnostics();
      lastAdminState = {
        ...diagnostics,
        reason,
        publishedAt: Date.now(),
      };

      const payload = `event: state\nid: ${lastAdminState.publishedAt}\ndata: ${JSON.stringify(lastAdminState)}\n\n`;
      for (const client of adminClients) {
        if (client.destroyed) {
          adminClients.delete(client);
          continue;
        }
        try {
          client.write(payload);
        } catch (_error) {
          adminClients.delete(client);
        }
      }
    } catch (error) {
      console.warn('publishAdminState error', error.message || error);
    }
  })().finally(() => {
    adminStatePublishPromise = null;
    if (adminStatePublishQueued) {
      adminStatePublishQueued = false;
      void publishAdminState('queued');
    }
  });

  return adminStatePublishPromise;
}

const obs = new OBSWebSocket();
let programScene = null;
let previewScene = null;
let connected = false;
let currentTally = null;
const adminClients = new Set();
let adminStatePublishPromise = null;
let adminStatePublishQueued = false;
let lastAdminState = null;

function normalizeObsUrl(address) {
  if (/^wss?:\/\//i.test(address)) return address;
  return `ws://${address}`;
}

async function safePostTallyForDevice(device, state) {
  if (!device || !device.url) return;
  const url = `${String(device.url).replace(/\/$/, '')}/api/tally?tallyState=${state}&_t=${Date.now()}`;
  const startedAt = Date.now();
  const agent = /^https:/i.test(url) ? keepAliveAgents.https : keepAliveAgents.http;
  try {
    const res = await fetch(url, { method: 'GET', agent });
    recordDeviceLatency(device, Date.now() - startedAt, res.ok ? null : `HTTP ${res.status}`);
    console.log('posted tally', state, 'to', device.sourceName || device.url, '->', url, 'status', res.status);
  } catch (e) {
    recordDeviceLatency(device, null, e && e.message ? e.message : 'request failed');
    console.warn('failed to post tally to phone', e && e.message ? e.message : e);
  }
}

async function getSceneItems(sceneName) {
  try {
    const resp = await obs.call('GetSceneItemList', { sceneName });
    return resp.sceneItems || [];
  } catch (e) {
    console.warn('GetSceneItemList failed for', sceneName, e.message);
    return [];
  }
}

async function getProgramSceneName() {
  try {
    const resp = await obs.call('GetCurrentProgramScene');
    return resp.currentProgramSceneName || null;
  } catch (e) {
    console.warn('GetCurrentProgramScene failed', e.message);
    return null;
  }
}

async function getPreviewSceneName() {
  try {
    const resp = await obs.call('GetCurrentPreviewScene');
    return resp.currentPreviewSceneName || null;
  } catch (e) {
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
    const deviceList = Object.values(devices).filter((device) => device && device.url);

    await Promise.all(deviceList.map(async (device) => {
      const sourceName = getDeviceSourceName(device);
      const programHas = programItems.some((item) => item.sourceName === sourceName && item.sceneItemEnabled !== false);
      const previewHas = previewItems.some((item) => item.sourceName === sourceName && item.sceneItemEnabled !== false);
      const state = programHas ? 'PROGRAM' : previewHas ? 'PREVIEW' : 'IDLE';
      await safePostTallyForDevice(device, state);
    }));
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

  obs.on('ConnectionOpened', () => console.log('OBS connection opened'));
  obs.on('ConnectionClosed', (data) => {
    console.log('OBS connection closed', data);
    connected = false;
    currentTally = null;
    void publishAdminState('disconnected');
  });
  obs.on('Identified', () => console.log('OBS identified'));
  obs.on('AuthenticationSuccess', () => console.log('OBS auth success'));
  obs.on('AuthenticationFailure', (err) => console.warn('OBS auth failure', err));
  obs.on('error', (err) => console.warn('OBS error', err));
}

// --- Admin server (Express)
const express = require('express');
const bodyParser = require('body-parser');
function startAdmin(){
  loadDevices();
  console.log('Loaded devices:', Object.keys(devices).length);
  const app = express();
  app.use(bodyParser.json());
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
      } catch (_error) {
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
    const {id,name,sourceName,url} = req.body || {};
    if(!url) return res.status(400).json({error:'url required'});
    const device = upsertDevice({ id, name, sourceName, url });
    console.log('REGISTER', { id: device.id, name: device.name, sourceName: device.sourceName, url: device.url });
    void evaluateTally();
    void publishAdminState('register');
    res.json({ok:true,id:device.id});
  });
  app.post('/api/ping', (req,res)=>{
    const {id,name,sourceName,url} = req.body || {};
    if(!url) return res.status(400).json({error:'url required'});
    const device = upsertDevice({ id, name, sourceName, url });
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
      res.status(500).json({ error: error && error.message ? error.message : 'diagnostics unavailable' });
    }
  });
  app.post('/api/assistant', async (req, res) => {
    try {
      const message = req.body && req.body.message ? String(req.body.message) : '';
      const diagnostics = await collectDiagnostics();
      const reply = buildAssistantReply(message, diagnostics);
      res.json({ reply, diagnostics });
    } catch (error) {
      res.status(500).json({ error: error && error.message ? error.message : 'assistant unavailable' });
    }
  });
  app.listen(ADMIN_PORT, ()=>console.log('Admin UI listening on http://localhost:'+ADMIN_PORT));
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
    currentTally = null;
    void publishAdminState('retry');
    console.log('Retrying connection in 3s...');
    await new Promise((resolve) => setTimeout(resolve, 3000));
  }
}

// start admin UI
startAdmin();

connectLoop().catch((e) => console.error(e));
