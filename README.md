# Glasses Hub v0.1.1

Clean-room Android companion prototype for Meta AI glasses.

## Included

- Meta Wearables Device Access Toolkit 0.8 camera/session integration.
- Terminal-session recreation and exponential reconnect.
- Maximum current DAT live mode: 720x1280 at 30 FPS.
- Low-latency on-phone preview from the newest raw I420 frame, without LAN buffering.
- Wi-Fi or phone-hotspot-only browser viewer on port 8080.
- User-set fixed LAN delay from 0 to 5000 ms, applied to both video and microphone audio.
- Smooth 30 FPS delayed MJPEG playback, delayed snapshots, status endpoint and access token.
- DroidCam-compatible PC endpoint on TCP 4747 and UDP 4748.
- DroidCam JPEG video framing and Speex wideband 16 kHz mono microphone framing.
- Glasses microphone extraction from the decoded DAT stream, then downmixing/resampling for the DroidCam client.
- ChatGPT official-app voice launch and no-key accessibility response relay.
- Spotify launch plus system media controls.
- Connected-app launcher cards and tap-to-speak command routing.

## Meta setup

For a local development build, enable Developer Mode in the Meta AI app. This project uses DAT application ID and client token `0` by default. For production, build with:

```
-PmwdatApplicationId=YOUR_ID -PmwdatClientToken=YOUR_TOKEN
```

The official Meta AI app remains required for device ownership, firmware, registration and capability permission flows.

## Wi-Fi LAN web camera

Connect the phone and viewing device to the same Wi-Fi network, or enable the phone hotspot and connect the viewer to it. Start **Glasses LAN** in the app. The phone hosts:

- `/` browser viewer
- `/stream.mjpg` fixed-delay multipart MJPEG
- `/snapshot.jpg` delayed JPEG snapshot
- `/status.json` source, FPS, buffer, audio and interface status

All routes require the access token shown in the app. The server binds only to a private Wi-Fi or hotspot IPv4 address. It does not intentionally bind to cellular, VPN, Wi-Fi Direct or loopback interfaces.

## DroidCam PC client

1. Start **Glasses LAN** in Glasses Hub.
2. On the PC, open the DroidCam client.
3. Enter the phone's displayed Wi-Fi IP address.
4. Use port `4747`.
5. Enable both **Video** and **Audio**.

The compatibility server follows the open DroidCam Linux client protocol:

- JPEG video over TCP 4747
- Speex wideband audio over UDP 4748 when available
- TCP audio fallback over port 4747
- 16 kHz, 16-bit, mono, 20 ms Speex chunks

The configured fixed delay is applied to both streams from the same media timeline to preserve A/V synchronization.

## Current limitations

- Third-party DAT apps currently receive a 720x1280 live stream, not the glasses' full still-photo or recording resolution.
- The microphone bridge relies on the decoded audio flow present in DAT 0.8.0. If Meta changes or hides that implementation, video continues but DroidCam audio reports unavailable.
- DroidCam protocol compatibility is based on the open desktop client implementation and still requires physical testing against the target Windows client.
- Browser MJPEG contains video only. Use DroidCam mode for the synchronized PC microphone device.
- Do not port-forward either LAN service to the public internet.
