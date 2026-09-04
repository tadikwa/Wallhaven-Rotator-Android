# Security

Please report security issues privately to the repository owner rather than opening a public issue containing exploit details.

Wallhaven content is handled as image data and stored in application-private storage. The application does not request root or device-owner privileges.

## OTA update validation

Before an APK update is handed to Android's package installer, Wallhaven Rotator Android verifies:

1. the release-provided SHA-256 digest;
2. the APK application ID (`fr.tadikwa.wallhavenrotator`);
3. the APK `versionCode` against the OTA manifest;
4. the APK signing certificate against the currently installed application.

The application never executes an APK directly. Installation is delegated to Android's package installer and requires the normal user confirmation / "Install unknown apps" permission where Android requires it.

Release signing uses a persistent private key stored only as encrypted GitHub Actions secrets and restored into an ephemeral runner during release builds. See [SIGNING.md](SIGNING.md).
