import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const publicDir = path.join(__dirname, 'public');
const boundary = 'frame';

const state = {
  liveTargetId: null,
  devices: new Map(),
  sseClients: new Set(),
};

const placeholderJpeg = Buffer.from(
  '/9j/4AAQSkZJRgABAQAAAQABAAD/2wCEAAoHBwgHBgoICAkLCgoLDhgQDg0NDh0VFhEYIx8lJCIfIiEmKzAuJjU1Ki0vMTExGiQ7PDw8Jyo5PjgyMTAwM//AABEIAAEAAQMBIgACEQEDEQH/xAAUAAEAAAAAAAAAAAAAAAAAAAAC/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAwDAQACEAMAAAB1A//xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAE/AL//xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAEDAQE/AL//xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAECAQE/AL//2Q==',
  'base64'
);

function json(res, statusCode, payload) {
  res.writeHead(statusCode, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
  });
  res.end(JSON.stringify(payload));
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => {
      try {
        const text = Buffer.concat(chunks).toString('utf8');
        resolve(text ? JSON.parse(text) : {});
      } catch (error) {
        reject(error);
      }
    });
    req.on('error', reject);
  });
}

function newId(prefix) {
  return `${prefix}-${Math.random().toString(36).slice(2, 9)}`;
}

function getDevice(deviceId) {
  if (!state.devices.has(deviceId)) {
    state.devices.set(deviceId, {
      id: deviceId,
      label: 'Unknown camera',
      role: 'phone',
      url: null,
      lastSeen: Date.now(),
      active: false,
      online: true,
      settings: {
        deviceId: null,
        resolution: '1080p',
        bitrate: 6,
        fps: 30,
        quality: 0.8,
        zoom: 1,
        iso: null,
        shutterSpeed: null,
        whiteBalance: 'auto',
      },
      capabilities: {},
      latestFrame: placeholderJpeg,
      subscribers: new Set(),
    });
  }
  return state.devices.get(deviceId);
}

function snapshot() {
  return {
    liveTargetId: state.liveTargetId,
    devices: [...state.devices.values()].map((device) => ({
      id: device.id,
      label: device.label,
      role: device.role,
      url: device.url,
      lastSeen: device.lastSeen,
      active: device.active,
      online: device.online,
      settings: device.settings,
      capabilities: device.capabilities,
    })),
  };
}

function broadcast() {
  const message = `event: state\ndata: ${JSON.stringify(snapshot())}\n\n`;
  for (const response of state.sseClients) {
    response.write(message);
  }
}

function sendStreamFrame(response, frame) {
  response.write(`--${boundary}\r\n`);
  response.write('Content-Type: image/jpeg\r\n');
  response.write(`Content-Length: ${frame.length}\r\n\r\n`);
  response.write(frame);
  response.write('\r\n');
}

function contentType(filePath) {
  if (filePath.endsWith('.html')) return 'text/html; charset=utf-8';
  if (filePath.endsWith('.js')) return 'text/javascript; charset=utf-8';
  if (filePath.endsWith('.css')) return 'text/css; charset=utf-8';
  if (filePath.endsWith('.svg')) return 'image/svg+xml';
  return 'application/octet-stream';
}

function renderIndex() {
  return `<!doctype html>
<html>
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <link rel="stylesheet" href="/styles.css" />
    <title>Phone Webcam Bridge</title>
  </head>
  <body>
    <main class="landing">
      <h1>Phone Webcam Bridge</h1>
      <p>Open the phone view on your Android device and the dashboard on your laptop.</p>
      <div class="landing-links">
        <a href="/phone">Open phone control</a>
        <a href="/desk">Open laptop dashboard</a>
      </div>
    </main>
  </body>
</html>`;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const pathname = decodeURIComponent(url.pathname);

  if (req.method === 'GET' && pathname === '/') {
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    res.end(renderIndex());
    return;
  }

  if (req.method === 'GET' && (pathname === '/phone' || pathname === '/desk')) {
    const filePath = path.join(publicDir, `${pathname.slice(1)}.html`);
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
    res.end(fs.readFileSync(filePath));
    return;
  }

  if (req.method === 'GET' && pathname === '/events') {
    res.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-cache, no-transform',
      connection: 'keep-alive',
    });
    res.write(`event: state\ndata: ${JSON.stringify(snapshot())}\n\n`);
    state.sseClients.add(res);
    req.on('close', () => state.sseClients.delete(res));
    return;
  }

  if (req.method === 'GET' && pathname.startsWith('/stream/')) {
    const deviceId = pathname.replace('/stream/', '').replace('.mjpg', '');
    const device = getDevice(deviceId);

    res.writeHead(200, {
      'content-type': `multipart/x-mixed-replace; boundary=${boundary}`,
      'cache-control': 'no-cache, no-store, must-revalidate',
      connection: 'keep-alive',
      'x-content-type-options': 'nosniff',
    });

    sendStreamFrame(res, device.latestFrame);
    device.subscribers.add(res);

    const interval = setInterval(() => {
      sendStreamFrame(res, device.latestFrame);
    }, 250);

    req.on('close', () => {
      clearInterval(interval);
      device.subscribers.delete(res);
    });
    return;
  }

  if (req.method === 'GET' && pathname.startsWith('/public/')) {
    const filePath = path.join(publicDir, pathname.replace('/public/', ''));
    if (!filePath.startsWith(publicDir) || !fs.existsSync(filePath)) {
      res.writeHead(404);
      res.end('Not found');
      return;
    }
    res.writeHead(200, { 'content-type': contentType(filePath), 'cache-control': 'no-store' });
    fs.createReadStream(filePath).pipe(res);
    return;
  }

  if (req.method === 'GET' && pathname === '/api/state') {
    json(res, 200, snapshot());
    return;
  }

  if (req.method === 'POST' && pathname === '/api/register') {
    const body = await readBody(req);
    const deviceId = body.deviceId || newId('cam');
    const device = getDevice(deviceId);
    device.label = body.label || device.label;
    device.role = body.role || device.role;
    device.url = body.url || device.url || null;
    device.lastSeen = Date.now();
    device.online = true;
    device.capabilities = body.capabilities || device.capabilities;
    json(res, 200, { deviceId, state: snapshot() });
    broadcast();
    return;
  }

  if (req.method === 'POST' && pathname === '/api/settings') {
    const body = await readBody(req);
    if (!body.deviceId) {
      json(res, 400, { ok: false, error: 'deviceId is required' });
      return;
    }
    const device = getDevice(body.deviceId);
    device.settings = { ...device.settings, ...body.settings };
    device.label = body.label || device.label;
    device.lastSeen = Date.now();
    broadcast();
    json(res, 200, { ok: true, state: snapshot() });
    return;
  }

  if (req.method === 'POST' && pathname === '/api/frame') {
    const body = await readBody(req);
    if (!body.deviceId) {
      json(res, 400, { ok: false, error: 'deviceId is required' });
      return;
    }
    const device = getDevice(body.deviceId);
    const base64 = (body.jpegBase64 || '').replace(/^data:image\/jpeg;base64,/, '');
    if (base64) {
      device.latestFrame = Buffer.from(base64, 'base64');
    }
    device.lastSeen = Date.now();
    json(res, 200, { ok: true });
    return;
  }

  if (req.method === 'POST' && pathname === '/api/live') {
    const body = await readBody(req);
    if (!body.deviceId) {
      json(res, 400, { ok: false, error: 'deviceId is required' });
      return;
    }
    state.liveTargetId = body.deviceId || null;
    for (const device of state.devices.values()) {
      device.active = device.id === state.liveTargetId;
    }
    broadcast();
    json(res, 200, { ok: true, state: snapshot() });
    return;
  }

  if (req.method === 'POST' && pathname === '/api/ping') {
    const body = await readBody(req);
    if (!body.deviceId) {
      json(res, 400, { ok: false, error: 'deviceId is required' });
      return;
    }
    const device = getDevice(body.deviceId);
    device.lastSeen = Date.now();
    device.online = true;
    json(res, 200, { ok: true });
    return;
  }

  const assetPath = path.join(publicDir, pathname.replace(/^\//, ''));
  if (req.method === 'GET' && assetPath.startsWith(publicDir) && fs.existsSync(assetPath) && fs.statSync(assetPath).isFile()) {
    res.writeHead(200, { 'content-type': contentType(assetPath), 'cache-control': 'no-store' });
    fs.createReadStream(assetPath).pipe(res);
    return;
  }

  res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
  res.end('Not found');
});

setInterval(() => {
  const cutoff = Date.now() - 15000;
  let changed = false;
  for (const device of state.devices.values()) {
    const nextOnline = device.lastSeen >= cutoff;
    if (device.online !== nextOnline) {
      device.online = nextOnline;
      changed = true;
    }
  }
  if (changed) {
    broadcast();
  }
}, 5000);

server.listen(8787, '0.0.0.0', () => {
  console.log('Phone Webcam Bridge running on http://0.0.0.0:8787');
});