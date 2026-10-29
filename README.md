# Local Creator

Local-first Android recording and editing project. Source milestone 0.5. This project has NOT been compiled or device-tested. No APK is available until a build succeeds.

## Implemented in source
CameraX front-camera preview and microphone recording; private original files; playback; confirmed deletion; Save Copy using Android's document picker; imported local intro; storage accounting; 10 GB managed-files budget and 50 GB free-space reserve; periodic recording checks; 2 GB per-recording limit; temporary-file cleanup.

## Not implemented yet
Intro composition/export, face tracking and reframing, topic bubbles, offline transcription, local language model, intelligent Shorts, question titles/descriptions/tags, YouTube authorization/upload. No paid APIs, backend, analytics, or internet permission are included. Do not mistake this foundation for the complete requested app.

## Safety
Uninstalling or clearing app data deletes private originals and intro. Save copies and OPEN them to verify before deleting originals or reinstalling. Interrupted backup may leave a partial destination file. Screen rotation/process death can cancel import/backup. Recovery and durable jobs are not implemented. Recording errors preserve recoverable files, which may not be playable. Do not use untested builds for irreplaceable footage.

Budget includes files under files/creator, not the installed app, OS caches, or user-selected backup destinations. Polling/encoder buffers can overshoot thresholds; this is not a hard disk quota. Startup recording check reserves 256 MB, not a complete session. Default quality is FHD with device fallback. Other apps can consume storage concurrently. Older milestone MediaStore recordings are not migrated or deleted.

Imported intro is limited to 1 GB and is not yet merged into recordings. Replacing an intro needs space for both copies. Cloud document providers may upload chosen backups, even though this app has no internet permission. A local Save Copy creates another phone file outside the managed budget. Backup checks conservatively reserve local space even for remote providers.

## Build
JDK 17, Gradle 8.9, Android SDK 35, AGP 8.7.3. Gradle wrapper binary not bundled; workflow installs Gradle. The Actions workflow is MANUAL ONLY: uploading code does not trigger a build. Select debug for initial compilation/testing. Workflow also runs Android lint and signature verification. CI success is not device-test success.

Debug builds on ephemeral runners can have different signing keys. They are temporary test installers, not a safe durable update channel. Do not uninstall an existing build to fix a signature mismatch until originals are backed up and verified. Release signing consumes a persistent owner-controlled keystore via secrets: LOCAL_CREATOR_KEYSTORE_BASE64, LOCAL_CREATOR_STORE_PASSWORD, LOCAL_CREATOR_KEY_ALIAS, LOCAL_CREATOR_KEY_PASSWORD. No key has been provisioned. Never commit keys/passwords/tokens. Future release updates require the same private key and increasing versionCode.

## Device validation pending
Galaxy S26 Ultra 1 TB: permissions denial/retry; front-camera orientation/mirroring and audio; stop/background/rotation; storage-threshold simulations without filling disk; playback; backup destination cancellation and interrupted copy; intro replacement; deletion confirmation; temporary-file cleanup; signed update preservation.

The next engineering tasks are external compilation/fixes, actual phone testing, safe signing provisioning, and Media3 intro composition. Broad-topic local AI and YouTube publishing remain future work.
