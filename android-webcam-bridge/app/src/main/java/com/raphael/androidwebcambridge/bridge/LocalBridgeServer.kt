package com.raphael.androidwebcambridge.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LocalBridgeServer(
    private val port: Int = 8787,
    private val onRemoteUpdate: (Map<String, String?>) -> Unit,
    private val onConnectionStatusChanged: (total: Int) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val latestFrame = AtomicReference(ByteArray(0))
    private val currentState = AtomicReference(BridgeState())
    private val activeClients = AtomicInteger(0)

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    fun updateState(state: BridgeState) {
        currentState.set(state)
    }

    fun submitFrame(frame: ByteArray) {
        latestFrame.set(frame)
    }

    fun start() {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    launch { handleClient(socket) }
                }
            } catch (error: Exception) {
                currentState.get()?.copy(errorMessage = error.message, statusMessage = "Server failed")?.let {
                    currentState.set(it)
                }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        scope.cancel()
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(BufferedInputStream(client.getInputStream()), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val requestParts = requestLine.split(Regex("\\s+"))
            if (requestParts.size < 2) return

            val pathWithQuery = requestParts[1]
            val uri = pathWithQuery.substringBefore("?")
            val query = parseQuery(pathWithQuery.substringAfter("?", ""))
            val output = BufferedOutputStream(client.getOutputStream())

            while (true) {
                val headerLine = reader.readLine() ?: break
                if (headerLine.isBlank()) break
            }

            when {
                uri == "/" -> writeText(output, htmlLandingPage(), "text/html; charset=utf-8")
                uri == dashboardPath() -> writeText(output, htmlDashboardPage(), "text/html; charset=utf-8")
                uri == "/obs-bridge" -> writeText(output, htmlObsBridgePage(), "text/html; charset=utf-8")
                uri == "/api/state" -> writeText(output, currentState.get().toJson().toString(), "application/json; charset=utf-8")
                uri == "/api/tally" -> {
                  scope.launch { onRemoteUpdate(query) } // answer immediately, update state in background
                    writeText(output, JSONObjectFactory.ok("tally updated"), "application/json; charset=utf-8")
                }
                uri == "/api/settings" -> {
                  scope.launch { onRemoteUpdate(query) }
                    writeText(output, JSONObjectFactory.ok("settings updated"), "application/json; charset=utf-8")
                }
                uri == "/stream.mjpg" || uri.startsWith("/stream") -> writeMjpeg(client, output)
                else -> writeText(output, "Not found", "text/plain; charset=utf-8", code = 404)
            }
        }
    }

    private suspend fun writeMjpeg(socket: Socket, output: BufferedOutputStream) {
        output.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Connection: keep-alive\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n"
                ).toByteArray(),
        )
        output.flush()

        onConnectionStatusChanged(activeClients.incrementAndGet())
        
        try {
            while (socket.isConnected && !socket.isClosed && scope.isActive) {
                val frame = latestFrame.get()
                if (frame.isNotEmpty()) {
                    output.write("--frame\r\n".toByteArray())
                    output.write("Content-Type: image/jpeg\r\n".toByteArray())
                    output.write("Content-Length: ${frame.size}\r\n\r\n".toByteArray())
                    output.write(frame)
                    output.write("\r\n".toByteArray())
                    output.flush()
                }
                delay(33L)
            }
        } catch (_: Exception) {
        } finally {
            onConnectionStatusChanged(activeClients.decrementAndGet())
        }
    }

    private fun writeText(
        output: BufferedOutputStream,
        body: String,
        contentType: String,
        code: Int = 200,
    ) {
        val statusText = when(code) {
            200 -> "OK"
            404 -> "Not Found"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 ")
            append(code)
            append(" ")
            append(statusText)
            append("\r\n")
            append("Content-Type: ")
            append(contentType)
            append("\r\n")
            append("Content-Length: ")
            append(body.toByteArray(StandardCharsets.UTF_8).size)
            append("\r\n")
            append("Connection: close\r\n\r\n")
            append(body)
        }
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun htmlObsBridgePage(): String = """
        <!doctype html>
        <html>
          <head>
            <title>OBS Tally Bridge</title>
            <style>
              body { margin: 0; background: #000; overflow: hidden; font-family: 'Segoe UI', sans-serif; color: #fff; }
              #tally-ui { position: absolute; top: 12px; left: 12px; display: flex; align-items: center; gap: 10px; z-index: 100; pointer-events: none; }
              #dot { width: 16px; height: 16px; border-radius: 50%; background: #444; border: 2px solid #fff; box-shadow: 0 0 10px rgba(0,0,0,0.8); }
              #status-text { font-size: 11px; font-weight: 900; text-transform: uppercase; text-shadow: 0 1px 4px #000; letter-spacing: 0.1em; }
              
              .program #dot { background: #ff4444; box-shadow: 0 0 20px #ff4444; }
              .preview #dot { background: #ffcc00; box-shadow: 0 0 20px #ffcc00; }
              .idle #dot { background: #4ade80; }
            </style>
          </head>
          <body id="body-node" class="idle">
            <div id="tally-ui">
                <div id="dot"></div>
                <div id="status-text">BRIDGE READY</div>
            </div>
            <img src="/stream.mjpg" style="width: 100vw; height: 100vh; object-fit: contain;" />
            <script>
              let _isVisible = false;
              let _isActive = false;
              let _lastSent = null;
              let _debounce = null;

              function applyUi(stateStr) {
                const node = document.getElementById('body-node');
                const txt = document.getElementById('status-text');
                if (stateStr === 'PROGRAM') {
                  node.className = 'program'; txt.textContent = 'LIVE (ON AIR)';
                } else if (stateStr === 'PREVIEW') {
                  node.className = 'preview'; txt.textContent = 'OBS PREVIEW';
                } else {
                  node.className = 'idle'; txt.textContent = 'REMOTE';
                }
              }

              async function report() {
                try {
                  const server = await fetch('/api/state').then(r => r.json()).catch(() => null);
                  const connected = server?.connectedClients || 0;

                  let state = 'IDLE';
                  if (_isActive || connected > 0) state = 'PROGRAM';
                  else if (_isVisible) state = 'PREVIEW';

                  // update UI immediately
                  applyUi(state);

                  // debounce network updates to avoid spamming the phone
                  if (_debounce) clearTimeout(_debounce);
                  _debounce = setTimeout(() => {
                    if (_lastSent !== state) {
                      fetch('/api/tally?tallyState=' + state + '&_t=' + Date.now()).catch(() => {});
                      _lastSent = state;
                    }
                    _debounce = null;
                  }, 300);
                } catch (e) {
                  // fallback to simple behavior
                  const fallback = _isActive ? 'PROGRAM' : (_isVisible ? 'PREVIEW' : 'IDLE');
                  applyUi(fallback);
                  if (_lastSent !== fallback) {
                    fetch('/api/tally?tallyState=' + fallback + '&_t=' + Date.now()).catch(() => {});
                    _lastSent = fallback;
                  }
                }
              }

              // OBS Studio visibility events
              window.addEventListener('obsSourceVisibleOn', () => { _isVisible = true; report(); });
              window.addEventListener('obsSourceVisibleOff', () => { _isVisible = false; report(); });
              window.addEventListener('obsSourceActiveOn', () => { _isActive = true; report(); });
              window.addEventListener('obsSourceActiveOff', () => { _isActive = false; report(); });
              
              if (window.obsstudio) {
                window.obsstudio.onActiveChange((a) => { _isActive = a; report(); });
                // obsstudio provides visibility too in some builds
                if (typeof window.obsstudio.getVisibility === 'function') {
                  _isVisible = window.obsstudio.getVisibility();
                }
              }

              // periodic check in case events are missed
              setInterval(report, 2500);
              report();
            </script>
          </body>
        </html>
    """.trimIndent()

    private fun htmlLandingPage(): String = """
        <!doctype html>
        <html>
          <head><meta name="viewport" content="width=device-width, initial-scale=1"><title>Android Webcam Bridge</title></head>
          <body style="font-family:sans-serif;padding:24px;background:#081018;color:#eff6ff;">
            <h1>Android Webcam Bridge</h1>
            <p>Open <a href="/dashboard" style="color:#4ade80">/dashboard</a> for controls.</p>
            <p>OBS browser source: <code>/stream.mjpg</code></p>
            <p>API: <code>/api/state</code></p>
          </body>
        </html>
    """.trimIndent()

    private fun htmlDashboardPage(): String = """
        <!doctype html>
        <html>
          <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1" />
            <title>Webcam Bridge Dashboard</title>
            <style>
              :root { --bg: #0b0f14; --card: #151a21; --accent: #4ade80; --text: #eff6ff; --sub: #94a3b8; --border: rgba(255,255,255,0.1); }
              body { margin: 0; font-family: -apple-system, system-ui, sans-serif; background: var(--bg); color: var(--text); -webkit-font-smoothing: antialiased; overflow-x: hidden; }
              
              .dashboard-layout { display: grid; grid-template-columns: 320px 1fr 320px; height: 100vh; gap: 0; }
              
              .sidebar { background: var(--card); border-right: 1px solid var(--border); padding: 24px; overflow-y: auto; display: flex; flex-direction: column; gap: 24px; }
              .sidebar-right { border-right: 0; border-left: 1px solid var(--border); }
              
              .main-view { padding: 40px; display: flex; flex-direction: column; align-items: center; justify-content: center; background: var(--bg); position: relative; }
              
              header { position: absolute; top: 24px; left: 40px; right: 40px; display: flex; justify-content: space-between; align-items: flex-start; pointer-events: none; z-index: 20; }
              h1 { margin: 0; font-size: 20px; font-weight: 600; letter-spacing: -0.02em; pointer-events: auto; }
              .status-line { color: var(--sub); font-size: 13px; margin-top: 4px; pointer-events: auto; }
              .live-badge { background: #ef4444; color: white; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 700; text-transform: uppercase; margin-right: 8px; display: none; }
              
              .preview-container { width: 100%; max-width: 1200px; aspect-ratio: 16/9; background: #000; border-radius: 12px; overflow: hidden; border: 1px solid var(--border); position: relative; display: flex; align-items: center; justify-content: center; box-shadow: 0 20px 50px rgba(0,0,0,0.5); }
              .preview-img { width: 100%; height: 100%; object-fit: fill; pointer-events: none; }
              
              h2 { margin: 0 0 16px; font-size: 12px; font-weight: 700; color: var(--sub); text-transform: uppercase; letter-spacing: 0.1em; }
              .control-group { margin-bottom: 20px; }
              .control-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
              label { font-size: 13px; font-weight: 500; color: var(--sub); }
              .value { font-family: monospace; font-size: 12px; color: var(--accent); }
              
              input[type=range] { -webkit-appearance: none; width: 100%; background: transparent; margin: 10px 0; }
              input[type=range]::-webkit-slider-runnable-track { width: 100%; height: 4px; cursor: pointer; background: rgba(255,255,255,0.1); border-radius: 2px; }
              input[type=range]::-webkit-slider-thumb { height: 16px; width: 16px; border-radius: 50%; background: #fff; cursor: pointer; -webkit-appearance: none; margin-top: -6px; }
              
              .btn-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
              button { background: rgba(255,255,255,0.05); border: 1px solid var(--border); color: var(--text); padding: 8px; border-radius: 6px; cursor: pointer; font-size: 12px; font-weight: 500; transition: all 0.2s; }
              button.active { background: var(--accent); color: #000; border-color: var(--accent); }
              select { width: 100%; background: rgba(255,255,255,0.05); border: 1px solid var(--border); color: var(--text); padding: 8px; border-radius: 6px; font-size: 13px; }
              
              .framing-area { width: 100%; aspect-ratio: 16/9; background: #050505; border-radius: 8px; position: relative; cursor: crosshair; overflow: hidden; border: 1px solid var(--border); }
              .framing-target { position: absolute; border: 1px solid var(--accent); background: rgba(74, 222, 128, 0.05); pointer-events: none; transform: translate(-50%, -50%); transition: all 0.1s; }
              .zoom-v-wrap { height: 140px; display: flex; align-items: center; justify-content: center; }
              .zoom-v-slider { -webkit-appearance: slider-vertical; width: 8px; height: 100%; }
              
              .face-overlay-container { position: absolute; top: 0; left: 0; width: 100%; height: 100%; pointer-events: none; display: flex; align-items: center; justify-content: center; }
              .face-overlay-inner { position: relative; }
              .face-box { position: absolute; border: 1px solid var(--accent); background: rgba(74, 222, 128, 0.05); border-radius: 4px; cursor: pointer; pointer-events: auto; transition: all 0.15s; }
              .face-box.selected { border-color: #fff; border-width: 2px; box-shadow: 0 0 15px rgba(255,255,255,0.5); }

              @media (max-width: 1100px) {
                .dashboard-layout { grid-template-columns: 1fr; height: auto; }
                .sidebar { border: 0; border-bottom: 1px solid var(--border); }
                .main-view { padding: 20px; }
              }
            </style>
          </head>
          <body>
            <div class="dashboard-layout">
              <!-- Left Sidebar: Optics & Framing -->
              <aside class="sidebar">
                <section>
                  <h2>Camera Selection</h2>
                  <div class="btn-grid">
                    <button id="btn-BACK" data-lens="BACK">Rear</button>
                    <button id="btn-FRONT" data-lens="FRONT">Front</button>
                  </div>
                </section>

                <section>
                  <div class="control-header">
                    <h2>Framing</h2>
                    <button id="btn-FOLLOW" style="padding:2px 8px;font-size:10px">Follow Faces</button>
                  </div>
                  <div style="display:flex; gap:12px">
                    <div class="framing-area" id="framingArea" style="flex:1">
                      <div class="framing-target" id="framingTarget"></div>
                    </div>
                    <div class="zoom-v-wrap">
                      <input id="zoom" type="range" min="1" max="10" step="0.1" value="1" class="zoom-v-slider" />
                    </div>
                  </div>
                  <div class="control-header" style="margin-top:8px">
                    <label>Zoom Level</label>
                    <span class="value" id="zoomVal">1.0x</span>
                  </div>
                </section>

                <section>
                  <div class="control-header">
                    <h2>Focus</h2>
                    <button id="btn-AF" style="padding:2px 8px;font-size:10px">Auto</button>
                  </div>
                  <span class="value" id="focusVal" style="display:block; margin-bottom:8px">Infinity</span>
                  <input id="f-slider" type="range" min="0" max="10" step="0.1" value="0" />
                </section>
              </aside>

              <!-- Center: Live Preview -->
              <main class="main-view">
                <header>
                  <div>
                    <h1>Android Webcam Bridge</h1>
                    <div class="status-line">
                      <span id="live" class="live-badge">Live</span>
                      <span id="status">Connecting...</span>
                    </div>
                  </div>
                </header>

                <div class="preview-container" id="previewContainer">
                  <img src="/stream.mjpg" class="preview-img" id="streamImg" alt="Live Preview" />
                  <div class="face-overlay-container">
                    <div id="faceOverlay" class="face-overlay-inner"></div>
                  </div>
                </div>
              </main>

              <!-- Right Sidebar: Image Adjustments -->
              <aside class="sidebar sidebar-right">
                <section>
                  <h2>Exposure</h2>
                  <div class="control-group">
                    <div class="control-header">
                      <label>ISO</label>
                      <button id="btn-ISO-A" style="padding:2px 8px;font-size:10px">Auto</button>
                    </div>
                    <div class="control-header">
                      <span class="value" id="isoVal">Auto</span>
                    </div>
                    <input id="iso" type="range" min="100" max="3200" step="50" value="200" />
                  </div>

                  <div class="control-group">
                    <div class="control-header">
                      <label>Shutter (ms)</label>
                      <button id="btn-SH-A" style="padding:2px 8px;font-size:10px">Auto</button>
                    </div>
                    <div class="control-header">
                      <span class="value" id="shutterVal">Auto</span>
                    </div>
                    <input id="shutter" type="range" min="1" max="250" step="1" value="60" />
                  </div>

                  <div class="control-group">
                    <div class="control-header">
                      <label>Compensation</label>
                      <span class="value" id="expVal">0</span>
                    </div>
                    <input id="exposure" type="range" min="-6" max="6" step="1" value="0" />
                  </div>
                </section>

                <section>
                  <h2>Output Settings</h2>
                  <div class="control-group">
                    <label>Resolution</label>
                    <select id="resolution" style="margin-top:8px">
                      <option value="P720">720p HD</option>
                      <option value="P1080">1080p Full HD</option>
                      <option value="P1440">1440p QHD</option>
                      <option value="P4K">4K Ultra HD</option>
                    </select>
                  </div>
                  <div class="control-group">
                    <label>Frame Rate</label>
                    <select id="fps" style="margin-top:8px">
                      <option value="24">24 FPS</option>
                      <option value="30">30 FPS</option>
                      <option value="60">60 FPS</option>
                    </select>
                  </div>
                </section>
                  <section style="margin-top:16px">
                    <h2>Relay</h2>
                    <div class="control-group">
                      <label>Laptop / Relay IP</label>
                      <div style="font-size:11px; color:var(--sub); margin-top:4px">Enter the laptop address running the relay, for example <code>http://192.168.1.100:3000</code></div>
                      <div style="display:flex;gap:8px;margin-top:8px">
                        <input id="relayHost" placeholder="http://192.168.1.100:3000" style="flex:1;padding:8px;border-radius:6px;border:1px solid #222;background:transparent;color:var(--text)" />
                        <button id="btn-register-relay">Save</button>
                      </div>
                    </div>
                    <div class="control-group">
                      <label>Source Name</label>
                      <div style="font-size:11px; color:var(--sub); margin-top:4px">This is the OBS Browser Source name for this phone, for example <code>phone1</code></div>
                      <div style="display:flex;gap:8px;margin-top:8px">
                        <input id="relaySourceName" placeholder="phone1" style="flex:1;padding:8px;border-radius:6px;border:1px solid #222;background:transparent;color:var(--text)" />
                        <button id="btn-register-relay-source">Save</button>
                      </div>
                    </div>
                  </section>
                
                <div style="margin-top:auto; font-size:11px; color:var(--sub); opacity:0.5">
                  Stream: <code>/stream.mjpg</code>
                </div>
              </aside>
            </div>
            </div>

            <script>
              const s = async () => (await fetch('/api/state')).json();
              const push = async (p) => { await fetch('/api/settings?' + new URLSearchParams(p)); };
              let latestSettings = null;
              
              let pendingParams = {};
              let throttleTimer = null;
              let lastLocalInteraction = 0;
              let lastPushAt = 0;
              
              const throttledPush = (params) => {
                pendingParams = { ...pendingParams, ...params };
                lastLocalInteraction = Date.now();
                
                const now = Date.now();
                const timeSinceLast = now - lastPushAt;
                
                if (throttleTimer) return;

                if (timeSinceLast > 30) {
                  push(pendingParams);
                  pendingParams = {};
                  lastPushAt = now;
                } else {
                  throttleTimer = setTimeout(() => {
                    push(pendingParams);
                    pendingParams = {};
                    throttleTimer = null;
                    lastPushAt = Date.now();
                  }, 30 - timeSinceLast);
                }
              };

              const updateUI = async () => {
                const now = Date.now();
                const state = await s();
                const set = state.settings;
                latestSettings = set;
                
                document.getElementById('status').textContent = state.statusMessage;
                document.getElementById('live').style.display = state.streaming ? 'inline' : 'none';
                
                const isInteracting = document.activeElement?.type === 'range' || (now - lastLocalInteraction) < 3000;

                if (!isInteracting) {
                  document.getElementById('zoom').value = set.zoomRatio;
                  document.getElementById('zoomVal').textContent = set.zoomRatio.toFixed(1) + 'x';
                  
                  const target = document.getElementById('framingTarget');
                  const z = set.zoomRatio;
                  target.style.width = (100 / z) + '%';
                  target.style.height = (100 / z) + '%';
                  target.style.left = (50 + (set.panX * 50 * (1 - 1/z))) + '%';
                  target.style.top = (50 + (set.panY * 50 * (1 - 1/z))) + '%';

                  document.getElementById('btn-FOLLOW').className = set.faceFollowEnabled ? 'active' : '';
                  
                  const overlay = document.getElementById('faceOverlay');
                  const streamImg = document.getElementById('streamImg');
                  
                  if (streamImg.complete) {
                    overlay.style.width = streamImg.clientWidth + 'px';
                    overlay.style.height = streamImg.clientHeight + 'px';
                  }

                  overlay.innerHTML = '';
                  state.detectedFaces.forEach(face => {
                    const box = document.createElement('div');
                    box.className = 'face-box' + (state.selectedFaceId === face.id ? ' selected' : '');
                    
                    const z = set.zoomRatio;
                    const px = set.panX;
                    const py = set.panY;
                    
                    const screenX = (face.x - (0.5 + px * 0.5 * (1 - 1/z))) * z;
                    const screenY = (face.y - (0.5 + py * 0.5 * (1 - 1/z))) * z;
                    const screenW = face.width * z;
                    const screenH = face.height * z;

                    if (screenX > -0.1 && screenX < 1.1 && screenY > -0.1 && screenY < 1.1) {
                        box.style.left = ((screenX - screenW/2) * 100) + '%';
                        box.style.top = ((screenY - screenH/2) * 100) + '%';
                        box.style.width = (screenW * 100) + '%';
                        box.style.height = (screenH * 100) + '%';
                        box.onclick = (e) => {
                            e.stopPropagation();
                            push({ selectedFaceId: face.id, faceFollowEnabled: "true" }).then(updateUI);
                        };
                        overlay.appendChild(box);
                    }
                  });

                  document.getElementById('f-slider').value = set.focusDistanceDiopters;
                  const dist = set.focusDistanceDiopters > 0 ? (1/set.focusDistanceDiopters).toFixed(2) + 'm' : 'Infinity';
                  document.getElementById('focusVal').textContent = set.focusAuto ? 'AF Active' : dist;
                  document.getElementById('btn-AF').className = set.focusAuto ? 'active' : '';
                  
                  document.getElementById('exposure').value = set.exposureCompensation;
                  const isManualExp = set.iso > 0 && set.shutterSpeedMs > 0;
                  document.getElementById('expVal').textContent = isManualExp ? 'Manual Lock' : (set.exposureCompensation > 0 ? '+' + set.exposureCompensation : set.exposureCompensation);
                  document.getElementById('exposure').style.opacity = isManualExp ? "0.3" : "1.0";
                  document.getElementById('exposure').disabled = isManualExp;

                  document.getElementById('iso').value = set.iso || 200;
                  document.getElementById('isoVal').textContent = set.iso > 0 ? set.iso : 'Auto';
                  document.getElementById('btn-ISO-A').className = set.iso === 0 ? 'active' : '';
                  
                  document.getElementById('shutter').value = set.shutterSpeedMs || 60;
                  document.getElementById('shutterVal').textContent = set.shutterSpeedMs > 0 ? set.shutterSpeedMs + 'ms' : 'Auto';
                  document.getElementById('btn-SH-A').className = set.shutterSpeedMs === 0 ? 'active' : '';
                }

                document.getElementById('resolution').value = set.resolutionPreset;
                document.getElementById('fps').value = set.frameRate;

                document.getElementById('btn-BACK').className = set.lensFacing === 'BACK' ? 'active' : '';
                document.getElementById('btn-FRONT').className = set.lensFacing === 'FRONT' ? 'active' : '';
              };

              document.querySelectorAll('button[data-lens]').forEach(b => {
                const applyLens = (event) => {
                  event.preventDefault();
                  event.stopPropagation();
                  b.classList.add('active');
                  push({ lensFacing: b.dataset.lens, panX:0, panY:0, zoomRatio:1 }).then(updateUI);
                };
                b.onpointerdown = applyLens;
              });

              document.getElementById('btn-AF').onpointerdown = (event) => {
                event.preventDefault();
                event.stopPropagation();
                const isAuto = latestSettings?.focusAuto ?? false;
                push({ focusAuto: isAuto ? "false" : "true" }).then(updateUI);
              };

              document.getElementById('btn-FOLLOW').onpointerdown = (event) => {
                event.preventDefault();
                event.stopPropagation();
                const active = latestSettings?.faceFollowEnabled ?? false;
                push({ faceFollowEnabled: !active }).then(updateUI);
              };

              document.getElementById('btn-ISO-A').onpointerdown = (event) => {
                event.preventDefault();
                event.stopPropagation();
                const isAuto = (latestSettings?.iso ?? 0) === 0;
                push({ iso: isAuto ? "200" : "0" }).then(updateUI);
              };

              document.getElementById('btn-SH-A').onpointerdown = (event) => {
                event.preventDefault();
                event.stopPropagation();
                const isAuto = (latestSettings?.shutterSpeedMs ?? 0) === 0;
                push({ shutterSpeedMs: isAuto ? "60" : "0" }).then(updateUI);
              };

              zoom.oninput = () => { 
                const z = Number(zoom.value);
                zoomVal.textContent = z.toFixed(1) + 'x';
                const target = document.getElementById('framingTarget');
                target.style.width = (100 / z) + '%';
                target.style.height = (100 / z) + '%';
                throttledPush({ zoomRatio: zoom.value });
              };
              
              const framingArea = document.getElementById('framingArea');
              const handleFraming = (e) => {
                e.preventDefault();
                const rect = framingArea.getBoundingClientRect();
                const clientX = e.touches ? e.touches[0].clientX : e.clientX;
                const clientY = e.touches ? e.touches[0].clientY : e.clientY;
                
                const x = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
                const y = Math.max(0, Math.min(1, (clientY - rect.top) / rect.height));
                
                let z = Number(zoom.value);
                if (z <= 1.05) {
                    z = 1.2; 
                    zoom.value = z;
                    zoomVal.textContent = "1.2x";
                }
                
                const panX = ((x - 0.5) * 2) / (1 - 1/z);
                const panY = ((y - 0.5) * 2) / (1 - 1/z);
                
                const target = document.getElementById('framingTarget');
                target.style.left = (x * 100) + '%';
                target.style.top = (y * 100) + '%';

                throttledPush({ panX: panX.toFixed(3), panY: panY.toFixed(3), zoomRatio: z });
              };
              
              framingArea.addEventListener('mousedown', (e) => { handleFraming(e); window.addEventListener('mousemove', handleFraming); });
              window.addEventListener('mouseup', () => { window.removeEventListener('mousemove', handleFraming); });
              
              framingArea.addEventListener('touchstart', (e) => { handleFraming(e); framingArea.addEventListener('touchmove', handleFraming); }, {passive: false});
              framingArea.addEventListener('touchend', () => { framingArea.removeEventListener('touchmove', handleFraming); });

              const fSlider = document.getElementById('f-slider');
              fSlider.oninput = () => { 
                const d = parseFloat(fSlider.value);
                const val = d > 0 ? (1/d).toFixed(2) + 'm' : 'Infinity';
                document.getElementById('focusVal').textContent = val;
                push({ focusDistanceDiopters: fSlider.value, focusAuto: "false" });
                lastLocalInteraction = Date.now();
              };

              exposure.oninput = () => { 
                const val = exposure.value > 0 ? '+' + exposure.value : exposure.value;
                expVal.textContent = val;
                throttledPush({ exposureCompensation: exposure.value });
              };

              iso.oninput = () => { 
                isoVal.textContent = iso.value;
                throttledPush({ iso: iso.value });
              };

              shutter.oninput = () => { 
                shutterVal.textContent = shutter.value + 'ms';
                throttledPush({ shutterSpeedMs: shutter.value });
              };

              resolution.onchange = () => push({ resolutionPreset: resolution.value });
              fps.onchange = () => push({ frameRate: fps.value });

              document.getElementById('btn-register-relay').onclick = () => {
                const val = document.getElementById('relayHost').value;
                if (!val) return alert('Enter relay host');
                push({ relayHost: val }).then(() => alert('Relay host saved'));
              };

              document.getElementById('btn-register-relay-source').onclick = () => {
                const val = document.getElementById('relaySourceName').value;
                if (!val) return alert('Enter source name');
                push({ relaySourceName: val }).then(() => alert('Source name saved'));
              };

              setInterval(updateUI, 4000);
              updateUI();
            </script>
          </body>
        </html>
    """.trimIndent()

    private fun parseQuery(query: String): Map<String, String?> {
        if (query.isBlank()) return emptyMap()
        return query.split("&")
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index < 0) return@mapNotNull null
                val key = URLDecoder.decode(part.substring(0, index), StandardCharsets.UTF_8.name())
                val value = URLDecoder.decode(part.substring(index + 1), StandardCharsets.UTF_8.name())
                key to value
            }
            .toMap()
    }

    private object JSONObjectFactory {
        fun ok(message: String) = org.json.JSONObject()
            .put("ok", true)
            .put("message", message)
            .toString()
    }

    private fun dashboardPath(): String = "/dashboard"

    companion object {
    }
}
