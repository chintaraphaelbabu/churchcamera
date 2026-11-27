// Unit tests for the relay's pure helpers. These never start a server, touch
// OBS, read devices.json, or use the network or the real clock.
const test = require('node:test');
const assert = require('node:assert/strict');

const {
  normalizeObsUrl,
  summarizeSceneMatch,
  hostFromUrl,
  isDefaultSourceName,
  getDeviceSourceName,
  getDeviceAgeMs,
  isDeviceFresh,
  buildDeviceRecord,
} = require('../lib/pure');
const { publishRelayService } = require('../lib/discovery');

const SOURCE_NAME = 'Browser Full';

test('publishRelayService advertises the relay as a TCP DNS-SD service', () => {
  let publishedOptions;
  const expectedService = { fqdn: 'ChurchCam Relay._churchcam._tcp.local', port: 3000 };
  const bonjour = {
    publish(options) {
      publishedOptions = options;
      return expectedService;
    },
  };

  assert.equal(publishRelayService(bonjour, '3000'), expectedService);
  assert.deepEqual(publishedOptions, {
    name: 'ChurchCam Relay',
    type: 'churchcam',
    protocol: 'tcp',
    port: 3000,
  });
});

test('normalizeObsUrl adds ws:// only when no ws/wss scheme is present', () => {
  assert.equal(normalizeObsUrl('localhost:4455'), 'ws://localhost:4455');
  assert.equal(normalizeObsUrl('192.168.1.10:4455'), 'ws://192.168.1.10:4455');
  assert.equal(normalizeObsUrl('ws://localhost:4455'), 'ws://localhost:4455');
  assert.equal(normalizeObsUrl('wss://obs.example.com:4455'), 'wss://obs.example.com:4455');
  assert.equal(normalizeObsUrl('WS://LOCALHOST:4455'), 'WS://LOCALHOST:4455');
  assert.equal(normalizeObsUrl('ws://localhost:4455/path'), 'ws://localhost:4455/path');
});

test('summarizeSceneMatch requires a matching item', () => {
  const items = [
    { sourceName: 'Cam A', sceneItemEnabled: true },
    { sourceName: 'Cam B', sceneItemEnabled: false },
    { sourceName: 'Cam C' },
  ];
  assert.equal(summarizeSceneMatch(items, 'Cam A'), true);
  assert.equal(summarizeSceneMatch(items, 'Cam C'), true);
  assert.equal(summarizeSceneMatch(items, 'Missing'), false);
  assert.equal(summarizeSceneMatch([], 'Cam A'), false);
  assert.equal(summarizeSceneMatch(items, 'cam a'), false);
});

test('summarizeSceneMatch rejects matching items that are explicitly disabled', () => {
  assert.equal(summarizeSceneMatch([{ sourceName: 'Cam A', sceneItemEnabled: false }], 'Cam A'), false);
});

test('summarizeSceneMatch accepts any enabled matching item', () => {
  const items = [
    { sourceName: 'Cam A', sceneItemEnabled: false },
    { sourceName: 'Cam A', sceneItemEnabled: true },
  ];
  assert.equal(summarizeSceneMatch(items, 'Cam A'), true);
});

test('hostFromUrl returns a lowercased hostname', () => {
  assert.equal(hostFromUrl('http://Phone.Local:8080/stream'), 'phone.local');
  assert.equal(hostFromUrl('https://192.168.1.20/stream'), '192.168.1.20');
  assert.equal(hostFromUrl('ws://Example.COM:4455'), 'example.com');
});

test('hostFromUrl returns an empty string for missing or invalid URLs', () => {
  assert.equal(hostFromUrl(''), '');
  assert.equal(hostFromUrl(null), '');
  assert.equal(hostFromUrl(undefined), '');
  assert.equal(hostFromUrl('not a url'), '');
  assert.equal(hostFromUrl('://missing-scheme'), '');
  assert.equal(hostFromUrl('http://'), '');
});

test('isDefaultSourceName treats blank, device-name, and fallback names as default', () => {
  assert.equal(isDefaultSourceName({ name: 'Pixel 8', sourceName: 'Pixel 8' }), true);
  assert.equal(isDefaultSourceName({ name: 'Pixel 8', sourceName: SOURCE_NAME }), true);
  assert.equal(isDefaultSourceName({ name: 'Pixel 8', sourceName: '   ' }), true);
  assert.equal(isDefaultSourceName({ name: 'Pixel 8' }), true);
  assert.equal(isDefaultSourceName({ name: 'Pixel 8', sourceName: `  ${SOURCE_NAME}  ` }), true);
});

test('isDefaultSourceName trims the device name before comparing', () => {
  assert.equal(isDefaultSourceName({ name: '  Pixel 8  ', sourceName: 'Pixel 8' }), true);
});

test('isDefaultSourceName treats an explicit OBS name as non-default', () => {
  assert.equal(isDefaultSourceName({ name: 'Pixel 8', sourceName: 'OBS Cam 1' }), false);
  assert.equal(isDefaultSourceName({ name: 'Pixel 8', sourceName: '  OBS Cam 1  ' }), false);
});

test('isDefaultSourceName accepts an explicit source name argument', () => {
  const device = { name: 'Pixel 8', sourceName: 'OBS Cam 1' };
  assert.equal(isDefaultSourceName(device, 'Pixel 8'), true);
  assert.equal(isDefaultSourceName(device, SOURCE_NAME), true);
  assert.equal(isDefaultSourceName(device, ''), true);
  assert.equal(isDefaultSourceName(device, 'OBS Cam 1'), false);
});

test('isDefaultSourceName accepts a custom fallback name', () => {
  assert.equal(isDefaultSourceName({ name: 'X', sourceName: 'House Left' }, undefined, 'House Left'), true);
  assert.equal(isDefaultSourceName({ name: 'X', sourceName: 'House Right' }, undefined, 'House Left'), false);
});

test('isDefaultSourceName handles a missing device', () => {
  assert.equal(isDefaultSourceName(null), true);
  assert.equal(isDefaultSourceName(undefined, SOURCE_NAME), true);
  assert.equal(isDefaultSourceName(undefined, 'OBS Cam'), false);
});

test('getDeviceSourceName prefers the trimmed device source name', () => {
  assert.equal(getDeviceSourceName({ sourceName: 'OBS Cam 1' }, 'Fallback'), 'OBS Cam 1');
  assert.equal(getDeviceSourceName({ sourceName: '  OBS Cam 1  ' }, 'Fallback'), 'OBS Cam 1');
});

test('getDeviceSourceName falls back when the device source name is blank', () => {
  assert.equal(getDeviceSourceName({ sourceName: '' }, 'Fallback'), 'Fallback');
  assert.equal(getDeviceSourceName({ sourceName: '   ' }, 'Fallback'), 'Fallback');
  assert.equal(getDeviceSourceName({}, 'Fallback'), 'Fallback');
  assert.equal(getDeviceSourceName(null, 'Fallback'), 'Fallback');
});

test('getDeviceSourceName defaults to Browser Full', () => {
  assert.equal(getDeviceSourceName({}), SOURCE_NAME);
  assert.equal(getDeviceSourceName(null), SOURCE_NAME);
});

test('getDeviceAgeMs computes age from lastSeen', () => {
  assert.equal(getDeviceAgeMs({ lastSeen: 1000 }, 5000), 4000);
  assert.equal(getDeviceAgeMs({ lastSeen: '1000' }, 5000), 4000);
  assert.equal(getDeviceAgeMs({ lastSeen: 5000 }, 5000), 0);
  assert.equal(getDeviceAgeMs({ lastSeen: 6000 }, 5000), -1000);
});

test('getDeviceAgeMs returns null for missing or invalid lastSeen', () => {
  assert.equal(getDeviceAgeMs({}, 5000), null);
  assert.equal(getDeviceAgeMs({ lastSeen: null }, 5000), null);
  assert.equal(getDeviceAgeMs({ lastSeen: 0 }, 5000), null);
  assert.equal(getDeviceAgeMs({ lastSeen: -1 }, 5000), null);
  assert.equal(getDeviceAgeMs({ lastSeen: 'nope' }, 5000), null);
  assert.equal(getDeviceAgeMs(null, 5000), null);
});

test('isDeviceFresh is strict: age must be less than staleMs', () => {
  const staleMs = 10000;
  assert.equal(isDeviceFresh({ lastSeen: 9001 }, 19000, staleMs), true); // age 9999
  assert.equal(isDeviceFresh({ lastSeen: 9000 }, 19000, staleMs), false); // age 10000 == boundary
  assert.equal(isDeviceFresh({ lastSeen: 8999 }, 19000, staleMs), false); // age 10001
  assert.equal(isDeviceFresh({ lastSeen: 19000 }, 19000, staleMs), true); // age 0
});

test('isDeviceFresh rejects missing, zero, and invalid lastSeen', () => {
  const staleMs = 10000;
  assert.equal(isDeviceFresh({}, 19000, staleMs), false);
  assert.equal(isDeviceFresh({ lastSeen: null }, 19000, staleMs), false);
  assert.equal(isDeviceFresh({ lastSeen: 0 }, 19000, staleMs), false);
  assert.equal(isDeviceFresh({ lastSeen: 'nope' }, 19000, staleMs), false);
  assert.equal(isDeviceFresh(null, 19000, staleMs), false);
});

test('isDeviceFresh treats a future lastSeen as fresh', () => {
  assert.equal(isDeviceFresh({ lastSeen: 20000 }, 19000, 10000), true); // age -1000
});

test('buildDeviceRecord creates a new record from a full payload', () => {
  const record = buildDeviceRecord(
    { id: 'phone-1', name: 'Pixel 8', sourceName: 'OBS Cam 1', url: 'http://phone.local/stream', rtt: 81, batteryLevel: 55 },
    { id: 'dev-1' },
    12345,
    SOURCE_NAME
  );

  assert.deepEqual(record, {
    id: 'dev-1',
    name: 'Pixel 8',
    sourceName: 'OBS Cam 1',
    sourceNameAuto: false,
    url: 'http://phone.local/stream',
    lastSeen: 12345,
    latencyMs: 41,
    batteryLevel: 55,
  });
});

test('buildDeviceRecord uses the resolved previous id when present', () => {
  const record = buildDeviceRecord({ id: 'incoming', name: 'N' }, { id: 'resolved' }, 1, SOURCE_NAME);
  assert.equal(record.id, 'resolved');
});

test('buildDeviceRecord falls back to the payload id when there is no previous id', () => {
  assert.equal(buildDeviceRecord({ id: 'incoming', name: 'N' }, {}, 1, SOURCE_NAME).id, 'incoming');
  assert.equal(buildDeviceRecord({}, {}, 1, SOURCE_NAME).id, '');
});

test('buildDeviceRecord preserves previous values when incoming values are missing', () => {
  const previous = {
    id: 'dev-1',
    name: 'Pixel 8',
    sourceName: 'OBS Cam 1',
    sourceNameAuto: false,
    url: 'http://phone.local/stream',
    lastSeen: 111,
    latencyMs: 10,
    batteryLevel: 50,
  };

  assert.deepEqual(buildDeviceRecord({}, previous, 999, SOURCE_NAME), {
    id: 'dev-1',
    name: 'Pixel 8',
    sourceName: 'OBS Cam 1',
    sourceNameAuto: false,
    url: 'http://phone.local/stream',
    lastSeen: 999,
    latencyMs: null,
    batteryLevel: 50,
  });
});

test('buildDeviceRecord handles a null payload like an empty payload', () => {
  const previous = { id: 'dev-1', name: 'Pixel 8', url: 'http://phone.local/stream', batteryLevel: 50 };
  const record = buildDeviceRecord(null, previous, 999, SOURCE_NAME);
  assert.equal(record.name, 'Pixel 8');
  assert.equal(record.url, 'http://phone.local/stream');
  assert.equal(record.batteryLevel, 50);
  assert.equal(record.latencyMs, null);
});

test('buildDeviceRecord always stamps lastSeen with the supplied now', () => {
  const record = buildDeviceRecord({ id: 'x', lastSeen: 1 }, { id: 'x' }, 4242, SOURCE_NAME);
  assert.equal(record.lastSeen, 4242);
});

test('buildDeviceRecord computes half the round-trip latency', () => {
  const cases = [
    [80, 40],
    [81, 41],
    ['80', 40],
    [0, null],
    [-20, null],
    [Number.NaN, null],
    [Infinity, null],
    [undefined, null],
  ];

  for (const [rtt, expected] of cases) {
    const record = buildDeviceRecord({ id: 'x', rtt }, { id: 'x' }, 1, SOURCE_NAME);
    assert.equal(record.latencyMs, expected, `rtt=${String(rtt)}`);
  }
});

test('buildDeviceRecord records battery level, defaults to -1, and preserves previous', () => {
  assert.equal(buildDeviceRecord({ id: 'x', batteryLevel: 0 }, { id: 'x' }, 1, SOURCE_NAME).batteryLevel, 0);
  assert.equal(buildDeviceRecord({ id: 'x', batteryLevel: 77 }, { id: 'x' }, 1, SOURCE_NAME).batteryLevel, 77);
  assert.equal(buildDeviceRecord({ id: 'x', batteryLevel: null }, { id: 'x' }, 1, SOURCE_NAME).batteryLevel, 0);
  assert.equal(buildDeviceRecord({ id: 'x' }, { id: 'x' }, 1, SOURCE_NAME).batteryLevel, -1);
  assert.equal(buildDeviceRecord({ id: 'x' }, { id: 'x', batteryLevel: 33 }, 1, SOURCE_NAME).batteryLevel, 33);
});

test('buildDeviceRecord prefers incoming name and url but preserves them when absent', () => {
  const previous = { id: 'x', name: 'Old Name', url: 'http://old.local/stream' };

  assert.equal(buildDeviceRecord({ id: 'x', name: 'New Name' }, previous, 1, SOURCE_NAME).name, 'New Name');
  assert.equal(buildDeviceRecord({ id: 'x', url: 'http://new.local/stream' }, previous, 1, SOURCE_NAME).url, 'http://new.local/stream');
  assert.equal(buildDeviceRecord({ id: 'x', name: '' }, previous, 1, SOURCE_NAME).name, 'Old Name');
  assert.equal(buildDeviceRecord({ id: 'x', url: '' }, previous, 1, SOURCE_NAME).url, 'http://old.local/stream');
  assert.equal(buildDeviceRecord({ id: 'x' }, previous, 1, SOURCE_NAME).name, 'Old Name');
  assert.equal(buildDeviceRecord({ id: 'x' }, previous, 1, SOURCE_NAME).url, 'http://old.local/stream');
});

test('buildDeviceRecord preserves an auto-matched source name when the phone sends its default again', () => {
  const previous = { id: 'x', name: 'Pixel 8', sourceName: 'OBS Cam 1', sourceNameAuto: true };
  const cases = [
    { name: 'Pixel 8', sourceName: 'Pixel 8' },      // Android model name
    { name: 'Pixel 8', sourceName: '' },              // blank source name
    { name: 'Pixel 8' },                              // missing source name
    { name: 'Pixel 8', sourceName: SOURCE_NAME },     // configured fallback
  ];

  for (const payload of cases) {
    const record = buildDeviceRecord({ id: 'x', ...payload }, previous, 1, SOURCE_NAME);
    assert.equal(record.sourceName, 'OBS Cam 1', JSON.stringify(payload));
    assert.equal(record.sourceNameAuto, true, JSON.stringify(payload));
  }
});

test('buildDeviceRecord lets an explicit source name replace a previous one and clears sourceNameAuto', () => {
  const previous = { id: 'x', name: 'Pixel 8', sourceName: 'OBS Cam 1', sourceNameAuto: true };
  const record = buildDeviceRecord({ id: 'x', name: 'Pixel 8', sourceName: 'OBS Cam 2' }, previous, 1, SOURCE_NAME);

  assert.equal(record.sourceName, 'OBS Cam 2');
  assert.equal(record.sourceNameAuto, false);
});

test('buildDeviceRecord clears sourceNameAuto when the same explicit name is sent', () => {
  const previous = { id: 'x', name: 'Pixel 8', sourceName: 'OBS Cam 1', sourceNameAuto: true };
  const record = buildDeviceRecord({ id: 'x', name: 'Pixel 8', sourceName: 'OBS Cam 1' }, previous, 1, SOURCE_NAME);

  assert.equal(record.sourceName, 'OBS Cam 1');
  assert.equal(record.sourceNameAuto, false);
});

test('buildDeviceRecord uses the provided fallback source name for default detection', () => {
  const previous = { id: 'x', name: 'Pixel 8', sourceName: 'OBS Cam 1', sourceNameAuto: true };
  const record = buildDeviceRecord({ id: 'x', name: 'Pixel 8', sourceName: 'House Left' }, previous, 1, 'House Left');

  assert.equal(record.sourceName, 'OBS Cam 1');
  assert.equal(record.sourceNameAuto, true);
});

test('buildDeviceRecord defaults sourceNameFallback to Browser Full', () => {
  const previous = { id: 'x', name: 'Pixel 8', sourceName: 'OBS Cam 1', sourceNameAuto: true };
  const record = buildDeviceRecord({ id: 'x', name: 'Pixel 8', sourceName: SOURCE_NAME }, previous, 1);

  assert.equal(record.sourceName, 'OBS Cam 1');
  assert.equal(record.sourceNameAuto, true);
});

test('buildDeviceRecord stores an explicit source name on a new device without marking it auto', () => {
  const record = buildDeviceRecord({ id: 'x', name: 'Pixel 8', sourceName: 'OBS Cam 1' }, { id: 'x' }, 1, SOURCE_NAME);

  assert.equal(record.sourceName, 'OBS Cam 1');
  assert.equal(record.sourceNameAuto, false);
});

test('buildDeviceRecord leaves sourceName empty when none is known', () => {
  const record = buildDeviceRecord({ id: 'x', name: 'Pixel 8' }, { id: 'x' }, 1, SOURCE_NAME);

  assert.equal(record.sourceName, '');
  assert.equal(record.sourceNameAuto, false);
});
