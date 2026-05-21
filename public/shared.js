export function formatAgo(timestamp) {
  if (!timestamp) return 'never';
  const seconds = Math.max(0, Math.round((Date.now() - timestamp) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.round(seconds / 60);
  return `${minutes}m ago`;
}

export function resolutionPresetToSize(preset) {
  const presets = {
    '720p': { width: 1280, height: 720 },
    '1080p': { width: 1920, height: 1080 },
    '1440p': { width: 2560, height: 1440 },
    '4k': { width: 3840, height: 2160 },
  };
  return presets[preset] || presets['1080p'];
}

export function pickBestLens(devices, hint) {
  const normalizedHint = (hint || 'balanced').toLowerCase();
  const scored = devices.map((device) => {
    const label = (device.label || '').toLowerCase();
    let score = 0;
    if (label.includes('back') || label.includes('rear') || label.includes('environment')) score += 10;
    if (label.includes('wide')) score += normalizedHint === 'face' ? 7 : 2;
    if (label.includes('ultra')) score += normalizedHint === 'low light' ? -4 : 3;
    if (label.includes('tele')) score += normalizedHint === 'face' ? 4 : 1;
    if (label.includes('front') || label.includes('selfie')) score -= 5;
    if (normalizedHint === 'low light' && device.capabilities?.torch) score += 4;
    if (normalizedHint === 'sharpness' && device.capabilities?.imageHeight) score += 2;
    return { device, score };
  });

  scored.sort((a, b) => b.score - a.score);
  return scored[0]?.device?.deviceId || devices[0]?.deviceId || null;
}

export function formatCapability(value) {
  if (value === undefined || value === null) return 'n/a';
  if (typeof value === 'number') return String(Math.round(value * 100) / 100);
  return String(value);
}