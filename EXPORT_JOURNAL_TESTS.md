# Isolated export-job regression tests

New test source and workflow have NOT been compiled or run. User reported all short reliability-patch checks passed; that remains manual functional evidence, not an automated test result.

Adds 13 JUnit/Robolectric tests using API 28, isolated TemporaryFolder files, namespaced test preferences and fake free-space statistics. Fixture bytes are NOT playable video and must never be used to claim codec or output-integrity validation. Production Kotlin, manifest, encoder settings, APK version and existing APK build workflow are unchanged. Test-only dependencies are not production app dependencies.

Coverage: durable queued round trip; single pending/running job; retry tokens/stale callbacks; forbidden phase jumps; missing/empty expected output; terminal metadata deletion preserving files; source metadata change; synthetic previous-session recovery; current-session protection; malformed/oversized journal rejection; low-space write refusal preserving prior journal; invalid percent. Previous-session recovery is simulated by editing fixture JSON, NOT killing a real Android process.

Manual workflow: GitHub Actions > Test Export Job Journal > Run workflow > main. No APK signing secrets, phone/emulator, installation or real recordings are involved. The runner downloads test dependencies and Android simulation artifacts; failures can be infrastructure/dependency failures as well as assertions. Workflow runs only on manual dispatch and has a 20-minute timeout. This commit does not start it. Existing APK builds do not automatically run this suite.

The workflow uploads HTML/XML test reports when available, even on failure. Passing these tests would NOT establish Android 15 service timeout behavior, cancellation race safety, camera/audio quality, actual process-death recovery, thermal endurance or two-hour reliability. Those remain device-test work.
