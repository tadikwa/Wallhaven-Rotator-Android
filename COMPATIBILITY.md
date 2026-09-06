# Compatibility

Wallhaven Rotator targets Android 7.0+ (`minSdk 24`), but API compatibility does not guarantee reliable background execution on every Android manufacturer. Vendors can add their own process, battery and app-launch policies.

## Physically validated device

| Manufacturer | Model | Android | Vendor software | Status |
| --- | --- | --- | --- | --- |
| HONOR | MTN-NX1M / device HNMTN-Q1 | Android 16 / SDK 36 | MagicOS | Validated |

The rotation engine promoted to stable `0.1.0` was physically validated on 6 September 2026 as `0.1.0-alpha.20` on the HONOR device above:

- 52 min 46 s with the Wallhaven Activity out of focus;
- three successful automatic independent Home/Lock rotations;
- two successful rotations on battery;
- a due rotation received while the phone was locked was deferred without consuming the cadence gate;
- automatic recovery occurred after unlock without reopening Wallhaven Rotator;
- one process remained present through 577 process samples during the final burn-in.

Stable `0.1.0` is a version/documentation-only promotion of that validated rotation engine. The publication procedure refuses any change under `app/src/main`.

**No other phone model or Android vendor is formally validated yet.** The application may work correctly elsewhere, but background reliability on another OEM must be considered unverified until tested on real hardware.

## Background reliability checklist

These settings are troubleshooting guidance, not a universal guarantee. Menu names and OEM policies vary.

### Standard Android checks

1. If Android asks for **Alarms & reminders** / exact-alarm access, allow it. Wallhaven Rotator uses `AlarmManager` for its user-selected rotation cadence.
2. If Wallhaven Rotator asks to be excluded from battery optimization, allow the exemption.
3. Do not assume the standard Android battery page is the only relevant control. Some manufacturers add their own autostart, app-launch or background-execution policy.

Android references:

- [Schedule alarms — Android Developers](https://developer.android.com/develop/background-work/services/alarms)
- [Optimize for Doze and App Standby — Android Developers](https://developer.android.com/training/monitoring-device-state/doze-standby)

### HONOR / MagicOS known-good configuration

The configuration validated on the HONOR test phone is:

1. Open **Settings** and search for **App launch** / **Lancement des applications**.
2. Open Wallhaven Rotator.
3. Disable **Manage automatically** / **Gérée automatiquement**.
4. Enable **Auto-launch** / **Lancement automatique**.
5. Enable **Secondary launch** / **Lancement secondaire**.
6. Enable **Run in background** / **Exécution en arrière-plan**.
7. Keep the Android battery-optimization exemption enabled.
8. If background cleanup remains aggressive, HONOR also documents locking the application preview in the Recents screen.

This OEM launch policy is separate from Android's standard battery-optimization exemption. During the investigation, a MagicOS kernel bugreport demonstrated the Wallhaven process inside the OEM refrigerator/freezer while automatic management was enabled. With manual HONOR launch management enabled, the final burn-in completed without that failure.

HONOR reference:

- [An app running in the background restarts when re-accessed — HONOR Support](https://www.honor.com/uk/support/content/en-us00406916/)

## Other manufacturers

No Samsung, Google Pixel, Xiaomi/HyperOS, OnePlus/OxygenOS, Oppo/ColorOS, Motorola or other model is claimed as validated by this project yet.

If automatic rotation works only after bringing the application back to the foreground:

1. confirm exact-alarm access when applicable;
2. confirm the Android battery-optimization exemption;
3. inspect the manufacturer's autostart/app-launch/background-execution controls;
4. collect diagnostics before changing the application architecture;
5. report the exact device model, Android version and vendor software version when opening an issue.

A successful manual rotation does not prove background scheduling on a device. Several natural automatic rotations with the Activity out of focus are the meaningful validation.
