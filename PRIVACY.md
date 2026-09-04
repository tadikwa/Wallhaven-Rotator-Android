# Privacy

Wallhaven Rotator Android is designed without project telemetry, analytics, advertising or tracking.

The application contacts:

- `wallhaven.cc` for SFW wallpaper search metadata;
- Wallhaven image hosts for selected wallpaper files;
- the public GitHub API and this project's GitHub Releases assets for update checks and optional APK downloads.

Automatic update checks are rate-limited locally to at most one check per 24 hours. Manual update checks remain user initiated.

Application settings, cache metadata, wallpaper history and downloaded update files remain in the app's private local storage/cache. Wallpaper and update caches are excluded from Android cloud backup/device transfer rules.
