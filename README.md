# QuestGPT 2.1 — Meta Quest 3

The current application is the independent native Android module **reborn** (`pl.somaskan.questgpt2`). The `app` and `server` directories retain the earlier implementation for reference. The new APK uses the user's own OpenAI API key, encrypted on the headset with Android Keystore; no PC or personal backend is needed after installation.

Implemented: Polish text chat and image questions through Responses, native Realtime audio with interruption, user-approved MediaProjection in a foreground service, a shared Mini panel with its own launcher entry, and delayed screenshots. Mini controls fit the Quest minimum panel width; keyboard insets keep the composer accessible. A 48 kHz microphone fallback converts audio to the 24 kHz format expected by Realtime.

Build and verify:

```sh
gradle :reborn:testDebugUnitTest :reborn:assembleRelease :reborn:lintDebug
gradle :reborn:connectedDebugAndroidTest
```

The workflow `.github/workflows/questgpt2.yml` builds the APK and runs native Android tests. Release APKs are signed outside the repository with the existing QuestGPT 2 certificate. APK installation still requires approval on the headset.

Start: install QuestGPT 2, open **⋯ → Połączenie**, save your API key and test the connection. Enable **Live** for audio and **Ekran** for screen sharing. **Widok** opens current-view questions, frame preview and a five-second screenshot timer. **Mini** also has its own launcher icon. See [QUESTGPT2.md](QUESTGPT2.md) for instructions.

Horizon OS controls panel visibility, audio focus and capture. This is not a privileged persistent overlay over every immersive application. Screen input consists of individual frames, not full-motion video understanding. OpenAI API billing is separate from ChatGPT subscriptions; this app does not inherit ChatGPT account conversations or memory. Build/emulator tests do not certify a physical Quest or a paid OpenAI session.
