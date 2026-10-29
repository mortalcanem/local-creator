# Intro export milestone 0.7

Uncompiled and device-untested source addition. Owner reports successful 0.6 recording/quality test. MainActivity is unchanged, including its 0.6 heading; HomeActivity now provides navigation to that tested recorder and the new exporter.

Workflow: record/import intro on recording screen, return home, open Add intro and export, choose recording and vertical or landscape output, export, play finished file, optionally save a copy. Export is user-triggered in this milestone, NOT automatically run after each recording. Automatic post-record jobs remain pending.

Uses Media3 Transformer/effect 1.5.1 with explicit UnstableApi opt-in. Output is 1080p H.264/AAC, preserves aspect ratio with padding instead of cropping, normalizes mono/stereo inputs to mono at 48 kHz, and requests silent audio for inputs without audio. Spatial/surround audio inputs and unusual formats may fail. Intro and recording audio stay sequential; no music overlay or transition fade. 4K originals remain unchanged; 4K export/HDR policy is not implemented.

Storage: output first written under managed working directory, then moved to managed exports on success. Preflight estimates 2 MB/second plus headroom; this is conservative but not a guaranteed reservation or bitrate. Actual encoder bitrate is automatic. Poll every second for app budget/free reserve and a 2 GB output ceiling. Buffering and other apps can cause overshoot. No hard quota. Original and intro remain untouched.

Export/copy are foreground activity jobs, canceled on leaving screen, rotation or Back; not resumable. Screen kept awake while active. Temp deletion is best effort; app process death may leave working files for explicit cleanup on recorder screen. No foreground service or wake lock. Completion/cancel races, background timing, storage exceptions and Android codec/device differences need actual testing.

Save Copy destination can be local or a cloud provider; app still has no internet permission. Failed/interrupted copy can leave a partial destination. Opening/verifying copy before deleting originals is required. Delete button affects selected finished export only, after confirmation.

Manual debug build workflow remains unchanged and must be started after commit. Persistent signing remains unprovisioned, so separate debug builds may require reinstall. Owner says current test files are disposable; no files are deleted by this commit. Readme camera/initial-build notes are historical.

Testing: build/lint; home navigation; import short valid intro; 10-second voice recording; export portrait and landscape; verify intro precedes recording, face not cropped, audio present and synced across join; silent intro; mono/stereo intro; 4K original downscale; playback/save copy/delete; cancel and background; budget preflight. Compilation success is not device-test success.

Not added: face reframing, topic bubbles, offline speech/text models, Shorts, question metadata, YouTube uploads.
