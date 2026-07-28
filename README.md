# Meta glasses + ChatGPT no-key prototypes

Two independent Android proof-of-concepts. Neither app contains an OpenAI API key, Spotify developer key, ChatGPT token extractor, or private backend client.

## 1. Glasses Voice Bridge

Uses Android intents/deep links to open the installed ChatGPT app and sends media-key commands to the active Android media session.

### Intended flow

1. Pair the glasses normally and select them as the Android media output.
2. Tap **Open ChatGPT Voice** or dictate a prompt.
3. The official ChatGPT app creates the conversation and speaks the response.
4. Android routes that audio to the glasses.

### Pros

- Uses the official ChatGPT app and the account already signed in there.
- Conversations can remain normal ChatGPT chats.
- No accessibility access and minimal privacy exposure.
- Less likely to break than screen automation.
- Spotify launch and basic play/pause/skip do not require a Spotify developer key.

### Cons

- Cannot programmatically read the ChatGPT response.
- ChatGPT must remain responsible for speaking the response.
- Deep-link behavior may change between ChatGPT versions.
- It does not replace Meta's private pairing, firmware, ownership, or camera protocol.

## 2. Glasses Chat Relay

Uses a package-restricted Android AccessibilityService to place a prompt into the installed ChatGPT app, watch visible response text, and speak a stable response with Android TTS.

### Intended flow

1. Enable **ChatGPT response relay** in Android Accessibility settings.
2. Enter or dictate a prompt in the relay app.
3. The app opens ChatGPT, inserts the prompt, and attempts to press Send.
4. When the visible response stops changing, Android TTS reads it through the active media output.

### Pros

- No OpenAI API key or separate API billing.
- The relay app receives a usable spoken answer path instead of relying on ChatGPT Voice.
- The official ChatGPT app still owns the login and conversation.
- Can later route recognized commands to Spotify, Home Assistant, phone actions, or other apps.

### Cons

- Requires powerful accessibility permission.
- ChatGPT UI changes can break text-field, send-button, or answer detection.
- Streaming answers may be read late, partially, or with unrelated interface text.
- Android TTS is used for the final speech, not ChatGPT's native voice.
- Not suitable for unattended or safety-critical operation.

## Scope

These prototypes test the two no-key ChatGPT integration approaches. They do not yet implement the proprietary Meta glasses L2CAP/DataX connection, automatic recovery of that private session, or camera-to-LAN streaming. Those should be built as a separate transport layer after validating which ChatGPT interaction mode works reliably on the target phone.
