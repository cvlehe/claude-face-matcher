# Face Matcher: master plan

## Goal and scope

Build a dependable **phone app** that remembers a person's name after an introduction and shows the name when that face appears again. The phone camera must have a view of the person; background operation means the user can leave the app's control screen after starting a visible foreground session. It does not mean a phone in a pocket can see faces or that Android will permit an unattended camera and microphone service to run forever.

Android XR display glasses are the eventual target. The phone version should keep camera input, audio input, and name presentation replaceable so a glasses implementation can use the same recognition and enrollment logic later. Glasses-specific APIs and UI are outside this milestone.

## Phone release behavior

1. The user grants camera and microphone access, then explicitly starts a session. A persistent notification shows that recognition is active and offers a way to stop it.
2. While the camera has a usable view, the app detects faces and classifies each as known, unknown, or uncertain. An uncertain match is treated as unknown for display and is never silently assigned a name.
3. When one stable, unknown face is visible, the app listens for a plausible introduction such as “Hi, I'm Paul” or “My name is Samantha.” It can detect direct introductions without a Gemini key or network connection if an on-device speech engine is available.
4. The app associates the detected name with a face observed at the time of the speech event. If there is no suitable face, multiple plausible faces, or insufficient confidence, it does not silently save an identity. It offers a confirmation path when useful.
5. The name and face representation survive an app restart. When that person appears later, a small, legible badge appears once per appearance on the phone screen. The app does not repeatedly flash the same name while the person remains in view.
6. The user can inspect saved people, correct a name, remove one person, add a person manually, or delete all saved people. Stopping a session releases the camera and microphone.

## Product rules

- Prefer an unknown result over a wrong name. A recognition decision needs a calibrated distance threshold and a clear margin over the next-best identity.
- Never infer who spoke from a transcript alone. For automatic enrollment, require exactly one eligible unknown face tracked through the introduction window. Ask for confirmation when the scene is ambiguous.
- Store face embeddings and names, not raw camera frames or audio recordings. Keep transient transcripts only as long as needed for name extraction and remove them from logs.
- Show an accurate active, unavailable, or stopped state. Missing permissions, unavailable speech recognition, camera failures, and service termination must be visible to the user.
- Support the flow without a Gemini API key. Any cloud-based language processing is optional, explicitly enabled, and separate from basic introduction detection.
- No automatic launch at boot is promised. Android restricts when camera and microphone foreground services can start; the app must respect those limits.

## Current implementation baseline

The repository already contains CameraX frame analysis, ML Kit face detection, a TFLite embedding model, JSON face storage, speech phrase parsing, a foreground service, manual enrollment, and a screen overlay. `:app:assembleDebug --offline` succeeded on 2026-11-26. These pieces have not yet been verified as a complete phone flow.

Known gaps to address:

- `FaceDetectionService` creates `NameDetector` only when `GEMINI_API_KEY` is set, even though direct phrase parsing does not need Gemini.
- The app manifest does not declare visibility of Android speech recognition services; `NameDetector.start()` exits silently when `SpeechRecognizer.isRecognitionAvailable()` is false.
- The current listener repeatedly restarts `SpeechRecognizer`, which Android does not intend for continuous recognition. Partial results are logged but not processed.
- A detected name is paired with the largest face in the latest result, regardless of whether that face is known, whether it is the speaker, or whether the frame corresponds to the speech event.
- The match threshold is fixed without documented calibration. The current name overlay is a fixed, large bottom-of-screen view and can repeat after a short cooldown.
- Stored identities are a plain JSON file, and current backup rules do not explicitly exclude that data. Transcript debug prints can expose private speech in logs.
- The existing activity and service are ordinary Android components; they do not access projected glasses hardware or render a projected glasses UI.

## Implementation tasks

Tasks are ordered by dependency. Each task is complete only when its acceptance checks pass on a real phone or an appropriate emulator, as well as in code review.

### Progress checklist

- [x] 1. Establish a runnable baseline
- [x] 2. Define the session lifecycle and visible status
- [ ] 3. Make camera observations reliable
- [ ] 4. Validate and calibrate face matching
- [ ] 5. Add a spoken-name toggle
- [ ] 6. Make speech recognition available without Gemini
- [ ] 7. Build a bounded, resilient listening strategy
- [ ] 8. Extract names conservatively
- [ ] 9. Associate each introduction with the correct face
- [ ] 10. Persist and manage identities safely
- [ ] 11. Present recognition clearly on the phone
- [ ] 12. Protect privacy and make corrections easy
- [ ] 13. Verify the complete phone experience and set a release gate

When a task's **Done when** condition is met, change its checkbox to `[x]` in the same commit as the completed work and push that commit to GitHub. Leave unfinished tasks unchecked. The checklist is the source of truth for progress; the sections below contain the requirements and completion checks.

### 1. Establish a runnable baseline

Run the current app on a phone. Verify permissions, Start/Stop, camera frames, model initialization, speech callbacks, storage, and overlay display. Record device and OS version, observable failures, and reproducible steps. Keep this baseline in a short test log in the repository. Do not treat a successful build as runtime proof.

Baseline result: [Phone baseline test log](PHONE_BASELINE_TEST_LOG.md).

**Done when:** The starting behavior and an end-to-end result are reproducible, with each failure recorded as a concrete test case.

### 2. Define the session lifecycle and visible status

Make Start and Stop authoritative across the activity, foreground service, notification, and process restarts. Start only from a user-visible action allowed by Android. Report camera, microphone, and speech availability separately. Provide a Stop action in the notification and release resources promptly. Avoid duplicate camera bindings or recognizer sessions after repeated starts.

**Done when:** Starting, leaving the app, returning, stopping, permission revocation, and service interruption all produce accurate UI and resource state.

Verification: [Step 2 phone test log](PHONE_SESSION_LIFECYCLE_TEST_LOG.md).

### 3. Make camera observations reliable

Select the intended phone camera explicitly and handle unavailable cameras and bind failures. Attach a timestamp, frame size, bounding box, tracking identifier where available, embedding, and quality assessment to each face observation. Reject faces that are too small, blurred, cut off, or otherwise unsuitable for enrollment. Keep analysis responsive without queuing stale frames.

**Done when:** The app consistently detects and tracks visible faces on test devices and surfaces camera failures instead of silently continuing.

### 4. Validate and calibrate face matching

Check the bundled model's input shape, channel order, normalization, and output against the actual TFLite model. Build a labeled, consented evaluation set with multiple appearances per person and unrelated people. Measure false matches and missed matches across lighting, distance, angle, and camera movement. Choose a threshold and second-best margin from these measurements; keep unknown as the default when confidence is weak.

**Done when:** Matching behavior and its measured error rates are documented, and the threshold is supported by test data rather than a hard-coded guess.

### 5. Add a spoken-name toggle

Add a persistent **Speak recognized names** toggle to the phone controls. When enabled, say a recognized person's name aloud with text-to-speech at the same time as the visual name toast or badge. Speak once per appearance after a stable match, and avoid repeated announcements from consecutive camera frames. When disabled, keep the visual alert and make no spoken announcement. Handle unavailable text-to-speech and audio interruptions visibly without blocking recognition. Keep speech output behind a replaceable presenter so a future glasses version can route it to the glasses.

**Done when:** On a phone, a recognized face produces both the visual alert and one audible name with the toggle on; the toggle survives an app restart; turning it off leaves the visual alert working and produces no spoken name.

### 6. Make speech recognition available without Gemini

Add the required Android speech-service manifest visibility and decouple direct introduction parsing from the Gemini API key. Prefer on-device speech recognition when available. If the device cannot provide the selected speech engine, show that limitation and keep manual enrollment usable. Do not label the microphone pipeline active when it is not receiving results.

**Done when:** Direct phrases produce transcripts on a supported phone without a Gemini key, and an unavailable recognizer yields a visible, actionable state.

### 7. Build a bounded, resilient listening strategy

Replace the unbounded restart loop with a strategy appropriate to the chosen speech engine. Coordinate listening with a stable unknown-face candidate, allow enough lead-in to catch short introductions, and stop or suspend listening when it is unnecessary. Handle recognizer busy, silence, timeout, permission loss, and service restart with bounded retries. Measure battery use during a realistic active session.

**Done when:** Introductions are captured reliably during an active session, the listener recovers from ordinary errors, and battery behavior is measured and documented.

### 8. Extract names conservatively

Parse common direct introductions and short answers to a recent name question. Use word boundaries, time-limited question context, name validation, and deduplication. Reject ordinary expressions that resemble names, such as “I'm fine,” and avoid treating unrelated later speech as an answer. Keep optional language-model assistance behind an interface with explicit user consent if it sends transcripts off-device.

**Done when:** A table of positive and negative transcript examples passes, including variants, punctuation, false triggers, and split recognition sessions.

### 9. Associate each introduction with the correct face

Use timestamps and face tracks to join speech events to camera observations. Auto-enroll only when one eligible unknown face is stable throughout the association window. Do not enroll a recognized face under a new name. For multiple faces, uncertain matches, or a person leaving frame mid-introduction, present a confirmation or defer enrollment. Store more than one good embedding for a person if evaluation shows that it improves recognition.

**Done when:** Tests with one face, multiple faces, no face, a known face, and a speaker outside the frame never silently save a name to an ambiguous face.

### 10. Persist and manage identities safely

Replace or harden the JSON store so writes are atomic and data can be migrated across app versions. Define a `Person` identity with editable name and one or more embeddings. Add list, rename, delete-one, delete-all, and manual-add flows. Keep the data in app-private storage, encrypt it at rest if the selected storage design supports that reliably, and explicitly exclude face data from automatic cloud backup unless the user opts in to a designed backup feature.

**Done when:** Saved people survive process and device restarts, edits are reflected in recognition, deletion removes the intended data, and interrupted writes do not erase the collection.

### 11. Present recognition clearly on the phone

Replace the current large fixed overlay with a compact badge. Show it only after a stable match, once per new appearance, with a sensible timeout. Handle two recognized people without implying which name belongs to an untracked face. Keep the badge usable over other apps when overlay permission is granted; provide an in-app fallback when it is not.

**Done when:** The user can read the name at a glance and repeated frames do not produce repeated alerts.

### 12. Protect privacy and make corrections easy

Explain camera, microphone, and optional network use during onboarding. Keep capture visibly active through the foreground notification. Remove raw transcripts, names, embeddings, and API credentials from debug logging. Add a straightforward way to correct an incorrect association immediately after enrollment or recognition. Verify that Stop releases camera and microphone access.

**Done when:** A user can tell when sensing is active, can correct mistakes, and can delete the stored identity data; logs and backups do not inadvertently expose it.

### 13. Verify the complete phone experience and set a release gate

Test the flow across fresh install, permission denial and later grant, direct introduction, contextual reply, reappearance after restart, multiple people, poor lighting, camera interruption, speech errors, and deletion. Run a small consented field pilot. Record correct enrollments, wrong enrollments, correct recognition alerts, wrong-name alerts, missed recognitions, latency, and battery use. Set explicit acceptable limits from those results before declaring the phone version ready.

**Done when:** Every release behavior above is demonstrated on a phone, known limitations are documented, and the measured error rates meet the agreed limits.

## Architecture boundary for future glasses

Keep the core pipeline independent of Android display and hardware APIs:

```text
Camera source → timestamped face observations ┐
                                             ├→ association and identity store → recognition events → name presenter
Audio source  → timestamped speech events    ┘
```

The phone milestone provides phone camera, microphone, and badge adapters. A later Android XR milestone provides a projected glasses camera and microphone context, glasses-specific permission flow, and projected display UI. The face matcher, association rules, and identity store should remain reusable. The glasses milestone will require its own device test and permission review; a phone test cannot prove glasses behavior.

## Platform references

- [Android `SpeechRecognizer` API](https://developer.android.com/reference/android/speech/SpeechRecognizer): speech-service visibility and limits of continuous recognition.
- [Restrictions on starting foreground services from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start): camera and microphone startup constraints.
- [Use a projected context to access glasses hardware](https://developer.android.com/develop/xr/jetpack-xr-sdk/access-hardware-projected-context): future glasses camera and microphone integration.
- [Create an activity for audio and display glasses](https://developer.android.com/develop/xr/jetpack-xr-sdk/glasses/first-activity): future projected UI entry point.
