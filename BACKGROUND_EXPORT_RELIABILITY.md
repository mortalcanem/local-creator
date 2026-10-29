# Background export reliability patch

Source change only; not built or phone-tested. User reported the preceding background short tests worked, including notification visibility. This patch does not change encoder settings, face effects, notification appearance, screens or manifest.

Terminal outcomes now distinguish explicit user cancellation (CANCELED), handled exceptions (FAILED), and service timeout/lifecycle interruption (INTERRUPTED). Process death still relies on previous-process journal recovery. A late cancellation after output move and successful COMPLETED persistence cannot replace the success text with a canceled message. If output move succeeds but completion persistence fails, output remains preserved and retry still refuses overwrite. No automatic reconciliation or partial-render continuation added.

Claim stores the attempt record inside a non-cancellable IO block, then checks cancellation. This lets cleanup know the token if cancellation arrives while the durable claim is being written. The rendering progress monitor is canceled and joined before leaving render(), preventing in-flight monitoring writes from racing final journal transitions. Resource release/UI-guard cleanup runs even when partial-output cleanup fails, and failed cleanup is reported.

Build/lint and regression tests required: normal effects-off/on export, immediate Cancel around startup, Cancel during analysis/rendering, Cancel near completion, retry, notification Cancel, and foreground fallback. Real process-kill/manual-relaunch and two-hour duration tests remain unverified. Journal failure at storage limits remains possible. No crash-proof recording recovery, automatic post-record pipeline or uploads.
