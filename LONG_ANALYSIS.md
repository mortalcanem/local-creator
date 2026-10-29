# Two-hour face analysis and checkpoints — development step

Source change only; not compiled or device-tested. The 0.9 service recorder build result is still unconfirmed. This change does not make the end-to-end long workflow complete.

Removes the analysis module's ten-minute rejection. Accepts up to two hours plus ten seconds of finalization tolerance. Keeps one-second sampling and the existing effect geometry/timing. Roughly 7,200 face samples for two hours, one scaled bitmap processed at a time. Extracted images are not saved. Local face detection remains bundled/offline.

Writes an AtomicFile JSON checkpoint after each 30 samples and at successful completion. Checkpoints live under managed working storage and are counted toward the configurable budget. Cache key includes version, file name, byte length, modification time and duration. This is a file-metadata fingerprint, NOT a hash of video contents. A source change invalidates the scan. Cached rows are validated for expected time-prefix and finite normalized boxes; malformed caches restart.

Re-exporting the same unchanged recording reuses a completed face plan or resumes after its last saved sample. Canceling midway may repeat up to 29 sample intervals plus an in-flight frame on restart. This is analysis checkpoint recovery, NOT automatic job restart, export-render resumption, crash-proof MP4 recovery or cloud processing. Atomic checkpoint write errors stop analysis with an error; originals stay unchanged.

A process-wide mutex serializes scans so quick cancel/restart cannot write the same plan concurrently. Tasks.await remains blocking on IO during inference; cancellation is checked before/after and can wait for that call to finish. Working-file cleanup deletes checkpoints, so clearing temporary files removes the ability to reuse that analysis. Old plan files are not automatically pruned yet and source deletion can leave small obsolete plans. No unlimited storage promise.

UI still says up to 10 min and old screens still display 10 GB. Those strings are now stale and MUST be corrected in the upcoming exporter/UI patch before recommending an installer. Existing export's 2 GB ceiling and cancel-on-leaving behavior remain. Long recording is still experimental single-file recording; its journal is not clip repair. No two-hour runtime/speed/thermal tests have happened.

Next: update UI to dynamic budget and two-hour analysis wording; replace export ceiling with managed-space checks; durable export job handling; compile/lint and Galaxy tests. Test partial scan cancellation and retry; completed plan reuse; invalidation after source change; corrupted checkpoint; no-face and multi-face cases; and long analysis duration incrementally. No YouTube, Shorts, speech models or topic bubbles are added here.
