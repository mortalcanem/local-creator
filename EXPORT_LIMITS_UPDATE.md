# Dynamic storage displays and long-export groundwork

Source only; no build or phone tests performed. Package remains development 0.9.

MainActivity/IntroExportActivity now display Snapshot.budget rather than hard-coded 10 GB. Face checkbox says recordings up to two hours, matching checkpointed analysis. Short recorder is explicitly labeled as 2 GB per recording; long recorder is the separate experimental service path. Intro messages direct users to the actual export screen. Temp cleanup warns that face checkpoints are deleted.

Exporter no longer uses a fixed 2 GB output cutoff. Preflight still estimates duration × 2 MB/s plus headroom; after face analysis it computes an output allowance from minimum of remaining managed budget and phone free space above 50 GB reserve, minus headroom. One-second monitoring checks that allowance, current budget/free reserve and source size/mtime. Source changes abort. Output finalized only if nonempty, within allowance, sources unchanged, and final storage check passes. This is polling-based, NOT a hard quota, file-system reservation, guaranteed capacity or unlimited MP4 support. Device codecs/muxer may still fail on long media.

Generation tokens prevent stale canceled callbacks/monitor jobs from resetting a later job's UI. Rendering still runs in activity and is canceled on leaving/rotation/Back. A completed face plan can be reused after retry; final rendered MP4 cannot resume. Mid-finalization cancellation can leave a successfully moved finished file in exports, which is preserved and should be reviewed. Temp cleanup is best effort.

This does NOT add a background export service, persistent export queue, recording crash recovery, auto post-record pipeline, Shorts, transcription, bubbles, metadata or uploads. New long recorder remains untested and its notification permissions/orientation/lifecycle need audit. Do not recommend important two-hour sessions.

Next: compile/lint the accumulated source and fix errors; audit recording-service permission checks and orientation; durable rendering/job handling; then incremental Galaxy testing. Previous notes saying labels remain stale and export cutoff is 2 GB are historical and superseded by this source patch, not a record of test success.
