# Glasses Hub

Clean-room Android companion prototype for Meta AI glasses.

## Included

- Meta Wearables Device Access Toolkit 0.8 camera/session integration.
- Terminal-session recreation and exponential reconnect.
- Maximum SDK camera mode: 720x1280 at 30 FPS.
- Local-network MJPEG viewer, snapshot endpoint, status endpoint, access code, and foreground notification.
- ChatGPT official-app voice launch and no-key accessibility response relay.
- Spotify launch plus system media controls.
- Connected-app launcher cards and tap-to-speak command routing.

## Meta setup

For a local development build, enable Developer Mode in the Meta AI app. This project uses DAT application ID and client token `0` by default. For production, build with:

```
-PmwdatApplicationId=YOUR_ID -PmwdatClientToken=YOUR_TOKEN
```

The official Meta AI app remains required for device ownership, firmware, registration, and capability permission flows.

## Web camera

Start **Camera to web** in the app. The phone hosts:

- `/` browser viewer
- `/stream.mjpg` multipart MJPEG
- `/snapshot.jpg` current JPEG
- `/status.json` source, frame, viewer, and resolution status

All routes require the access token shown in the app. The server is intended for a trusted local network and should not be port-forwarded to the internet.
