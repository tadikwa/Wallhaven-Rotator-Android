## 0.1.0-alpha.8

Strict-content filtering hardening before persistent signing / real OTA.

- Bump the content-filter policy version so older, looser cached pools are discarded automatically.
- Keep **Reduced** compatible with ordinary women/anime subjects while blocking explicit adult/suggestive metadata such as `pornstar`, `Tushy`, nudity/erotic/fetish terms, `ass`/`butt` and related high-signal tags.
- Make **Strict** deliberately conservative: in addition to adult/suggestive metadata it rejects female-focused tags (`women`, `girls`, `anime girls`, `video game girls`, `actress`, etc.), even when Wallhaven does not provide an explicit sexual tag.
- Keep non-female anime (for example `anime boys`) and scenery/abstract subjects eligible in Strict.
- Increase the metadata-inspection budget from 12 to 16 candidates per refill to compensate for the stricter local rejection rate.
- If Wallhaven returns zero results because query-side negative terms make a narrow listing collapse, retry the same SFW listing without the automatic negatives and enforce Reduced/Strict locally from detailed wallpaper tags.
- Improve diagnostics with explicit broad-search fallback counters and the exact tag labels that caused rejection.
- No changes to Home/Lock compatibility, independent wallpaper selection, scheduling, cache limits, settings keys or OTA state.

Strict remains metadata-driven rather than image-recognition-driven. It intentionally favours false positives over allowing female-focused wallpapers when the user selects the conservative Strict mode.
