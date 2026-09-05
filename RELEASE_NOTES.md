## 0.1.0-alpha.15

Sleep-safe automatic rotation and monotonic cadence hardening for HONOR/MagicOS.

- Base automatic cadence on `SystemClock.elapsedRealtime()` and `AlarmManager.ELAPSED_REALTIME_WAKEUP` for relative "every N minutes" scheduling.
- Never call `WallpaperManager` while the device is non-interactive. A due wake-up received with the screen off is converted to a non-wakeup elapsed alarm so delivery resumes after a natural device wake.
- Keep the durable gate overdue while sleeping; missed sleeping intervals are not replayed and are not consumed.
- Acquire the in-process rotation lock before inspecting the cadence gate. Busy automatic callers can no longer advance the schedule without changing a wallpaper.
- Split automatic cadence into inspect -> apply -> commit. The next interval is persisted only after a successful wallpaper transition.
- Arm a watchdog alarm before automatic app-owned work. If the process dies or `WallpaperManager` stalls, the same overdue gate is retried later rather than lost.
- Preserve newer manual/settings deadlines if they change while an automatic transition is running.
- Use a lightweight automatic wallpaper apply path: no transient deep color listeners, submitted-preview hashing/compression or deep snapshots during background rotations.
- Keep the proven HONOR independent-pair order unchanged: Lock candidate to SYSTEM|LOCK, then immediate Home restore to SYSTEM.
- WorkManager remains a fallback only. Its automatic path also defers while non-interactive and uses the same lock-before-gate / success-only-commit semantics.
- Migrate and cancel the alpha.13 RTC alarm identity, then use versioned alpha.15 monotonic PendingIntents.
- Preserve Strict content policy v5 and all alpha.13 filtering behavior.

### Root cause confirmed by overnight alpha.13 trace

AlarmManager successfully woke the app without UI focus and changed wallpapers automatically while the device was usable. During long screen-off periods, however, HONOR's `WallpaperManager.setBitmap()` could block for many minutes. While that old transition held the rotation lock, subsequent alarm/WorkManager callers could still claim future cadence slots before discovering the engine was busy. Alpha.15 avoids entering WallpaperManager while non-interactive and advances cadence only after a successful transition.
