import { formatAgo, pickBestLens, resolutionPresetToSize } from './shared.js';

const elements = {
  preview: document.getElementById('preview'),
  cameraSelect: document.getElementById('cameraSelect'),
  resolutionSelect: document.getElementById('resolutionSelect'),
  fpsSelect: document.getElementById('fpsSelect'),
  qualitySelect: document.getElementById('qualitySelect'),
  bitrateSelect: document.getElementById('bitrateSelect'),
  refreshDevices: document.getElementById('refreshDevices'),
  smartPick: document.getElementById('smartPick'),
  setLive: document.getElementById('setLive'),
  isoControl: document.getElementById('isoControl'),
  shutterControl: document.getElementById('shutterControl'),
  wbControl: document.getElementById('wbControl'),
  zoomControl: document.getElementById('zoomControl'),
  connectionDot: document.getElementById('connectionDot'),
  connectionText: document.getElementById('connectionText'),
  liveDot: document.getElementById('liveDot'),
  liveText: document.getElementById('liveText'),
  previewDot: document.getElementById('previewDot'),
  previewText: document.getElementById('previewText'),
  qualityReadout: document.getElementById('qualityReadout'),
  qualityLabel: document.getElementById('qualityLabel'),
  bitrateLabel: document.getElementById('bitrateLabel'),
  lastSync: document.getElementById('lastSync'),
  capabilityLabel: document.getElementById('capabilityLabel'),
  deviceName: document.getElementById('deviceName'),
};

const state = {
  deviceId: localStorage.getItem('cameraBridge.deviceId') || null,
  stream: null,
  track: null,
  mediaCapabilities: {},
  settings: {
    deviceId: null,
    resolution: '1080p',
    fps: 30,
    quality: 0.8,
    bitrate: 6,
    zoom: 1,
    iso: 200,
    shutterSpeed: 60,
    whiteBalance: 'auto',
  },
  devices: [],
  active: false,
  liveTargetId: null,
  frameLoop: null,
  syncTimer: null,
  sendingFrame: false,
};

const canvas = document.createElement('canvas');
const context = canvas.getContext('2d', { alpha: false, desynchronized: true });

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'content-type': 'application/json' },
    ...options,
  });
  if (!response.ok) {
    throw new Error(`${path} failed: ${response.status}`);
  }
  return response.json();
}

async function registerDevice() {
  const result = await api('/api/register', {
    method: 'POST',
    body: JSON.stringify({
      deviceId: state.deviceId,
      label: 'Android phone',
      role: 'phone',
      capabilities: state.mediaCapabilities,
    }),
  });
  state.deviceId = result.deviceId;
  localStorage.setItem('cameraBridge.deviceId', state.deviceId);
  state.liveTargetId = result.state.liveTargetId;
}

async function refreshDeviceList() {
  await ensurePermissionProbe();
  const devices = await navigator.mediaDevices.enumerateDevices();
  state.devices = devices.filter((device) => device.kind === 'videoinput');
  elements.cameraSelect.innerHTML = state.devices.map((device, index) => `<option value="${device.deviceId}">${device.label || `Camera ${index + 1}`}</option>`).join('');
  if (!elements.cameraSelect.value && state.devices[0]) {
    elements.cameraSelect.value = state.devices[0].deviceId;
  }
  updateCapabilityLabel();
}

async function ensurePermissionProbe() {
  if (!navigator.mediaDevices?.getUserMedia) {
    throw new Error('Camera API unavailable. Open this page over HTTPS or localhost, then allow camera permission.');
  }
  if (state.track) return;
  const probe = await navigator.mediaDevices.getUserMedia({ video: true, audio: false });
  probe.getTracks().forEach((track) => track.stop());
}

function updateCapabilityLabel() {
  const supported = [];
  if (state.track?.getCapabilities) {
    const caps = state.track.getCapabilities();
    if ('zoom' in caps) supported.push('zoom');
    if ('exposureMode' in caps || 'exposureCompensation' in caps) supported.push('exposure');
    if ('whiteBalanceMode' in caps) supported.push('white balance');
    if ('frameRate' in caps) supported.push('frame rate');
  }
  elements.capabilityLabel.textContent = supported.length ? supported.join(', ') : 'browser-limited';
}

async function startStream() {
  if (!navigator.mediaDevices?.getUserMedia) {
    throw new Error('Camera API unavailable. On phone browsers, camera capture usually requires HTTPS.');
  }
  if (state.stream) {
    state.stream.getTracks().forEach((track) => track.stop());
  }

  const deviceId = elements.cameraSelect.value || state.devices[0]?.deviceId || undefined;
  const { width, height } = resolutionPresetToSize(elements.resolutionSelect.value);
  const frameRate = Number(elements.fpsSelect.value);
  const constraints = {
    video: {
      deviceId: deviceId ? { exact: deviceId } : undefined,
      width: { ideal: width },
      height: { ideal: height },
      frameRate: { ideal: frameRate, max: frameRate },
      facingMode: 'environment',
    },
    audio: false,
  };

  state.stream = await navigator.mediaDevices.getUserMedia(constraints);
  state.track = state.stream.getVideoTracks()[0];
  elements.preview.srcObject = state.stream;
  elements.deviceName.textContent = state.devices.find((device) => device.deviceId === deviceId)?.label || 'selected camera';
  updateCapabilityLabel();
  await applyVisibleConstraints();
  startFramePump();
}

async function applyVisibleConstraints() {
  if (!state.track?.applyConstraints) return;

  const constraints = {};
  const capabilities = state.track.getCapabilities ? state.track.getCapabilities() : {};

  if ('zoom' in capabilities) {
    constraints.zoom = Number(elements.zoomControl.value);
  }
  if ('frameRate' in capabilities) {
    constraints.frameRate = { ideal: Number(elements.fpsSelect.value) };
  }
  if ('whiteBalanceMode' in capabilities) {
    constraints.whiteBalanceMode = elements.wbControl.value === 'auto' ? 'continuous' : 'manual';
  }
  if ('exposureMode' in capabilities) {
    constraints.exposureMode = 'continuous';
  }
  if (Object.keys(constraints).length) {
    try {
      await state.track.applyConstraints(constraints);
    } catch {
      // Best-effort only. Unsupported controls stay visible but disabled by the browser.
    }
  }
}

function startFramePump() {
  if (state.frameLoop) {
    cancelAnimationFrame(state.frameLoop);
  }

  const pump = async () => {
    if (!state.track || state.sendingFrame) {
      state.frameLoop = requestAnimationFrame(pump);
      return;
    }

    const quality = Number(elements.qualitySelect.value) / 100;
    const fps = Number(elements.fpsSelect.value);
    const interval = 1000 / fps;

    if (!pump.lastCapture || Date.now() - pump.lastCapture >= interval) {
      pump.lastCapture = Date.now();
      const width = elements.preview.videoWidth || 1280;
      const height = elements.preview.videoHeight || 720;
      canvas.width = width;
      canvas.height = height;
      context.drawImage(elements.preview, 0, 0, width, height);
      state.sendingFrame = true;
      canvas.toBlob(async (blob) => {
        try {
          if (blob) {
            const base64 = await blobToBase64(blob);
            await fetch('/api/frame', {
              method: 'POST',
              headers: { 'content-type': 'application/json' },
              body: JSON.stringify({ deviceId: state.deviceId, jpegBase64: base64 }),
            });
            elements.previewDot.classList.add('live');
            elements.previewText.textContent = 'Streaming preview';
          }
        } catch {
          elements.previewText.textContent = 'Stream upload paused';
        } finally {
          state.sendingFrame = false;
        }
      }, 'image/jpeg', quality);
    }

    state.frameLoop = requestAnimationFrame(pump);
  };

  state.frameLoop = requestAnimationFrame(pump);
}

function blobToBase64(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = String(reader.result || '');
      resolve(result.split(',')[1] || '');
    };
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

function syncUiFromSettings() {
  elements.qualityReadout.textContent = `${elements.resolutionSelect.value} / ${elements.fpsSelect.value} fps`;
  elements.qualityLabel.textContent = elements.qualitySelect.value;
  elements.bitrateLabel.textContent = `${elements.bitrateSelect.value} Mbps`;
}

async function pushSettings() {
  state.settings = {
    deviceId: elements.cameraSelect.value,
    resolution: elements.resolutionSelect.value,
    fps: Number(elements.fpsSelect.value),
    quality: Number(elements.qualitySelect.value) / 100,
    bitrate: Number(elements.bitrateSelect.value),
    zoom: Number(elements.zoomControl.value),
    iso: Number(elements.isoControl.value),
    shutterSpeed: Number(elements.shutterControl.value),
    whiteBalance: elements.wbControl.value,
  };

  await api('/api/settings', {
    method: 'POST',
    body: JSON.stringify({ deviceId: state.deviceId, label: 'Android phone', settings: state.settings }),
  });
  elements.lastSync.textContent = formatAgo(Date.now());
}

async function setLive() {
  await api('/api/live', {
    method: 'POST',
    body: JSON.stringify({ deviceId: state.deviceId }),
  });
}

function applyLiveState(snapshot) {
  state.liveTargetId = snapshot.liveTargetId;
  state.active = state.deviceId === snapshot.liveTargetId;
  elements.liveDot.classList.toggle('live', state.active);
  elements.liveText.textContent = state.active ? 'live on output' : 'not live';
  elements.connectionDot.classList.add('live');
  elements.connectionDot.classList.remove('offline');
  elements.connectionText.textContent = 'connected';
}

async function startSyncLoop() {
  const eventSource = new EventSource('/events');
  eventSource.addEventListener('state', async (event) => {
    const snapshot = JSON.parse(event.data);
    applyLiveState(snapshot);
    const self = snapshot.devices.find((device) => device.id === state.deviceId);
    if (self) {
      state.settings = self.settings;
      elements.deviceName.textContent = self.label;
      elements.lastSync.textContent = formatAgo(self.lastSeen);
    }
  });
  eventSource.onerror = () => {
    elements.connectionText.textContent = 'reconnecting';
    elements.connectionDot.classList.remove('live');
    elements.connectionDot.classList.add('offline');
  };

  state.syncTimer = setInterval(() => {
    fetch('/api/ping', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ deviceId: state.deviceId }),
    }).catch(() => {});
  }, 5000);
}

async function init() {
  await registerDevice();
  await refreshDeviceList();
  await startStream();
  await startSyncLoop();
  syncUiFromSettings();
  elements.connectionDot.classList.add('live');
  elements.connectionDot.classList.remove('offline');
  elements.connectionText.textContent = 'connected';
  elements.liveText.textContent = 'awaiting live status';
}

elements.refreshDevices.addEventListener('click', async () => {
  await refreshDeviceList();
  await startStream();
});

elements.smartPick.addEventListener('click', async () => {
  const bestId = pickBestLens(state.devices.map((device) => ({ ...device })), 'balanced');
  if (bestId) {
    elements.cameraSelect.value = bestId;
    await startStream();
    await pushSettings();
  }
});

elements.setLive.addEventListener('click', setLive);
elements.cameraSelect.addEventListener('change', async () => {
  await startStream();
  await pushSettings();
});

[elements.resolutionSelect, elements.fpsSelect, elements.qualitySelect, elements.bitrateSelect, elements.isoControl, elements.shutterControl, elements.wbControl, elements.zoomControl].forEach((input) => {
  input.addEventListener('input', async () => {
    syncUiFromSettings();
    await applyVisibleConstraints();
    await pushSettings();
  });
});

init().catch((error) => {
  elements.previewDot.classList.remove('live');
  elements.previewDot.classList.add('offline');
  elements.previewText.textContent = `Startup failed: ${error.message}`;
  elements.connectionDot.classList.remove('live');
  elements.connectionDot.classList.add('offline');
  elements.connectionText.textContent = 'camera unavailable';
});