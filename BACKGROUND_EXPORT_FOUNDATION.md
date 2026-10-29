# Background export: stage 1, job journal only

The user reported that the preceding build and short recording/intro/face-export/storage-display checks worked. This new source is not compiled or device-tested.

Adds ExportJobs, an unused persistence module. No manifest, service, screens, recorder, renderer or workflow changes. Existing export behavior remains foreground-only and canceled when leaving. No build is triggered by this change.

Jobs store source names, byte lengths/mtime, vertical and face-effect flags, phase, percent, attempt token and timestamps in AtomicFile JSON under managed creator/export_jobs. This directory survives Clear temp files, but NOT uninstall or clearing app data. Up to 100 job records; malformed records surface errors instead of being silently discarded. AtomicFile persistence is not a guarantee against all device/storage failures.

A single-process lock serializes journal operations. Claiming prevents another queued/running job and checks source metadata. Per-attempt tokens and process-session IDs reject stale updates. At startup the future worker must call recoverPreviousProcess before claiming; old in-flight states become INTERRUPTED, not automatically resumed. Queued entries remain queued. Source checking is metadata-based, not a content hash. Completion requires a nonempty expected output, NOT validated playback or codec correctness.

Future integration MUST: launch a properly declared foreground service from visible UI; handle notification permissions, service start failure, Android timeout and process death; stop the encoder before marking cancellation; preserve originals; guard recording/import/delete/temp cleanup across screens; throttle journal writes; surface write errors; reconcile files moved before completion was persisted; and handle partial output explicitly. No render continuation or export queue UI exists yet.

Journal writes use managed-budget/reserve checks. If storage is exhausted, even persisting FAILED/CANCELED can fail; the future worker must stop independently and report that persistence failed. An old running journal may then require recovery on the next process. No automatic input/output deletion occurs. forget deletes terminal job metadata only.

Next stage: service implementation and UI wiring, followed by build/lint and short background-navigation/cancel/relaunch tests. Two-hour reliability remains unverified.
