# Wallhaven Rotator Android

Android companion to **Wallhaven Rotator**, built around the public SFW Wallhaven API.

> This project is not affiliated with or endorsed by Wallhaven.

## Initial alpha

The `0.1.0-alpha.1` prerelease provides a functional first implementation:

- Android 7.0+ (`minSdk 24`)
- Home screen, lock screen, same image on both, or independent home/lock profiles
- Automatic orientation policy:
  - phone: portrait by default
  - tablet: uses the current tablet orientation in Auto mode, with landscape as the natural default use case
  - explicit Portrait / Landscape overrides
- Wallhaven sources matching the desktop app semantics:
  - Trending = `toplist / 1d`
  - Popular = `toplist / 1M`
  - New = `date_added`
  - Random = `random`
- General / Anime / People / All categories
- Optional Wallhaven search/tag expression per profile
- SFW-only requests (`purity=100`)
- Local anti-repeat history (up to 1,000 Wallhaven IDs)
- Profile-aware cache keys: changing source/category/tags immediately switches to a clean pool
- Cross-pool queue deduplication so independent home/lock profiles do not download the same pending image
- Cache-first rotation with batched refill:
  - target pool: 24 images
  - refill threshold: 6 images
  - API search is used to refill a pool, not on every wallpaper rotation
- WorkManager periodic rotation at 15 min, 30 min, 1 h, 3 h, 6 h, 12 h or 24 h
- Manual "Change now"
- Center-crop and downsample before applying the wallpaper to limit memory pressure
- No telemetry, analytics, ads or tracking endpoint

## Home / lock behavior

Android's `WallpaperManager` is used with `FLAG_SYSTEM` and `FLAG_LOCK` (API 24+). Independent mode maintains separate cache pools and settings for the home and lock screen. Same-image mode downloads once and applies the same bitmap to both destinations.

## Cache strategy

Wallhaven search listings return up to 24 results per page. The app therefore uses a 24-item target pool. At a 15-minute rotation interval, one full pool represents roughly six hours of rotations per active profile. Refill is triggered only when the pool reaches six items or fewer. One API page normally fills a pool; additional pages are queried only when history or another active pool already owns too many of the returned IDs, with a hard cap of four search pages per refill.

Images are downloaded into app-private storage. Once an image is successfully applied it is removed from the queue and its Wallhaven ID enters the anti-repeat history.

## Android scheduling caveat

WorkManager periodic work has a minimum repeat interval of 15 minutes. Android may defer background execution because of Doze, battery optimizations or vendor-specific scheduling. The interval is therefore a requested minimum cadence, not a real-time timer.

## Build

The project pins:

- Android Gradle Plugin 8.13.2
- Gradle 8.13
- Kotlin / Compose Compiler plugin 2.3.21
- `compileSdk` 36.1 / `targetSdk` 36
- Android SDK Platform 36.1 / Build Tools 36.1.0
- JDK 17

Build locally with a compatible Android SDK:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

The included GitHub Actions workflow installs the required Android SDK packages and builds the prerelease APK from source.

## Privacy

Wallhaven Rotator Android contacts Wallhaven only to search for and download wallpapers. GitHub is used only by the repository/build/release workflow. The application contains no project telemetry.

## License

MIT. See [LICENSE](LICENSE).
