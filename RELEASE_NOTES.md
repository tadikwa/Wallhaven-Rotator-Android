## 0.1.0-alpha.3

Validation update focused on content filtering and bounded storage use.

- Per-profile suggestive-content modes: Standard, Reduced and Strict
- Tag-based exclusion policy that keeps Anime available instead of requiring `-anime`
- Content-filter changes invalidate the affected cache pool automatically
- Global wallpaper-cache cap: 100 / 250 / 500 MiB, with 250 MiB default
- Automatic cleanup of inactive pools, missing queue entries and orphan files
- Cache enforcement during refills so storage cannot grow without bound
- Refill stops near the configured disk budget to avoid download/eviction churn
- Cache size shown in the UI and a manual **Clear cache** action
- Diagnostic exports now include content-filter and cache-limit settings plus cache bytes
- Retains alpha.2 manual-rotation, separate lockscreen application and diagnostics fixes

The suggestive-content filter is best-effort and depends on Wallhaven tags; it is not image recognition.
