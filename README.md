# StreamCam Pro (Android Webcam Bridge)

**StreamCam Pro** is a high-performance Android application that turns your smartphone into a professional-grade OBS camera source with integrated Tally support and remote hardware control.

## 🚀 Key Features

### 🎥 Professional Camera Control
- **Manual Exposure**: Precise control over ISO (100–6400) and Shutter Speed (Auto to 1/500s).
- **Advanced Focusing**: Toggle between Continuous Auto Focus and Manual Focus with an infinity lock.
- **Dual-Layer Zoom**: 
    - **Physical Zoom**: Controls the phone's actual camera lenses for maximum quality.
    *   **Digital Crop**: Independent digital zoom available via the web dashboard for fine reframing.
- **Switchable Lenses**: Toggle between Front and Back cameras seamlessly.
- **Resolution Presets**: Support for 720p, 1080p, 1440p, and 4K output.

### 🕹️ Tactile Hardware Interaction
- **Volume Key Control**: Use physical volume buttons to pull focus or zoom smoothly.
    - Tap the **Focus Rail** (left) to adjust focus distance via volume keys.
    - Tap the **Zoom Rail** (right) to adjust hardware zoom via volume keys.
    - Long-press **ISO** or **Shutter** tiles to adjust exposure via volume keys.
- **Custom Sensitivity**: Swipe from the **right edge** of the screen to open the **Velocity Settings** drawer. Customize exactly how fast focus and zoom move when using physical buttons. These settings are saved persistently.

### 🔴 OBS Integration & Tally
- **Live Tally Support**: Real-time on-screen tally borders:
    - **Red Border**: Program (Live on air)
    - **Orange Border**: Preview (Selected in OBS)
    - **Green Border**: Active/Connected
- **MJPEG Streaming**: Low-latency stream compatible with OBS Browser Source.
- **Remote Dashboard**: A built-in web server allows you to control the camera entirely from your laptop's browser.

## 🛠️ Setup Instructions

### 1. Android App Setup
- Build the project using Android Studio or download the latest APK.
- Ensure your phone and laptop are on the same Wi-Fi network.
- Note the IP address displayed in the app's Info Overlay (accessible via the Gear icon).

### 2. OBS & Relay Setup
StreamCam Pro works best with the `obs-relay` (Node.js component included in the root of this repo).
1. Enable **obs-websocket v5** in OBS (Port 4455).
2. Start the relay:
   ```powershell
   cd obs-relay
   npm install
   npm start
   ```
3. Open the relay dashboard at `http://localhost:3000`.
4. In the Android App, enter your laptop's IP in the Relay Host field.

### 3. Adding to OBS
- Create a **Browser Source** in OBS.
- **URL**: `http://<YOUR_PHONE_IP>:8787/stream.mjpg`
- **Width/Height**: Match your app's resolution preset (e.g., 1920x1080).
- For Tally support, use the bridge URL: `http://<YOUR_PHONE_IP>:8787/obs-bridge`

## ⌨️ Shortcuts & Gestures
| Action | Gesture |
| :--- | :--- |
| **Control Selection** | Tap the tile in the bottom strip |
| **Physical Button Mode** | Tap Focus or Zoom rail |
| **Exposure Button Mode** | Long-press ISO or Shutter tile |
| **Velocity Settings** | Swipe from right-most edge |
| **Release Buttons** | Tap middle of the camera preview |
| **Switch Camera** | Tap the lens icon (top-right) |
| **Connection Info** | Tap the gear icon (top-right) |

## 🏗️ Repository Layout
- `android-webcam-bridge/` — The Android Studio project (StreamCam Pro).
- `obs-relay/` — Node.js relay for OBS WebSocket integration.
- `app/src/main/` — Primary application logic, including the MJPEG server and Camera2 controller.

---
*Developed for high-quality, low-latency mobile broadcasting.*
