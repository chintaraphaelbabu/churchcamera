// Pure, side-effect-free helpers extracted from relay.js.
// Nothing in this module reads env vars, the filesystem, the clock, or the network.

const DEFAULT_SOURCE_NAME = 'Browser Full';

function normalizeObsUrl(address) {
  if (/^wss?:\/\//i.test(address)) return address;
  return `ws://${address}`;
}

function summarizeSceneMatch(items, sourceName) {
  return items.some((item) => item.sourceName === sourceName && item.sceneItemEnabled !== false);
}

// Extract the hostname used to identify which browser source shows a phone.
function hostFromUrl(rawUrl) {
  if (!rawUrl) return '';
  try {
    return new URL(String(rawUrl)).hostname.toLowerCase();
  } catch (_) {
    return '';
  }
}

// A source name is "default" until a human (or auto-match) gives it a real
// OBS input name. Phones send their Android model on every heartbeat.
function isDefaultSourceName(device, sourceName, fallbackSourceName = DEFAULT_SOURCE_NAME) {
  const value = sourceName !== undefined
    ? (sourceName ? String(sourceName).trim() : '')
    : (device && device.sourceName ? String(device.sourceName).trim() : '');
  if (!value) return true;
  if (value === fallbackSourceName) return true;
  const deviceName = device && device.name ? String(device.name).trim() : '';
  if (deviceName && value === deviceName) return true;
  return false;
}

function getDeviceSourceName(device, fallbackSourceName = DEFAULT_SOURCE_NAME) {
  const sourceName = device && device.sourceName ? String(device.sourceName).trim() : '';
  if (sourceName) return sourceName;
  return fallbackSourceName;
}

// Returns null when lastSeen is missing, zero, negative, or not a number so
// callers cannot mistake an unknown device for a fresh one.
function getDeviceAgeMs(device, now) {
  const lastSeen = Number(device && device.lastSeen);
  if (!Number.isFinite(lastSeen) || lastSeen <= 0) return null;
  return now - lastSeen;
}

// A device is fresh while its age is strictly less than staleMs. At exactly
// staleMs (or older) it is already stale. A missing/invalid lastSeen (null,
// 0, NaN) is never fresh.
function isDeviceFresh(device, now, staleMs) {
  const ageMs = getDeviceAgeMs(device, now);
  return ageMs !== null && ageMs < staleMs;
}

// Pure equivalent of the old upsertDevice record-building logic. `previous`
// is the already-resolved stored record (including its id); this function
// only decides the new field values and never touches storage.
function buildDeviceRecord(payload, previous, now, sourceNameFallback = DEFAULT_SOURCE_NAME) {
  const incoming = payload || {};
  const prev = previous || {};
  const incomingId = incoming.id ? String(incoming.id) : null;
  const incomingUrl = incoming.url ? String(incoming.url) : null;
  const incomingName = incoming.name ? String(incoming.name) : null;
  const incomingSourceName = incoming.sourceName ? String(incoming.sourceName) : null;

  // ponytail: rtt from phone (previous cycle), half is one-way latency — no clock drift
  let latencyMs = null;
  if (incoming.rtt !== undefined) {
    const rtt = Number(incoming.rtt);
    if (Number.isFinite(rtt) && rtt > 0) latencyMs = Math.round(rtt / 2);
  }

  // Phones heartbeat with their Android model as sourceName. Never let that
  // clobber a name the relay auto-matched to an OBS browser source.
  const incomingIsDefault = !incomingSourceName
    || (incomingName && incomingSourceName === incomingName)
    || incomingSourceName === sourceNameFallback;

  let sourceName;
  let sourceNameAuto;
  if (prev.sourceNameAuto && incomingIsDefault) {
    sourceName = prev.sourceName;
    sourceNameAuto = true;
  } else {
    sourceName = incomingSourceName || prev.sourceName || '';
    sourceNameAuto = false;
  }

  const recordId = prev.id !== undefined && prev.id !== null
    ? prev.id
    : (incomingId || '');

  return {
    id: recordId,
    name: incomingName || prev.name || '',
    sourceName,
    sourceNameAuto,
    url: incomingUrl || prev.url || '',
    lastSeen: now,
    latencyMs,
    batteryLevel: incoming.batteryLevel !== undefined
      ? Number(incoming.batteryLevel)
      : (prev.batteryLevel !== undefined ? prev.batteryLevel : -1),
  };
}

module.exports = {
  DEFAULT_SOURCE_NAME,
  normalizeObsUrl,
  summarizeSceneMatch,
  hostFromUrl,
  isDefaultSourceName,
  getDeviceSourceName,
  getDeviceAgeMs,
  isDeviceFresh,
  buildDeviceRecord,
};
