# Configurable storage budget

Owner superseded the uncommitted 150 GB proposal with a 350 GB default and an option to increase it later. Storage defaults to 350,000,000,000 bytes. Home screen Change storage budget accepts a positive whole number of decimal GB and persists it locally. It may be raised or lowered, but not below current managed usage. No automatic deletion, allocation or purchase. Overflow and invalid input rejected.

All snapshot-based recording/export/face-analysis checks read the persisted budget. Separate protected free-space reserve remains 50 GB; actual usable space is constrained by both limits. A budget greater than available phone space is only a ceiling, not a promise of that space. Installed app/bundled models, unrelated phone files and user-selected backup copies are not in managed-file accounting, but still affect free space. Checks are polling-based and not a hard filesystem quota.

This is a source-development step; no build or install has happened. The existing recording and export screens still have hard-coded 10 GB usage-display text. Those stale labels MUST be updated to use Snapshot.budget during the next UI/long-session patch before recommending another installer. Home screen and budget-exceeded errors display the actual configurable value. Historical docs retain earlier figures.

2 GB recording/export test limits, 10-minute face-effects cap and foreground-only processing are unchanged. This does not make 1–2-hour sessions supported yet. Do not recommend a long recording with the current tested installer. No signing changes, network permissions, video uploads or original-file deletions.
