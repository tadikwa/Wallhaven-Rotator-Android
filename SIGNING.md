# Android release signing

Wallhaven Rotator Android must use one persistent signing key for every real release.
Android only accepts an in-place APK update when the new APK is signed by a certificate trusted for the installed package.

## Important for the first signed release

`0.1.0-alpha.1` was built by GitHub Actions with a runner-local debug key. It is suitable for functional testing only.
Before installing the first persistently signed build, uninstall that debug alpha once. After that transition, future OTA updates can install over the existing app as long as the same release key is kept.

## Create the key once

Run locally with a JDK installed:

```powershell
keytool -genkeypair -v `
  -keystore wallhaven-rotator-android-release.jks `
  -alias wallhaven-rotator `
  -keyalg RSA `
  -keysize 4096 `
  -validity 10000
```

Back up the `.jks` file and its passwords offline. Never commit the keystore to Git.

## GitHub Actions secrets

Convert the keystore to Base64 in PowerShell:

```powershell
[Convert]::ToBase64String(
  [IO.File]::ReadAllBytes(".\wallhaven-rotator-android-release.jks")
) | Set-Clipboard
```

Create these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64` — Base64 content of the `.jks` file
- `ANDROID_KEYSTORE_PASSWORD` — keystore password
- `ANDROID_KEY_ALIAS` — normally `wallhaven-rotator`
- `ANDROID_KEY_PASSWORD` — key password

The signed-release workflow restores the key only into the ephemeral GitHub Actions runner, builds the release APK, verifies it with `apksigner`, generates OTA metadata and publishes the release assets.

## Key continuity

Losing or changing the signing key prevents normal in-place updates for existing installations. Keep at least two secure offline backups.
