# Phone baseline test log

## Session: 2026-11-26

- **Plan task:** [1. Establish a runnable baseline](MASTER_PLAN.md#1-establish-a-runnable-baseline)
- **Device:** Pixel 10 Pro XL, Android 17 (API 37), connected over ADB
- **App:** `com.example.facematcher`, version 1.0 (versionCode 1), target SDK 35
- **Starting source commit:** `ec6ef70`
- **Starting build:** `./gradlew :app:assembleDebug --offline` succeeded
- **Starting install:** Debug APK installed with `adb install -r` at approximately 14:14 local time; install succeeded
- **Build change during session:** At 14:34 local time, a debug APK with camera-preview changes was installed. Results below distinguish that build from the starting build. The final end-to-end run used only this later APK; its `lastUpdateTime` remained `2026-11-26 14:34:32` throughout.
- **Device memory page size:** 4,096 bytes (`getconf PAGE_SIZE`)
- **Test-data handling:** Do not save camera frames, audio, transcripts, API keys, or personal notifications in this log.

### Checks

| Check | Result | Evidence or next action |
| --- | --- | --- |
| Package and permissions | Pass | Camera, microphone, and notification runtime permissions are granted. Overlay app-op reports `allow`; no permission warning appeared in the control panel. |
| Launch and visible controls | Pass | After unlock, `MainActivity` displayed Stopped, Saved faces: 0, and an enabled Start button. |
| Start and Stop | Pass on later build | Start changed the UI to Active and Stop. In a controlled second session after the 14:34 install, Stop changed the UI to Stopped and Android showed no service or running camera/microphone app-ops. |
| Camera frames and face detection | Pass | The controlled face produced a stored 512-value embedding through the live camera analysis path. |
| Face model initialization and embeddings | Pass | The model loaded, and the saved test record contains a 512-value embedding. |
| Speech callbacks and introduction parsing | Pass for direct phrase | After “Hi, I'm Avery,” the app saved a record with the expected name. The microphone app-op reported running during the test. Other phrases and error recovery remain untested. |
| Background continuation | Pass | After Home, the launcher was foreground while `FaceDetectionService` remained foreground and both camera and microphone app-ops still reported running. Reopening the activity preserved the session. |
| Saving and reloading a person | Pass across install/restart | UI showed Saved faces: 1, and `faces.json` contained one Avery record with 512 embedding values. After a force-stop and relaunch, the later build showed Stopped and Saved faces: 1. |
| Recognition badge | Pass, user observed twice | The user reported seeing the Avery badge when the same face or photo reappeared, including on the final stable-build run. The short-lived badge was not captured in a UI dump. |
| End-to-end introduction → saved face → later recognition | Pass on stable installed build | After the 14:34 install, the store was cleared, then the user showed the consenting face or photo, said “Hi, I'm Avery,” moved it away, and showed it again. The UI showed Saved faces: 1 and `In frame: 1 · Recognized: Avery`; the user confirmed the badge appeared. The installed APK did not change during this run. |
| Delete temporary identity | Pass | Before the final run, the app's Delete all saved faces UI cleared the one-record store. After the final run, the app was force-stopped, its camera and microphone app-ops were no longer running, and `faces.json` was verified to contain only Avery before removal. The file was absent afterward. |

### Observed issue and follow-up

- On launch, Android displayed an **Android App Compatibility** dialog: “This app isn’t 16 KB compatible.” It listed `libtensorflowlite_jni.so`, `libimpress_api_jni.so`, `libface_detector_v2_jni.so`, `libandroidx.xr.runtime.openxr.so`, `libimage_processing_util_jni.so`, and `libandroidx.graphics.path.so` as failing or unknown alignment checks. Reproduction: install and launch this debug build on the Pixel 10 Pro XL running Android 17; the warning appeared over Face Matcher. Tap **OK** to proceed. Recognition then worked on this 4 KB page-size phone. Investigate the native library alignment before release on 16 KB page-size devices.
- The controlled direct introduction worked. Other phrases, speech-engine availability states, and error recovery remain for later plan tasks.

### Reproduction notes

1. Build and install the current debug APK.
2. Launch `com.example.facematcher/.MainActivity` on the connected phone and unlock it.
3. Tap Start. The UI changes to `Active — watching for faces`, and Android reports the app's camera and microphone as running.
4. Press Home. The app's foreground service and camera/microphone access remain active while the launcher is visible. Reopen Face Matcher.
5. Point the rear camera at one consenting face or face photo and say “Hi, I'm Avery.” The UI changes to Saved faces: 1; `faces.json` contains one `Avery` record with 512 embedding values. No raw frame, audio, transcript, or embedding was retained in this log.
6. Move the face out of view and show it again. The user reported seeing the Avery badge.
7. After the later debug install, force-stop and reopen the app. The UI reads Stopped and Saved faces: 1.
8. Start a short second session, then press Stop. The UI reads Stopped and Saved faces: 1; Android shows no service or running camera/microphone app-ops.
9. Clear the one test record through the app UI. On the unchanged 14:34 APK, start a new session, show the same consenting face or photo, say “Hi, I'm Avery,” move it away, and show it again. The UI shows Saved faces: 1 and `In frame: 1 · Recognized: Avery`; the user sees the Avery badge.
10. Stop the test session. The app service and camera/microphone access end. Verify that the only stored record is Avery, then remove `faces.json`; the private file is absent afterward.

**Status:** Complete. Task 1 is checked in `MASTER_PLAN.md`. The observed compatibility warning is recorded above for follow-up.
