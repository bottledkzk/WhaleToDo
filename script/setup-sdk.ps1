param([string]$SdkRoot, [switch]$WithEmulator)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$workspace=Split-Path $repo -Parent
if (!$SdkRoot) { $SdkRoot=Join-Path $workspace 'env/android-sdk' }
$tools=Join-Path $SdkRoot 'cmdline-tools/latest'
if (!(Test-Path -LiteralPath (Join-Path $tools 'bin/android.exe'))) {
    $downloadDir=Join-Path $workspace '.tmp'
    New-Item -ItemType Directory -Force -Path $downloadDir | Out-Null
    $zip=Join-Path $downloadDir 'commandlinetools-win-16111833_latest.zip'
    & curl.exe --fail --location --retry 2 --output $zip 'https://dl.google.com/android/repository/commandlinetools-win-16111833_latest.zip'
    if ($LASTEXITCODE -ne 0) { throw 'Official CLI download failed.' }
    if ((Get-FileHash -LiteralPath $zip).Hash.ToLower() -ne 'e5885e2e59038c0778a85fc19f01de7ce7c567c105553f7617b936b1e0e87b2b') { throw 'CLI checksum mismatch.' }
    $stage=Join-Path $downloadDir ('sdk-stage-'+[guid]::NewGuid().ToString('N'))
    Expand-Archive -LiteralPath $zip -DestinationPath $stage
    New-Item -ItemType Directory -Force -Path (Split-Path $tools -Parent) | Out-Null
    Move-Item -LiteralPath (Join-Path $stage 'cmdline-tools') -Destination $tools
}
. (Join-Path $PSScriptRoot 'android-env.ps1') -SdkRoot $SdkRoot
$cli=Join-Path $tools 'bin/android.exe'
foreach ($package in @('platform-tools','platforms/android-36','build-tools/35.0.0')) {
    & $cli --no-metrics --sdk=$SdkRoot sdk install $package
    if ($LASTEXITCODE -ne 0) { throw "SDK package failed: $package" }
}
if ($WithEmulator) {
    foreach ($package in @('emulator','system-images/android-36/google_apis/x86_64')) {
        & $cli --no-metrics --sdk=$SdkRoot sdk install $package
        if ($LASTEXITCODE -ne 0) { throw "SDK package failed: $package" }
    }
}
