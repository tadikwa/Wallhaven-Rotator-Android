## 0.1.0-alpha.4

Reliability fix based on exported on-device diagnostics.

- Fix independent Home/Lock ordering: both destinations are prepared before application
- Remove inline Home refill that previously delayed Lock application by tens of seconds
- Empty pools fetch one image immediately; full replenishment runs asynchronously afterwards
- Serialize rotations and cache mutations/refills inside the app process
- Use a unique KEEP preload after rotations to prevent concurrent refill storms
- Stop a refill after HTTP 429, DNS or socket failures instead of trying every remaining image
- Automatic WorkManager failures wait for the next scheduled opportunity instead of immediate retry loops
- Reduce ready pool from 24 to 8 images per profile, refill threshold from 6 to 2, and search-page cap from 4 to 2
- Fix Strict suggestive-content filtering by removing undocumented multi-word brace exclusions
- Keep Strict exclusions to Wallhaven's documented `-tagname` query syntax
- Retain the bounded 100 / 250 / 500 MiB cache and anti-repeat history

The tag-based suggestive-content modes remain best-effort: Wallhaven tagging quality determines what can be filtered without image recognition.
