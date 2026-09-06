# Releasing Wallhaven Rotator Android

Signing is local only. Never upload the private key, passwords, local signing configuration, device backups or raw ADB reports to GitHub.

1. Diagnose and validate the actual change. For HONOR background rotation, require several automatic rotations with the Activity out of focus; a manual rotation is insufficient.
2. Update `VERSION`, `versionCode`/`versionName` in `app/build.gradle.kts` and the release notes.
3. Build with `scripts/Build-SignedLocal.ps1` and the existing private signing directory, following `SIGNING.md`. Verify unit tests, APK identity, pinned certificate and device behavior.
4. Commit only named source/documentation files. Check remote `main` again and integrate without rewriting history.
5. Rebuild the exact final commit. Require clean source provenance and recheck the APK's SHA-256 and certificate. An in-place device install uses `adb -s <explicit serial> install -r <apk>`.
6. Only after release approval/authorization, upload the signed release APK and its public SHA-256/update manifest to the release for that exact commit. Do not overwrite an existing release.

The update manifest is `Wallhaven-Rotator-Android-update.json`, with schema 1 and the fields `applicationId`, `versionName`, `versionCode`, `minSdk`, `apk` (asset filename) and `sha256`. The package is `fr.tadikwa.wallhavenrotator`; minSdk is 24. The release APK filename is `Wallhaven-Rotator-Android-v<VERSION>-release.apk`.

Versions containing `-` are prereleases. The tag is `v<VERSION>`. Release assets contain no keystore or local provenance paths.

The public CI is a tests/compilation check. Signing and publishing use the local APK, so exhausted Actions minutes do not block local validation.
