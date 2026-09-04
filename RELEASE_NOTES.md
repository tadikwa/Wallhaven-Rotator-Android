## 0.1.0-alpha.13

Versioned AlarmManager delivery + fail-closed Strict content filtering.

### Background rotation reliability

- Give every AlarmManager deadline a persisted unique schedule ID and encode it in the PendingIntent identity.
- Cancel the legacy unversioned alpha.11 alarm during every new schedule, and cancel the previous versioned identity before replacing a deadline.
- Detect stale AlarmManager broadcasts instead of treating every `AUTO_ROTATION_ALARM` as the currently active deadline.
- Reuse an early/stale wake-up when the durable gate is less than four minutes away: a short foreground service + bounded wake lock waits until the real due time and then rotates, instead of attempting a second `setExactAndAllowWhileIdle()` inside Android's idle-alarm quota.
- Ignore stale alarms that are far from the real deadline; implausibly early current alarms are re-registered.
- Remove the double AlarmManager schedule previously produced by **Change now** (`configure()` followed immediately by manual deferral). Manual priority now rebuilds the WorkManager fallback and schedules exactly one alarm.
- Bump the scheduler configuration version to 13 so alpha.13 performs one clean migration from the alpha.11 alarm identity.
- Expand diagnostics with alarm schedule IDs, stale/current classification, remaining time, early-wait lifecycle and wake reason.

### Strict filtering

- Include the alpha.12 Strict policy v5 changes so users can jump directly from alpha.11 to alpha.13.
- Discard older Strict cache pools automatically.
- Keep Standard and Reduced behavior unchanged.
- Strict fails closed for female-focused metadata and ambiguous/sparse Anime or People subjects.
- Add hard sexual/exposure concepts plus a weighted score for weaker suggestive cues.
- Record Wallhaven category, Strict score and exact rejection reasons in diagnostics.

The background fix targets the observed HONOR trace where an AlarmManager broadcast arrived about 108 seconds before the newer durable gate. Android also documents a roughly nine-minute minimum dispatch interval for allow-while-idle alarms while Doze is active, so simply scheduling another allow-while-idle alarm a minute later is not a reliable correction path.
