// ponytail: smoke test — relay starts, /api/devices responds 200, register is tracked
const http = require('http');
const path = require('path');
const { spawn } = require('child_process');

const HOST = 'localhost';
const PORT = 3001;
const READY_TIMEOUT_MS = 10000;
const READY_POLL_MS = 100;

const relay = spawn('node', [path.join(__dirname, '..', 'relay.js')], {
  stdio: ['ignore', 'pipe', 'pipe'],
  env: { ...process.env, ADMIN_PORT: String(PORT), POLL_ON_START: 'false' },
});

let stdout = '';
relay.stdout.on('data', (d) => { stdout += d.toString(); });
relay.stderr.on('data', (d) => { process.stderr.write(d); });

let finished = false;
let childExit = null;

// Kill the relay and only exit once the child is actually gone, so a failing
// test can never leave an orphan holding port 3001.
function finish(code) {
  if (finished) return;
  finished = true;

  if (relay.exitCode !== null || relay.signalCode !== null) {
    process.exit(code);
    return;
  }

  const timer = setTimeout(() => process.exit(code), 2000);
  relay.once('exit', () => {
    clearTimeout(timer);
    process.exit(code);
  });
  try {
    relay.kill();
  } catch (_) {
    clearTimeout(timer);
    process.exit(code);
  }
}

relay.on('exit', (code, signal) => {
  childExit = { code, signal };
  if (!finished) {
    console.log(`FAIL: relay exited before the smoke test finished (code=${code}, signal=${signal})`);
    if (stdout.trim()) console.log('--- relay stdout ---\n' + stdout.trim());
    finish(1);
  }
});

relay.on('error', (err) => {
  console.log('FAIL: could not start relay:', err.message);
  finish(1);
});

process.on('SIGINT', () => finish(130));
process.on('SIGTERM', () => finish(143));
process.on('exit', () => {
  try {
    if (relay.exitCode === null && relay.signalCode === null) relay.kill();
  } catch (_) { /* already gone */ }
});

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function request(method, urlPath, payload) {
  return new Promise((resolve, reject) => {
    const body = payload === undefined ? null : JSON.stringify(payload);
    const headers = body === null
      ? {}
      : { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) };

    const req = http.request({ hostname: HOST, port: PORT, path: urlPath, method, headers }, (res) => {
      let text = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => { text += chunk; });
      res.on('end', () => resolve({ statusCode: res.statusCode, body: text }));
    });
    req.on('error', reject);
    if (body !== null) req.write(body);
    req.end();
  });
}

async function waitForReady() {
  const startedAt = Date.now();
  const deadline = startedAt + READY_TIMEOUT_MS;
  let lastError = 'no response yet';

  while (Date.now() < deadline) {
    if (childExit) {
      throw new Error(`relay exited before listening (code=${childExit.code}, signal=${childExit.signal})`);
    }
    try {
      const res = await request('GET', '/api/devices');
      if (res.statusCode === 200) {
        console.log(`PASS: relay ready after ${Date.now() - startedAt}ms (/api/devices returned 200)`);
        return;
      }
      lastError = `HTTP ${res.statusCode}`;
    } catch (err) {
      lastError = err.message;
    }
    await delay(READY_POLL_MS);
  }

  throw new Error(`relay not ready within ${READY_TIMEOUT_MS}ms (last error: ${lastError})`);
}

async function main() {
  await waitForReady();

  // Register mock device with rtt so latencyMs is tracked
  const reg = await request('POST', '/api/register', {
    id: 'test-device-05',
    name: 'Smoke',
    url: 'http://test.local/stream',
    rtt: 80,
  });
  if (reg.statusCode < 200 || reg.statusCode >= 300) {
    console.log(`FAIL: /api/register returned ${reg.statusCode}: ${reg.body}`);
    finish(1);
    return;
  }
  console.log(`PASS: /api/register returned ${reg.statusCode}`);

  let registeredId = null;
  try {
    registeredId = JSON.parse(reg.body).id || null;
  } catch (_) { /* body check happens below */ }

  // Verify latency via /api/devices (includes latencyMs)
  const res = await request('GET', '/api/devices');
  if (res.statusCode !== 200) {
    console.log('FAIL: expected 200, got', res.statusCode);
    finish(1);
    return;
  }

  let devices;
  try {
    devices = JSON.parse(res.body);
  } catch (e) {
    console.log('FAIL: parse error', e.message);
    finish(1);
    return;
  }

  const device = Array.isArray(devices)
    ? devices.find((d) => d && (registeredId ? d.id === registeredId : d.url === 'http://test.local/stream'))
    : null;
  if (!device) {
    console.log('FAIL: registered device not found in /api/devices', registeredId || reg.body);
    finish(1);
    return;
  }

  console.log('PASS: /api/devices returned 200, device exists, latencyMs=', device.latencyMs);

  // Assert latency tracked and under 100ms (rtt 80 => latencyMs = 40)
  const latencyMs = device.latencyMs;
  if (typeof latencyMs !== 'number' || !Number.isFinite(latencyMs)) {
    console.log('FAIL: latencyMs not tracked', device);
    finish(1);
    return;
  }
  if (latencyMs >= 100) {
    console.log('FAIL: latencyMs >= 100ms', latencyMs);
    finish(1);
    return;
  }
  console.log(`PASS: latency verified < 100ms (tracked, rtt 80 => ${latencyMs}ms)`);

  finish(0);
}

main().catch((err) => {
  console.log('FAIL:', err.message);
  finish(1);
});
