# Phone camera observations test log

## Session: 2026-11-26

- **Plan task:** [3. Make camera observations reliable](MASTER_PLAN.md#3-make-camera-observations-reliable)
- **Device:** Pixel 10 Pro XL, Android 17 (API 37), rear camera, connected over ADB
- **Build:** `./gradlew :app:assembleDebug --offline` succeeded; debug APK installed with `adb install -r`
- **Test image:** A consenting face image shown to the rear camera. No face embedding or camera frame was saved during this test.

| Check | Result | Observation |
| --- | --- | --- |
| Camera startup and frames | Pass | Start changed the UI to Camera: Active only after analyzed frames arrived. With an empty view, the UI reported In frame: 0. |
| Visible face and enrollment quality | Pass | The UI reported In frame: 1 and enabled Remember this face… while the face image was clearly visible. This held across three samples about two seconds apart. |
| Tracking | Pass | The debug-only readout reported tracking ID 2 for the same face in four samples about two seconds apart. |
| Unsuitable face | Pass | With the image moved farther away, the detector still reported In frame: 1, but showed Face unsuitable for saving: too small and disabled the save control. |
| Face leaves view | Pass | Removing the image changed the UI to In frame: 0 and disabled the save control. |
| Stop | Pass | Stop changed the UI to Stopped, Camera: Off, Microphone: Off, and Speech: Off. |
| Camera interruption attempt | Not reproduced | Temporarily enabling Android camera privacy and setting this app's camera app-op to ignore did not stop frame delivery on this device; the app continued reporting Camera: Active and In frame: 0. Both settings were restored. Camera state errors, bind failures, analyzer errors, and a five-second no-frame timeout have visible status paths in code, but those paths were not exercised by this device test. |
| Camera permission loss | Pass | Revoking camera permission during an active session ended it. Reopening the app showed Camera access required, Stopped, and Camera: Off. Camera permission was granted again after the check. |

The observation includes an elapsed-realtime timestamp, rotated frame dimensions, a clamped bounding box, ML Kit tracking ID when available, quality result, and an embedding only for suitable faces. Analysis uses CameraX keep-only-latest backpressure and does not queue a new face analysis while one is in flight. The size, edge, pose, and blur cutoffs are conservative starting rules; their real-world rejection rates remain to be measured during later evaluation.
