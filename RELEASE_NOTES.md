# 0.1.0-alpha.18

- Roll back the automatic `WallpaperManager.setStream()` transport introduced in alpha.17 after HONOR/MagicOS reverted both Home and Lock to the OEM default wallpapers after a background apply.
- Restore the alpha.16 `setBitmap()` transport for automatic Home/Lock writes, including the proven HONOR order: Lock candidate to SYSTEM|LOCK, then immediate Home restore to SYSTEM.
- Keep alpha.17's single automatic owner: only the exact-alarm foreground service may call WallpaperManager; WorkManager only repairs/reschedules AlarmManager.
- Keep unlocked/interactive gating, five-minute deferred checks, five-minute partial wake lock, two-minute stuck-Binder process fail-safe, monotonic cadence and success-only cadence commit.
- Preserve Strict v5 filtering and all cache behavior.

# 0.1.0-alpha.17

- Automatic wallpaper writes now have a single owner: the exact-alarm foreground service. WorkManager only repairs/reschedules AlarmManager and never calls WallpaperManager.
- Automatic writes use WallpaperManager.setStream() with a device-sized high-quality JPEG prepared before entering WallpaperManager, avoiding the setBitmap serialization path that stalled for minutes/hours on MagicOS.
- Automatic HONOR writes perform no WallpaperManager Binder reads before or after apply; only the required Lock SYSTEM|LOCK stream followed immediately by Home SYSTEM stream.
- Hold the foreground-service partial wake lock for up to five minutes while the encoded-stream apply is active.
- Add a two-minute hard fail-safe for an OEM-stuck WallpaperManager Binder call: the app process is restarted while the persisted due gate and system watchdog alarm remain intact for retry.
- Reduce sleeping deferred rechecks from one minute to five minutes.
- Preserve alpha.16 unlocked-device gate, monotonic cadence, success-only cadence commit, HONOR independent-pair ordering and Strict v5 filtering.

# 0.1.0-alpha.16

- Automatic WallpaperManager writes now require the display to be interactive AND the device/keyguard to be unlocked.
- Re-check readiness after bitmap preparation, immediately before WallpaperManager Binder calls.
- Preserve the overdue cadence when the device locks during preparation; defer instead of treating it as a failed run.
- Automatic HONOR pair writes no longer call getWallpaperId before/after the two required setBitmap calls.
- Keep alpha.15 monotonic elapsedRealtime scheduling, sleep deferral, success-only cadence commit and Strict v5 filtering.

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
