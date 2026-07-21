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
  http.get('http://localhost:3001/api/devices', (res) => {
    let body = '';
    res.on('data', (d) => { body += d.toString(); });
    res.on('end', () => {
      relay.kill();
      if (res.statusCode === 200) {
        console.log('PASS: /api/devices returned 200');
        process.exit(0);
      } else {
        console.log('FAIL: expected 200, got', res.statusCode);
        process.exit(1);
      }
    });
  }).on('error', (e) => {
    relay.kill();
    console.log('FAIL:', e.message);
    process.exit(1);
  });
}, 2000);
