param(
    [Parameter(Mandatory=$true)][string]$Keystore,
    [Parameter(Mandatory=$true)][string]$PasswordFile,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string]$Alias = 'whaletodo-preview',
    [string]$BuildTools = '35.0.0',
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$sdk = $env:ANDROID_SDK_ROOT
if (!$sdk) { throw 'Run android-env.ps1 first.' }
$signer = Join-Path $sdk "build-tools/$BuildTools/apksigner.bat"
$aapt = Join-Path $sdk "build-tools/$BuildTools/aapt.exe"
$expected = (Get-Content -LiteralPath (Join-Path $repo 'release/preview-certificate.sha256') -Raw).Trim().ToLowerInvariant()
if ($expected -notmatch '^[a-f0-9]{64}$') { throw 'Signing certificate pin is missing.' }
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Use a new output directory; published artifacts must never be overwritten.' }
$source = (& git -C $repo rev-parse HEAD).Trim()
if (& git -C $repo status --porcelain --untracked-files=normal) { throw 'Commit the source before building distribution artifacts.' }
$secret = ConvertTo-SecureString ((Get-Content -LiteralPath $PasswordFile -Raw).Trim())
$env:WHALETODO_SIGN_PASSWORD = [System.Net.NetworkCredential]::new('', $secret).Password
Push-Location $repo
try {
    $buildArgs = @(':core:test', ':app:assemblePublicPreview', '--console=plain')
    if ($Offline) { $buildArgs += '--offline' }
    & ./gradlew.bat @buildArgs
    if ($LASTEXITCODE -ne 0) { throw 'Preview build or core tests failed.' }
    $unsigned = Join-Path $repo 'build/output/app/outputs/apk/publicPreview/app-publicPreview-unsigned.apk'
    $badging = (& $aapt dump badging $unsigned) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $badging -notmatch "package: name='io.whaletodo.app.preview' versionCode='([0-9]+)' versionName='([^']+)'") { throw 'Unexpected preview package metadata.' }
    $code = [int]$Matches[1]; $version = $Matches[2]
    if ($badging -match 'application-debuggable' -or $version -notmatch '^\d+\.\d+\.\d+-preview\.\d+$') { throw 'Distribution APK must be a non-debuggable preview.' }
    New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
    $out = (Resolve-Path -LiteralPath $OutputDirectory).Path
    $asset = "WhaleToDo-$version.apk"
    $apk = Join-Path $out $asset
    & $signer sign --ks $Keystore --ks-key-alias $Alias --ks-pass env:WHALETODO_SIGN_PASSWORD --key-pass env:WHALETODO_SIGN_PASSWORD --out $apk $unsigned
    if ($LASTEXITCODE -ne 0) { throw 'Signing failed.' }
    $verification = (& $signer verify --verbose --print-certs $apk 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $verification -notmatch 'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)' -or $Matches[1].ToLowerInvariant() -ne $expected) { throw 'Signature verification or certificate pin failed.' }
    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $asset" | Set-Content -LiteralPath (Join-Path $out 'SHA256SUMS.txt') -Encoding ascii
    $manifest = [ordered]@{version=$version;version_code=$code;tag="v$version";package='io.whaletodo.app.preview';min_android='8.0';min_sdk=26;target_sdk=36;debuggable=$false;asset=$asset;bytes=(Get-Item -LiteralPath $apk).Length;sha256=$hash;certificate_sha256=$expected;source_commit=$source;built_at_utc=[DateTime]::UtcNow.ToString('o');channel='preview';capabilities=@('offline_tasks','protected_scheduling','calendar_preview','local_diary');online_account_agent='pending_network_bridge'}
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'release.json') -Encoding utf8
    Copy-Item -LiteralPath (Join-Path $repo "release/$version.md") -Destination (Join-Path $out 'RELEASE-NOTES.md')
    Write-Host "Signed preview ready: $apk"
    Write-Host "SHA256: $hash"
} finally {
    Remove-Item Env:WHALETODO_SIGN_PASSWORD -ErrorAction SilentlyContinue
    Pop-Location
}
