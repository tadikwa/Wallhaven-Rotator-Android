## 0.1.0-alpha.9

Background execution and manual-priority reliability pass.

- Keep WorkManager as the background rotation engine; the UI does not need to stay focused
- Reset periodic cadence on Save with `CANCEL_AND_REENQUEUE`, with the first automatic change after the full selected interval
- Preload active destinations in two phases: first guarantee one ready image per destination, then fill reserves to eight
- Give **Change now** priority over cache warming by cooperatively interrupting an active preload
- Cancel the current unique preload on manual priority and resume a fresh preload after the visible manual rotation
- Add cooperative stop checks between Wallhaven search, metadata inspection and image downloads
- Add `CONNECTED` network constraint to background preloads while keeping wallpaper rotation itself cache-capable offline
- Add scheduler/preload diagnostics for cadence, warm completion and manual-priority interruption
- Preserve the alpha.8 Strict content policy, independent HONOR Home/Lock compatibility path, bounded cache and settings persistence

WorkManager's 15-minute value remains a minimum requested cadence. Android may defer execution because of Doze, battery policy or vendor scheduling, but the application UI/process does not need to be in the foreground.
