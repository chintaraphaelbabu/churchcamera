import { formatAgo, pickBestLens } from './shared.js';

const els = {
  streamView: document.getElementById('streamView'),
  fallbackView: document.getElementById('fallbackView'),
  deviceList: document.getElementById('deviceList'),
  remoteDeviceSelect: document.getElementById('remoteDeviceSelect'),
  remoteResolution: document.getElementById('remoteResolution'),
  remoteZoom: document.getElementById('remoteZoom'),
  remoteFps: document.getElementById('remoteFps'),
  pushSettings: document.getElementById('pushSettings'),
  chooseBest: document.getElementById('chooseBest'),
  reloadState: document.getElementById('reloadState'),
  markLive: document.getElementById('markLive'),
  streamStatus: document.getElementById('streamStatus'),
  streamHint: document.getElementById('streamHint'),
  deskDot: document.getElementById('deskDot'),
  deskText: document.getElementById('deskText'),
  deskLiveText: document.getElementById('deskLiveText'),
  deskLiveDot: document.getElementById('deskLiveDot'),
};

const state = {
  snapshot: { devices: [], liveTargetId: null },
  currentDeviceId: null,
  peerConnection: null,
};

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

function setOnline(online) {
  els.deskDot.classList.toggle('live', online);
  els.deskDot.classList.toggle('offline', !online);
  els.deskText.textContent = online ? 'connected' : 'offline';
}

function closeExistingConnection() {
  if (state.peerConnection) {
    try {
      state.peerConnection.close();
    } catch (e) {}
    state.peerConnection = null;
  }
  if (els.streamView.srcObject) {
    try {
      els.streamView.srcObject.getTracks().forEach((track) => track.stop());
    } catch (e) {}
    els.streamView.srcObject = null;
  }
  els.streamView.style.display = 'none';
  els.fallbackView.src = '';
  els.fallbackView.style.display = 'none';
}

async function updateCurrentSelection(deviceId) {
  if (state.currentDeviceId === deviceId) {
    return;
  }

  closeExistingConnection();
  state.currentDeviceId = deviceId;

  if (!deviceId) return;

  const device = state.snapshot.devices.find((d) => d.id === deviceId);
  if (!device) return;

  if (device.url) {
    console.log(`Connecting to direct WebRTC on phone: ${device.url}`);
    els.streamView.style.display = 'block';

    try {
      const pc = new RTCPeerConnection({
        iceServers: [],
      });
      state.peerConnection = pc;

      pc.addTransceiver('video', { direction: 'recvonly' });

      pc.ontrack = (event) => {
        console.log('Received WebRTC remote video stream track', event.streams);
        if (event.streams && event.streams[0]) {
          els.streamView.srcObject = event.streams[0];
        }
      };

      pc.oniceconnectionstatechange = () => {
        console.log('WebRTC ICE connection state:', pc.iceConnectionState);
      };

      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);

      if (pc.iceGatheringState !== 'complete') {
        await new Promise((resolve) => {
          function checkState() {
            if (pc.iceGatheringState === 'complete') {
              pc.removeEventListener('icegatheringstatechange', checkState);
              resolve();
            }
          }
          pc.addEventListener('icegatheringstatechange', checkState);
          setTimeout(resolve, 800);
        });
      }

      const localDesc = pc.localDescription;
      console.log(`Sending SDP Offer to phone at: ${device.url}/api/webrtc/offer`);
      const response = await fetch(`${device.url}/api/webrtc/offer`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sdp: localDesc.sdp, type: 'offer' }),
      });

      if (!response.ok) {
        throw new Error(`Phone WebRTC signaling POST failed: ${response.status}`);
      }

      const answerJson = await response.json();
      if (answerJson.ok && answerJson.sdp) {
        await pc.setRemoteDescription(
          new RTCSessionDescription({
            type: 'answer',
            sdp: answerJson.sdp,
          })
        );
        console.log('WebRTC low-latency streaming connected successfully.');
      } else {
        throw new Error(answerJson.error || 'Invalid signaling answer payload');
      }
    } catch (err) {
      console.warn('WebRTC negotiation failed, falling back to MJPEG.', err);
      closeExistingConnection();
      state.currentDeviceId = deviceId;
      els.fallbackView.style.display = 'block';
      els.fallbackView.src = `/stream/${deviceId}.mjpg`;
    }
  } else {
    console.log(`Device has no direct URL. Falling back to MJPEG: /stream/${deviceId}.mjpg`);
    els.fallbackView.style.display = 'block';
    els.fallbackView.src = `/stream/${deviceId}.mjpg`;
  }
}

function renderDevices() {
  els.deviceList.innerHTML = state.snapshot.devices.map((device) => {
    const active = device.id === state.snapshot.liveTargetId;
    const online = device.online;
    return `
      <section class="device-card ${active ? 'active' : ''}">
        <div class="status-grid small">
          <div><strong>${device.label || device.id}</strong></div>
          <div>${device.role}</div>
          <div>${online ? 'online' : 'offline'} · ${formatAgo(device.lastSeen)}</div>
          <div>${active ? 'live on output' : 'standby'}</div>
        </div>
        <div class="device-toolbar" style="margin-top:10px">
          <button data-select="${device.id}">Select</button>
          <button data-live="${device.id}">${active ? 'Active' : 'Go live'}</button>
        </div>
      </section>`;
  }).join('');

  els.remoteDeviceSelect.innerHTML = state.snapshot.devices.map((device) => `<option value="${device.id}">${device.label || device.id}</option>`).join('');
  if (!els.remoteDeviceSelect.value && state.snapshot.devices[0]) {
    els.remoteDeviceSelect.value = state.snapshot.devices[0].id;
  }

  const selected = state.snapshot.devices.find((device) => device.id === state.currentDeviceId) || state.snapshot.devices[0];
  updateCurrentSelection(selected?.id || null);
  els.deskLiveText.textContent = state.snapshot.liveTargetId ? `Live: ${state.snapshot.liveTargetId}` : 'No active source';
  els.deskLiveDot.classList.toggle('live', Boolean(state.snapshot.liveTargetId));
  els.streamStatus.textContent = state.snapshot.liveTargetId ? 'Streaming to OBS browser source' : 'Waiting for a live source';
}

async function pushSettings() {
  const deviceId = els.remoteDeviceSelect.value;
  const settings = {
    resolution: els.remoteResolution.value,
    fps: Number(els.remoteFps.value),
    zoom: Number(els.remoteZoom.value),
  };
  await api('/api/settings', {
    method: 'POST',
    body: JSON.stringify({ deviceId, settings }),
  });
}

async function chooseBestLens() {
  const bestId = pickBestLens(state.snapshot.devices.map((device) => ({ ...device })), 'balanced');
  if (bestId) {
    els.remoteDeviceSelect.value = bestId;
    updateCurrentSelection(bestId);
    await pushSettings();
  }
}

async function markLive() {
  const deviceId = els.remoteDeviceSelect.value;
  if (!deviceId) return;
  await api('/api/live', {
    method: 'POST',
    body: JSON.stringify({ deviceId }),
  });
}

async function refresh() {
  state.snapshot = await api('/api/state');
  renderDevices();
}

function startEvents() {
  const source = new EventSource('/events');
  source.addEventListener('state', (event) => {
    state.snapshot = JSON.parse(event.data);
    renderDevices();
    setOnline(true);
  });
  source.onerror = () => setOnline(false);
}

els.deviceList.addEventListener('click', async (event) => {
  const selectId = event.target?.dataset?.select;
  const liveId = event.target?.dataset?.live;
  if (selectId) {
    updateCurrentSelection(selectId);
    els.remoteDeviceSelect.value = selectId;
    await pushSettings();
  }
  if (liveId) {
    els.remoteDeviceSelect.value = liveId;
    await markLive();
  }
});

els.pushSettings.addEventListener('click', pushSettings);
els.chooseBest.addEventListener('click', chooseBestLens);
els.reloadState.addEventListener('click', refresh);
els.markLive.addEventListener('click', markLive);
els.remoteDeviceSelect.addEventListener('change', () => updateCurrentSelection(els.remoteDeviceSelect.value));

startEvents();
refresh().catch((error) => {
  els.streamStatus.textContent = error.message;
});