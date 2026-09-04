## 0.1.0-alpha.11

System-alarm scheduler for HONOR/MagicOS background reliability.

- Replace the long-lived Handler-timed foreground service with a system-owned AlarmManager deadline.
- Use `setExactAndAllowWhileIdle()` when Android grants the user-facing `SCHEDULE_EXACT_ALARM` special access.
- Fall back to `setAndAllowWhileIdle()` when exact-alarm access is unavailable, while retaining WorkManager as a second fallback.
- Add an explicit alarm BroadcastReceiver so Android can recreate the app process after MagicOS has killed it.
- Launch a short-lived foreground service only while a due wallpaper rotation is actually running, then stop it.
- Hold a bounded partial wake lock during that one rotation so the CPU cannot fall asleep mid-apply.
- Keep the persistent due gate: AlarmManager and WorkManager share the same claim and cannot create catch-up bursts.
- Re-arm AlarmManager after manual changes, WorkManager fallback claims, boot/package replacement and exact-alarm permission changes.
- On Save, open Android's “Alarms & reminders” special-access screen when exact alarms are not yet allowed.
- Extend diagnostics with exact-alarm permission, scheduled mode and due time.
- Preserve alpha.10 cache warming/manual priority, Strict policy v4 and HONOR independent Home/Lock workaround.

Root cause observed on the HONOR MTN-NX1M: the alpha.10 foreground service process disappeared while backgrounded; no in-process Handler callback could fire. WorkManager then ran only when the app process became active again. AlarmManager moves the deadline into the Android system process instead of relying on our app remaining alive.
