# Face Matcher for Android XR

Runs in the background on Android XR glasses. When a familiar face appears in your field of view, a floating name badge appears automatically. Learns new faces by listening to introductions — no manual input required.

## How it works

The app runs as a background service. There is no camera preview — you see the world directly through the glasses. Two pipelines run simultaneously:

- **Camera** — detects and recognises faces continuously, showing a name overlay when a saved face is seen
- **Microphone** — listens for introductions and automatically saves the face in frame when a name is detected

## Trigger phrases

The following phrases cause the app to automatically remember whoever is currently in frame.

### Direct introductions (detected instantly, works offline)

| What's said | What's detected |
|-------------|-----------------|
| *"I'm Sarah"* | Sarah |
| *"I am Sarah"* | Sarah |
| *"My name is Sarah"* | Sarah |
| *"My name's Sarah"* | Sarah |
| *"Name is Sarah"* | Sarah |
| *"Name's Sarah"* | Sarah |
| *"Call me Sarah"* | Sarah |
| *"They call me Sarah"* | Sarah |

### Contextual replies (powered by Gemini, requires API key)

When you ask someone their name, the next short reply is interpreted as the answer.

| You say | They say | What's detected |
|---------|----------|-----------------|
| *"What's your name?"* | *"Joe"* | Joe |
| *"What is your name?"* | *"It's Joe"* | Joe |
| *"Your name?"* | *"Joe Smith"* | Joe |
| *"Name again?"* | *"Joe"* | Joe |
| *"Who are you?"* | *"I'm Joe"* | Joe |
| *"Remind me?"* | *"Joe"* | Joe |

Replies of up to 4 words are considered. Longer replies are ignored.

## Setup

### Requirements

- Android XR device or emulator (API 34+)
- Android Studio Canary (for XR emulator support)

### Gemini API key (optional)

Contextual reply detection requires a Gemini API key. Without it, only direct introduction phrases are recognised.

1. Get a key from [Google AI Studio](https://aistudio.google.com)
2. Add it to `local.properties` in the project root:

```
geminiApiKey=YOUR_KEY_HERE
```

### Permissions

The app will walk you through granting these on first launch:

| Permission | Purpose |
|------------|---------|
| Camera | Face detection |
| Microphone | Introduction detection |
| Notifications | Keeps the background service alive |
| Display over other apps | AR name overlay |

On the XR emulator, the overlay permission must be granted via ADB:
```sh
adb shell appops set com.example.facematcher SYSTEM_ALERT_WINDOW allow
```

## Usage

1. Open the app and grant all permissions
2. Tap **Start** — the app can now be closed
3. Look at someone as they say their name — it is saved automatically
4. Next time that person appears, their name floats in your field of view
5. To add a face manually, open the app while looking at the person and tap **Remember this face…**
