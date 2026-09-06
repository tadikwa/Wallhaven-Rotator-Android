# Persistent local Android signing

All installable APKs are signed on the owner's PC with one persistent key. No private key, password, signing configuration or device diagnostic is uploaded to GitHub, including Actions secrets.

The public certificate fingerprint is pinned in `SIGNING_CERTIFICATE_SHA256`. An APK's signing certificate is public information; it is not the private key.

## Private storage

Keep the keystore and `signing-secrets.json` in an access-restricted directory outside this Git checkout. The local build script reads these fields from that JSON file: `keystore` (filename), `alias`, `storePassword`, `keyPassword`. Do not put their values in command-line arguments, logs, Git files or GitHub settings.

Back up that entire private directory to secure offline storage. Reuse it for every update; never generate a replacement key for a new version. Losing it prevents normal updates of existing installations.

## Build locally

Use JDK 17, Gradle 8.13 and Android SDK platform 36.1 with build-tools 36.1.0. Run `scripts/Build-SignedLocal.ps1` with the local paths supplied through `-GradlePath`, `-JavaHome`, `-AndroidSdk`, `-SigningDirectory` and `-OutputDirectory`. The output directory should be outside the Git checkout. The default variant is Release; `-Variant Debug` is available for physical diagnostics and uses the same persistent certificate.

The script runs unit tests and builds the APK, verifies its signature against the pinned public fingerprint, then writes the APK, its SHA-256 and local build provenance. For Release builds it also writes the public update manifest consumed by the app. Check `dirty=false` before distributing a release. Temporary Gradle environment variables remain process-local; on Windows a short writable TEMP/TMP path can be needed for Gradle's loopback connection. Run the script with PowerShell 7.

GitHub Actions only validates tests/compilation and uploads unit test results. It does not distribute the runner's temporary-debug-key APK. The release workflow provides local-signing instructions and has no signing secrets or write permission.

## One-time migration from old debug builds

Old CI APKs were signed with ephemeral runner debug keys. They cannot be updated in place by the new permanent identity. A one-time reinstall requires the user's explicit agreement and a backup of recoverable data. After migration, verify an `adb install -r` update with the same certificate and preserved configuration. Future debug and release APKs built with the local script share that identity.
