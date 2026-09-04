## 0.1.0-alpha.6

Controlled follow-up to the alpha.5 on-device diagnostics.

- On HONOR devices in independent Home/Lock mode, test the original combined `FLAG_SYSTEM | FLAG_LOCK` write for the Lock candidate, then restore the independent Home candidate with `FLAG_SYSTEM`.
- Keep deep wallpaper ID/color/broadcast diagnostics around both the combined write and the Home restore.
- Strengthen suggestive-content filtering without over-constraining Wallhaven search queries.
- `Moins suggestif` and `Strict` now reject `schoolgirl`/`loli` at query level and verify the detailed wallpaper tags before downloading.
- `Strict` uses the same broad query as `Moins suggestif`, then performs a stricter metadata pass instead of stacking many negative search terms.
- Add a local Wallhaven API rate limiter with headroom below the documented 45 requests/minute.
- Improve empty-profile errors so they identify Source / Category / content-filter instead of blaming a generic mode.
- Keep the alpha.5 deep diagnostics and submitted Home/Lock preview export.

This remains a validation alpha. No release is created.
