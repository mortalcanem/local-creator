# Experimental background export service and screen

UNBUILT / UNTESTED SOURCE. The earlier foreground-export short tests passed per the user; they do not validate this update. Existing recorder, FaceMotion and IntroExportActivity are unchanged.

Home adds a separate experimental background-export entry. It uses ExportJobs with manual retry/history and retains the existing export screen as fallback and finished-video viewer. Starting requires notification permission on Android 13+ and visible UI. Home blocks camera/file-management/budget entry during an active export. No network permission or uploading added. Home navigation guards are not general OS/file locking and need back-stack/notification testing.

Service uses mediaProcessing on Android 15 and dataSync for local export processing on earlier supported versions. Android 15 imposes shared background runtime allowances, typically six hours per 24 hours. onTimeout requests cancellation and stopSelf promptly. An independent 5h50 timeout and six-hour wake-lock bound are safeguards, not guaranteed available runtime. No reboot, force-stop or automatic restart support (START_NOT_STICKY).

Analysis reuses existing checkpoints; rendering restarts from beginning after interruption. Job journals updated about every ten seconds; progress notification/UI updates more often. Output allowance/budget/free-space checks poll every two seconds, not hard quotas. Input size/mtime checked during rendering. Cancellation stops transformer before terminal journal update; canceled/error jobs are conservatively marked INTERRUPTED. Cleanup deletes only that attempt's partial output, best effort; originals and completed exports kept. Journals can fail to persist at storage limits and this is reported.

Finalization is non-cancellable within one IO block, so late Cancel can still complete successfully. If move succeeds but completion journal fails, finished file is preserved. Retry refuses to overwrite it and tells user to review finished videos. No automatic reconciliation, continuation of partial MP4, playable-file validation or claim of crash-proof recovery. Process-killed partials may remain for manual temporary cleanup after work stops. No auto post-record pipeline or Shorts/transcription/bubbles/uploads.

Build/lint required. Then test a short intro+clip with effects off/on; Home/Back/reopen/notification tap while exporting; cancellation during analysis and rendering; retry and checkpoint reuse; denial/revocation of notifications; lock screen; activity rotation; process death/manual relaunch; source disappearance; low reserve/budget; and late cancel during finalization. Recheck tested foreground path and long-recorder launch guard. Do not rely on two-hour sessions yet.

Android reference: https://developer.android.com/about/versions/15/changes/foreground-service-types and https://developer.android.com/develop/background-work/services/fgs/service-types
