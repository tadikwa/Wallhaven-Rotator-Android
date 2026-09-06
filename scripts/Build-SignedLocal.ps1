param(
    [Parameter(Mandatory)][string]$GradlePath,
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$AndroidSdk,
    [Parameter(Mandatory)][string]$SigningDirectory,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [ValidateSet('Debug', 'Release')][string]$Variant = 'Release'
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$signingRoot = (Resolve-Path -LiteralPath $SigningDirectory).Path
if ($signingRoot.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or $signingRoot -eq $repoRoot) {
    throw 'The private signing directory must be outside the Git checkout.'
}
$config = Get-Content -LiteralPath (Join-Path $signingRoot 'signing-secrets.json') | ConvertFrom-Json
$environment = @{
    JAVA_HOME=$JavaHome; ANDROID_HOME=$AndroidSdk;
    ANDROID_RELEASE_STORE_FILE=(Join-Path $signingRoot $config.keystore);
    ANDROID_RELEASE_STORE_PASSWORD=$config.storePassword;
    ANDROID_RELEASE_KEY_ALIAS=$config.alias;
    ANDROID_RELEASE_KEY_PASSWORD=$config.keyPassword
}
foreach ($entry in $environment.GetEnumerator()) {
    if ([string]::IsNullOrWhiteSpace($entry.Value)) { throw "Missing local signing/build setting: $($entry.Key)" }
}
$savedEnvironment = @{}
foreach ($key in $environment.Keys) { $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
Push-Location $repoRoot
try {
    foreach ($entry in $environment.GetEnumerator()) { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process') }
    & $GradlePath --no-daemon ':app:testDebugUnitTest' ':app:assembleDebug' ":app:test${Variant}UnitTest" ":app:assemble${Variant}"
    if ($LASTEXITCODE -ne 0) { throw 'Gradle validation failed.' }
    $variantLower = $Variant.ToLowerInvariant()
    $apk = Join-Path $repoRoot "app/build/outputs/apk/$variantLower/app-$variantLower.apk"
    $apksigner = Join-Path $AndroidSdk 'build-tools/36.1.0/apksigner.bat'
    $certificate = & $apksigner verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $actual = ($certificate | Select-String '^Signer #1 certificate SHA-256 digest: (.*)$').Matches.Groups[1].Value.Trim()
    $expected = (Get-Content -LiteralPath (Join-Path $repoRoot 'SIGNING_CERTIFICATE_SHA256') -Raw).Trim()
    if ($actual -ne $expected) { throw 'APK signing certificate does not match the persistent identity.' }
    $version = (Get-Content -LiteralPath (Join-Path $repoRoot 'VERSION') -Raw).Trim()
    $aapt = Join-Path $AndroidSdk 'build-tools/36.1.0/aapt.exe'
    $badging = & $aapt dump badging $apk
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect APK identity.' }
    $identity = [regex]::Match($badging[0], "^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'")
    if (-not $identity.Success -or $identity.Groups[1].Value -ne 'fr.tadikwa.wallhavenrotator' -or $identity.Groups[3].Value -ne $version) {
        throw 'APK package/version does not match the release identity.'
    }
    $versionCode = [int]$identity.Groups[2].Value
    $sdkIdentity = [regex]::Match(($badging -join "`n"), "(?m)^sdkVersion:'([0-9]+)'$")
    if (-not $sdkIdentity.Success) { throw 'Cannot inspect APK minimum SDK.' }
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $artifactName = "Wallhaven-Rotator-Android-v$version-$variantLower.apk"
    $artifact = Join-Path $OutputDirectory $artifactName
    Copy-Item -LiteralPath $apk -Destination $artifact
    $sha = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
    "$sha  $artifactName" | Set-Content -LiteralPath "$artifact.sha256" -Encoding ascii
    if ($Variant -eq 'Release') {
        [ordered]@{schema=1; applicationId=$identity.Groups[1].Value; versionName=$version; versionCode=$versionCode; minSdk=[int]$sdkIdentity.Groups[1].Value; apk=$artifactName; sha256=$sha} |
            ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'Wallhaven-Rotator-Android-update.json') -Encoding utf8NoBOM
    }
    $head = git rev-parse HEAD
    $dirty = @(git status --porcelain).Count -gt 0
    [ordered]@{applicationId=$identity.Groups[1].Value; version=$version; versionCode=$versionCode; variant=$Variant; head=$head; dirty=$dirty; apk=$artifactName; sha256=$sha; certificateSha256=$actual} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'build-provenance.json')
    Write-Output "APK: $artifact"
    Write-Output "SHA-256: $sha"
    Write-Output "Certificate SHA-256: $actual"
    Write-Output "Source: $head (dirty=$dirty)"
} finally {
    Pop-Location
    foreach ($entry in $savedEnvironment.GetEnumerator()) { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process') }
    $config = $null
    $environment.Clear()
}
