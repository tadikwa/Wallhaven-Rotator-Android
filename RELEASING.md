# Releasing Wallhaven Rotator Android

## One-time setup

Configure the persistent Android signing key and the four GitHub Actions secrets described in [SIGNING.md](SIGNING.md).

## Before every release

1. update `VERSION` to the intended version name;
2. increment `versionCode` in `app/build.gradle.kts` (it must always increase);
3. update `RELEASE_NOTES.md`;
4. commit and push to `main`;
5. wait for the regular Android validation workflow to pass;
6. open **Actions → Android signed release → Run workflow**.

The release workflow refuses to overwrite an existing tag/release. It builds a signed release APK, verifies the signature, creates the SHA-256 file and `Wallhaven-Rotator-Android-update.json`, then publishes the GitHub Release.

Versions containing `-` (for example `1.0.0-beta.1`) are published as GitHub prereleases. Versions without `-` are published as normal releases and marked latest.

## First signed release only

`0.1.0-alpha.1` was CI debug-signed. Android will not accept a normal in-place upgrade from that build to the new permanent signing identity. Uninstall the debug alpha once, install the first persistently signed APK manually, then OTA updates can continue normally with that same key.
