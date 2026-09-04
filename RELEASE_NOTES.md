## 0.1.0-alpha.10

Background scheduling hardening based on the alpha.8 on-device diagnostics.

- Builds on the alpha.9 source already present on main; phones still on alpha.8 can install alpha.10 directly after validation
- Replace stale periodic WorkManager generations on settings Save with `CANCEL_AND_REENQUEUE`
- Add a persisted next-due gate shared by WorkManager and the foreground service so delayed OEM jobs can never replay several missed rotations in a burst
- Add a `specialUse` foreground rotation service while automatic rotation is enabled, with a low-priority persistent notification, to keep cadence alive on aggressive OEM background managers such as HONOR/MagicOS
- Keep WorkManager periodic rotation as a durable fallback; foreground-service and WorkManager triggers race through the same persisted gate so only one may rotate
- Add a non-blocking automatic rotation path: if a visible transition is already active, later automatic triggers skip instead of queueing another transition
- Manual "Change now" defers the next automatic due time by the configured interval and interrupts/cancels cache preloading first
- Preload in two phases: warm one image per active destination before building the 8-image reserves
- Make running preloads cooperatively interruptible between searches, metadata checks and downloads
- Recover the automatic service/schedule after device boot and after in-place package replacement
- Add scheduler diagnostics: WorkManager generation/next schedule, persisted next-due gate, foreground-service heartbeat, claim/skip reasons
- Preserve the validated HONOR independent Home/Lock compatibility path
- Fix a Strict-filter regression exposed by diagnostics: `big boobs`/breast tags were not in the alpha.8 exact block set; bump the cache policy version so those older cached candidates are discarded

The foreground service is intentional: Android requires a visible foreground-service notification for long-lived background execution. WorkManager remains the fallback for process/service recovery and deferred execution.
