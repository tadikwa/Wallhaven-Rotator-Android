## 0.1.0-alpha.7

Independent-wallpaper stabilization after validating the HONOR compatibility path.

- Keep the HONOR combined SYSTEM|LOCK compatibility write that makes the visible lockscreen refresh.
- Pre-decode both Home and Lock bitmaps before either WallpaperManager write.
- Restore the independent Home bitmap immediately after the combined Lock write, with no 500 ms / 2.5 s diagnostic sleeps in the normal compatibility path.
- Preserve lightweight diagnostics and submitted Home/Lock preview images without intentionally extending the temporary combined state.
- Enforce that independent Home and Lock candidates never use the same Wallhaven ID in one rotation.
- Delay the first automatic WorkManager rotation by the configured interval after settings are saved; saving now preloads only.
- Keep separate Home/Lock pools, global queued-ID deduplication and anti-repeat history.
- No changes to stored settings keys: normal in-place signed updates preserve the existing SharedPreferences configuration.

Note: current CI validation APKs remain debug-signed. A persistent signing key is required before true OTA/in-place upgrades can preserve app data across builds.
