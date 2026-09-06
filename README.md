# QuestGPT Panel

Native Android / Jetpack Compose panel for Meta Quest 3 / Horizon OS.

## MVP

- Resizable 2D Horizon OS panel
- Text chat through OpenAI Responses API
- Image input from the Quest file picker
- One-shot headset screenshot through Android MediaProjection (explicit user consent required)
- Low-latency voice through OpenAI Realtime API (PCM 24 kHz) via a tiny backend WebSocket proxy
- Conversation history and `previous_response_id` continuity
- No OpenAI API key embedded in the APK

## Architecture

`Quest 3 APK -> your HTTPS/WSS backend -> OpenAI API`

The backend is intentional: OpenAI API keys must not be shipped inside client applications. The Android app talks only to your backend URL.

## Run backend

```bash
cd server
npm install
OPENAI_API_KEY=sk-... npm start
```

Default port: `8787`.

For a real headset use an HTTPS/WSS URL reachable from the Quest (for example a deployed server or a LAN endpoint during development). Enter that base URL in the app.

## Build APK

Open the repository in Android Studio or run:

```bash
gradle :app:assembleDebug
```

APK output:

`app/build/outputs/apk/debug/app-debug.apk`

## Install on Quest

Enable Developer Mode and USB debugging, then:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

or with current Meta VR CLI:

```bash
metavr app install app/build/outputs/apk/debug/app-debug.apk
```

After sideloading, the app runs directly on Quest as a standard Horizon OS 2D panel.

## Important platform limits

This is not a privileged Meta Dash overlay. Horizon OS controls panel placement and overlay lifecycle. The panel can be opened alongside immersive content through the Quest system UI, but a normal third-party APK cannot force itself permanently above every app.

MediaProjection can capture the headset view only after the platform consent flow. Protected content can be excluded/blocked. Do not try to bypass those restrictions.

## OpenAI models

Server defaults:

- Responses: `gpt-5` (override with `OPENAI_TEXT_MODEL`)
- Realtime: `gpt-realtime-2.1` (override with `OPENAI_REALTIME_MODEL`)

Model names are configurable so the app can move to newer snapshots without rebuilding the APK.
