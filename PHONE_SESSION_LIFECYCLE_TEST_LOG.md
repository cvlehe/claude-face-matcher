# Phone session lifecycle test log

## Session: 2026-11-26

- **Plan task:** [2. Define the session lifecycle and visible status](MASTER_PLAN.md#2-define-the-session-lifecycle-and-visible-status)
- **Device:** Pixel 10 Pro XL, Android 17 (API 37), connected over ADB
- **Build:** `./gradlew :app:assembleDebug --offline` succeeded after the lifecycle changes
- **App:** `com.example.facematcher`, target SDK 35

| Check | Result | Observation |
| --- | --- | --- |
| Start from visible activity | Pass | Tapping Start showed Active, Camera: Active, Microphone: Active, Speech: Listening, and Stop. Android reported one foreground service with a notification action and camera access running. |
| Leave and return | Pass | Home kept the service foreground and camera access running. Reopening the activity restored the same active status. |
| Stop from activity | Pass | The UI showed Stopped and all three pipeline labels Off. The service disappeared and camera access stopped. |
| Duplicate binding | Pass | After a second installed build and Start, the service dump showed one unique activity connection record. The activity now tracks an outstanding bind request. |
| Camera permission revoked during session | Pass | Revoking camera permission ended the service and camera access. After restoring permission and reopening the app, the UI showed Stopped and Start. |
| Notification Stop path | Pass with method note | The notification reported one action. The same private `STOP_SESSION` intent used by its PendingIntent was invoked as the app UID; the UI showed Stopped, camera access ended, and the service was removed while the activity remained visible. The notification button itself was not tapped in the shade. |
| Service interruption | Pass | Permission revocation terminated the process during an active session. The service did not restart automatically, and the reopened app showed Stopped. |

Speech availability remains limited by the current Gemini-key gate. The UI reports that limitation when there is no key; removing the gate and validating direct recognition without a key are task 6.
