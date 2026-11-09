# CamMonitor

> **Languages:** [English](README.md) | [繁體中文](README_TW.md) | [日本語](README_JA.md)  
> **Story:** [Why I Created This Project: A 40-Year Developer's Reflections on Building with AI](WhyCreateThisProject.md)

![CamMonitor Promo Banner](cammonitor_promo.jpg)

CamMonitor turns your spare Android phone into a private, zero-account home security camera. Connect two Android devices directly using peer-to-peer (P2P) encryption over Wi-Fi or cellular networks without cloud subscriptions, login accounts, or third-party servers storing your video.

---

## Key Features

- **Zero-Account Setup:** No signups, passwords, or cloud accounts. Pairing is completed instantly via an encrypted one-time QR code.
- **Strict End-to-End Security:** P2P connection encrypted with ECDSA device keys and AES-256-GCM. Only paired devices can access your camera feed.
- **Dual Device Roles:**
  - **Camera Mode:** Runs on the phone used as the camera (captures 720p HD video and audio).
  - **Viewer Mode:** Runs on your daily phone to monitor the live feed.
- **Multi-Device Support:**
  - 1 Camera can stream to up to **2 viewers** simultaneously.
  - 1 Viewer can pair with up to **8 different cameras** (e.g., Living Room, Front Door, Baby Nursery).
- **Smart Battery & Screen Protection:**
  - **OLED Black Screen & Touch Guard:** Screen turns completely black after 60 seconds of inactivity (or tap the **Black Screen** button) to prevent OLED burn-in and save power. Releases keep-screen-on while locked, allowing display hardware to sleep and SoC to enter deep suspend. Touch input is locked to prevent stray taps; swipe to unlock.
  - **Auto-Standby Mode & Dynamic Wi-Fi:** If no viewers are watching for 15 seconds, the camera sensor shuts down and high-performance Wi-Fi lock is released to preserve battery. Both automatically wake when a viewer connects.
  - **Hardware Longevity & Anti-Overheating:** Deliberately avoids 24/7 continuous video recording and persistent AI object detection to prevent phone overheating, battery swelling, and camera sensor wear. (Users can use AI tools to add these functions if specifically needed).
- **Remote Controls from Viewer:**
  - **3-Way Night Mode:** Switch between `Auto` (activates via ambient light sensor), `Night` (forced low-light enhancement), and `Normal`.
  - **Remote Flashlight:** Toggle the camera phone's LED torch on and off remotely.
  - **Push-to-Talk (PTT) Intercom:** Hold the microphone button on the viewer to speak through the camera phone.
  - **Orientation & Fit Controls:** Rotate incoming video in 90° increments ($0^\circ \to 90^\circ \to 180^\circ \to 270^\circ$) and toggle between Aspect Fit (full uncropped view) and Aspect Fill.
  - **Battery Monitoring:** View real-time battery percentages and charging statuses for both the camera and viewer devices.
- **Local Diagnostics:** Built-in rolling log viewer with instant share/export for troubleshooting.
- **Multi-Language:** English, Traditional Chinese (繁體中文), and Japanese (日本語).

---

## Requirements

- **Operating System:** Android 9.0 (Pie / API 28) or higher.
- **Permissions:** Camera, Microphone, and Notifications (Android 13+).
- **Network:** Both phones must have an active Wi-Fi or mobile data connection. Works on local Wi-Fi (LAN) and over the Internet (WAN).

---

## Getting Started

### 1. One-Time Pairing

Before first use, pair your two devices:

1. **On the Camera Phone:**
   - Open CamMonitor.
   - Tap **Start Camera Mode** (or tap **Pair Viewer** if prompted).
   - A pairing QR code will appear on screen.
2. **On the Viewer Phone:**
   - Open CamMonitor.
   - Tap **Start Viewer Mode** (or tap **Pair Camera**).
   - Point the camera scanner at the QR code displayed on the camera phone.
3. Once scanned, both phones will confirm the pairing automatically.

*(You only need to pair once. Paired devices are remembered permanently until manually removed.)*

---

### 2. Using Camera Mode (The Camera Phone)

1. Open CamMonitor and tap **Start Camera Mode**.
2. Mount or position your phone facing the area you want to monitor.
3. **Screen Behavior:**
   - **HUD Overlay:** The top bar shows streaming resolution/FPS (`720p 15fps`), active viewer count (`0/2` in portrait, `0/2 Viewers` in landscape), Night mode status, and flashlight status.
   - **Screen Behavior & OLED Black Screen:** While previewing, the display is kept awake. After 60 seconds without touch (or by tapping the **Black Screen** button at the bottom left), the screen switches to black screen mode and releases keep-screen-on, allowing the panel to sleep and the SoC to enter deep suspend. **Swipe to unlock**; the system Back key is ignored while locked.
   - **Camera Standby & Dynamic Wi-Fi:** If no viewer connects within 15 seconds, the camera enters standby mode and releases high-performance Wi-Fi lock to save battery. It will wake up automatically as soon as a paired viewer connects.
4. **To Exit:** Tap **Exit Camera** (or press the Back button and confirm).

---

### 3. Using Viewer Mode (Your Everyday Phone)

1. Open CamMonitor and tap **Start Viewer Mode**.
   - If you have multiple cameras paired, a dialog will appear allowing you to select which camera to watch.
2. **Live Monitoring Controls:**
   - **Top Bar:**
     - Camera device name and live status badge.
     - Dual battery monitor: displays camera battery level (e.g., `Cam 85%⚡`) and viewer battery level (`Viewer 90%`).
     - **Night Mode Button:** Tap to cycle between `Auto`, `Night`, and `Normal` modes.
     - **Torch Button:** Tap to turn the camera phone's flashlight on or off.
   - **Bottom Bar:**
     - **Rotate (90°):** Rotates the video stream 90 degrees clockwise per tap to adjust for camera mounting angles.
     - **Aspect Ratio:** Toggle between full view (Aspect Fit) and full screen (Aspect Fill). You can also double-tap the video to toggle.
     - **Push-to-Talk (PTT):** Press and hold **Hold to Talk** to broadcast your voice to the camera phone. Release to stop speaking.
     - **Audio Mute/Unmute:** Toggle camera audio listening on or off.
3. **To Exit:** Tap the Back arrow at the top-left or use the system back gesture.

---

## Managing Paired Devices

- Tap the **Devices** icon (`Icons.Default.Devices`) on the top-right of the Home Screen to open **Paired Devices**.
- View all paired cameras and viewers along with their pairing dates and IDs.
- Tap the red trash icon next to any device to unpair and revoke its access immediately.

---

## Language & System Logs

- **Change Language:** Tap the **Globe/Language** icon on the top-right of the Home screen to select between *System Default*, *English*, *繁體中文*, or *日本語*.
- **System Logs:** Tap the **Document** icon on the top-right of the Home screen to view the connection and signaling diagnostic logs. Tap the Share icon to export the log file.

---

## Design Philosophy: Hardware Longevity & Heat Protection

To prevent spare phones from overheating, battery degradation/swelling, and shortened camera sensor lifespan, CamMonitor is deliberately built as an **on-demand live monitor** rather than a 24/7 continuous recorder:

- **No Continuous Video Recording:** Keeping the camera sensor and video encoder constantly running generates significant heat and degrades the device and battery quickly.
- **No On-Device Object / Motion Detection:** Continuous computer vision models require holding the camera sensor and NPU/CPU at high utilization indefinitely.
- **Auto-Standby Power Conservation:** If no viewer is connected for 15 seconds, the camera hardware shuts down completely and enters low-power standby until a viewer connects.
- **Adding These Features:** If your specific use case requires continuous recording or object detection, you can use AI coding tools to extend this open-source codebase (e.g. adding CameraX `VideoCapture` or MLKit object detection onto the existing pipeline).

---

## Privacy & Security

- **No Video Relaying:** Media streams directly peer-to-peer between your phones via WebRTC.
- **End-to-End Encryption:** Audio, video, and control signals are protected by DTLS-SRTP and AES-GCM encryption.
- **Zero Cloud Footprint:** No credentials, tokens, or recordings are sent to or stored on any server.

---

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

