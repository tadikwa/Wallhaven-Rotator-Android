## 0.1.0-alpha.5

Deep lockscreen diagnostic build. This release intentionally avoids another speculative lockscreen workaround and instead records stronger public-API evidence around each wallpaper write.

- Keep the alpha.4 rotation, rate-limit, cache and suggestive-content behavior unchanged
- Save the exact cropped bitmap submitted to Home/Lock as bounded diagnostic previews (one file per destination)
- Record SHA-256 of the source wallpaper and submitted preview
- Record expected `WallpaperColors` derived from the submitted bitmap
- Capture wallpaper ID, `WallpaperColors`, wallpaper-info state, keyguard state, screen interactive state and desired wallpaper dimensions before/after writes
- For Lock writes, capture snapshots immediately, after 500 ms and after 2.5 s to detect quick replacement/reversion
- Temporarily listen for `ACTION_WALLPAPER_CHANGED`, screen on/off/user-present broadcasts and `OnColorsChangedListener` callbacks around the write
- Attach the latest submitted Home/Lock previews alongside the text report when sharing diagnostics
- Do not request broad storage access just to read the Android wallpaper back

The goal of alpha.5 is diagnosis, not to assume an HONOR/MagicOS cause. Android 14+ prevents ordinary apps from reading the real wallpaper bitmap back through `getWallpaperFile()` without broad/privileged storage access, so the app uses IDs, colors, callbacks, state snapshots and the exact submitted image instead.
