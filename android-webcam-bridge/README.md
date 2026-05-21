# Android Webcam Bridge (Pro)

This project turns your Android phone into a high-end, remote-controlled webcam for OBS and live streaming. It provides professional-grade features typically found in dedicated camera hardware, accessible via a browser-based dashboard.

## Key Features

### 1. Professional Remote Dashboard
- **Live Preview**: Real-time video monitor directly in your browser.
- **Zero-Lag Controls**: Every setting (ISO, Shutter, Focus, Zoom) updates the phone instantly.
- **Stable Sync**: Dashboard sliders won't "jump back" while you are interacting with them.

### 2. "Camo-Style" Digital Framing
- **Lossless Zoom**: High-resolution internal capture allows for sharp, pixel-perfect zooming up to 2x.
- **Digital Pan & Tilt**: Click and drag on the preview window to move your framing to any part of the sensor—perfect for off-center phone placements.
- **Software Processing**: Panning and zooming happen in software, bypassing restrictive system-level hardware limits.

### 3. AI Face Tracking (Follow Faces)
- **Real-Time Detection**: Uses Google ML Kit to identify faces at 30 FPS.
- **Dynamic Lock**: Click on a person's face in the dashboard preview to make the camera follow them automatically.
- **Smooth Framing**: The camera intelligently pans and tilts to keep the selected subject perfectly centered.

### 4. Full Manual Hardware Control
- **Manual Focus**: Set physical lens distance in meters (e.g., 0.50m) or lock to Infinity. Includes a "Forced Kill" toggle to prevent Auto Focus overrides.
- **Manual Exposure**: Dedicated ISO and Shutter Speed (ms) controls with real-time "Auto" toggles.
- **Exposure Compensation**: Fine-tune brightness (+/- 6 EV) when in Auto mode.

### 5. High-Performance Streaming
- **MJPEG Output**: Optimized specifically for OBS "Browser Source" with minimal latency.
- **Bulk Memory Processing**: High-speed pixel processing for buttery smooth 30-60 FPS performance.

## Architecture

- **ViewModel**: State machine that coordinates camera hardware, AI detection, and network commands.
- **Camera Controller**: High-speed video pipeline that performs software cropping and AI face detection.
- **Local Server**: Lightweight HTTP server that delivers the dashboard and handles the MJPEG stream.

## How to use

1.  Open the app on your Android phone and grant camera permissions.
2.  Open the **Settings** panel to find your local IP address.
3.  On your laptop, go to `http://<phone-ip>:8787/dashboard`.
4.  In OBS, add a **Browser Source** pointing to `http://<phone-ip>:8787/stream.mjpg`.

## Device Support
- **Resolution**: Supports up to 4K internal capture for high-quality oversampling.
- **Hardware**: Manual focus and exposure support depends on your specific device's camera module.
- **Multi-Lens**: Switch between Front and Rear cameras directly from the dashboard.
