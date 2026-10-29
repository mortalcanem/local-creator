# Camera quality milestone 0.6

Source changes only. Not yet compiled or device-tested. Previous 0.5 build and a short audio/video test succeeded according to the owner. README's original uncompiled warning describes the initial milestone; it is not a current CI status report.

Preview now uses FIT_CENTER to show the full frame without fill-cropping. Black bars are intentional. This does not independently improve sensor detail or encoding quality.

Quality button lists SDR recording modes exposed by the selected camera. Default remains 1080p when supported. Select 4K manually if offered. Preference is retained; unsupported modes are not offered. Binding failure tries supported 1080p or the first supported mode and reports fallback. Rear camera is used only if no front camera exists. The dialog title says front camera; rear fallback is identified on the main button.

Encoder bitrate stays CameraX-managed; frame rate, HDR, vendor beauty/sharpening, and proprietary Samsung video processing are not forced. No guarantee of native Samsung Camera parity. QualitySelector.getSupportedQualities is deprecated but retained for compatibility with the existing CameraX 1.4.1 dependencies.

Details button reads actual stored pixel dimensions, rotation, container bitrate, duration and file size after recording. Pixel dimensions may be landscape with a 90/270-degree rotation tag for portrait playback. Container bitrate is not a pure video-stream rate or a sharpness score. Metadata is read on demand off the UI thread.

10 GB managed-file budget, 50 GB free-space reserve, periodic checks and 2 GB per-recording limit remain. Higher bitrate modes can reach the file-size limit sooner. Originals are never automatically deleted.

Manual build workflow unchanged. Start a NEW debug run on main after commit; do not rerun an older commit. New ephemeral debug keys may prevent in-place installation. Owner says existing test footage need not be retained; do not delete anything remotely. Stable signing remains unprovisioned. No intro composition, AI effects, Shorts or YouTube features are added here.

Test preview/full-frame framing, front camera audio/video, quality changes while idle, 1080p and 4K if offered, actual Details dimensions, portrait orientation, background stop, storage thresholds and library actions. Quality change is blocked while recording/importing/backing up. Camera quality cannot compensate for every focus/exposure/lighting issue; assess saved output on device before claiming improvement.
