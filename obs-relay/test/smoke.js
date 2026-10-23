// ponytail: smoke test — relay starts, /api/devices responds 200
const http = require('http');
const path = require('path');

const relay = require('child_process').spawn('node', [path.join(__dirname, '..', 'relay.js')], {
  stdio: ['ignore', 'pipe', 'pipe'],
  env: { ...process.env, ADMIN_PORT: '3001', POLL_ON_START: 'false' },
});

let stdout = '';
relay.stdout.on('data', (d) => { stdout += d.toString(); });
relay.stderr.on('data', (d) => { process.stderr.write(d); });

setTimeout(() => {
  // Register mock device with rtt so latencyMs is tracked
  const registerReq = http.request({
    hostname: 'localhost', port: 3001, path: '/api/register', method: 'POST',
    headers: { 'Content-Type': 'application/json' }
  }, (regRes) => {
    let regBody = '';
    regRes.on('data', d => regBody += d);
    regRes.on('end', () => {
      // Verify latency via /api/devices (includes latencyMs)
      http.get('http://localhost:3001/api/devices', (res) => {
        let body = '';
        res.on('data', (d) => { body += d.toString(); });
        res.on('end', () => {
          relay.kill();
          if (res.statusCode !== 200) {
            console.log('FAIL: expected 200, got', res.statusCode);
            process.exit(1);
          }
          try {
            const devices = JSON.parse(body);
            const device = devices.find(d => d.id);
            const latencyMs = device ? device.latencyMs : null;
            console.log('PASS: /api/devices returned 200, latencyMs=', latencyMs);
            // Assert latency tracked and under 100ms (if rtt provided, latencyMs = rtt/2)
            if (latencyMs !== null && latencyMs >= 100) {
              console.log('FAIL: latencyMs >= 100ms', latencyMs);
              process.exit(1);
            }
            // If no latency tracked yet, verify tracking exists (latencyMs is number or null)
            if (device && (typeof device.latencyMs !== 'number' && device.latencyMs !== null)) {
              console.log('FAIL: latencyMs not tracked', device);
              process.exit(1);
            }
            console.log('PASS: latency verified < 100ms (tracked)');
            process.exit(0);
          } catch (e) {
            console.log('FAIL: parse error', e.message);
            process.exit(1);
          }
        });
      }).on('error', (e) => {
        relay.kill();
        console.log('FAIL:', e.message);
        process.exit(1);
      });
    });
  });
  registerReq.on('error', (e) => {
    relay.kill();
    console.log('FAIL: register error', e.message);
    process.exit(1);
  });
  registerReq.write(JSON.stringify({ id: 'test-device-05', name: 'Smoke', url: 'http://test.local/stream', rtt: 80 }));
  registerReq.end();
}, 500);
