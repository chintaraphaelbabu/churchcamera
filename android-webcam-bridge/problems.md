# Known Issues & Technical Limitations

This document tracks current known issues, hardware limitations, and behavior quirks of the Android Webcam Bridge.

## 1. OBS Studio Mode Tally
- **Kickstart Required**: On some OBS versions, the tally light (Red/Yellow border) may not appear immediately after adding the browser source.
  - **Workaround**: Click the "Eye" icon in OBS to hide and then show the source once to initialize the OBS visibility API.
- **Occlusion**: If the camera source is completely covered by a full-screen image or another video layer, OBS may stop sending "Active" signals to save power, which could cause the tally to turn yellow or green even while live.