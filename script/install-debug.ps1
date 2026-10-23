param([Parameter(Mandatory=$true)][string]$Device)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
if (!$env:ANDROID_HOME) { throw 'Load android-env.ps1 first.' }
$adb=Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$state=& $adb -s $Device get-state
if ($LASTEXITCODE -ne 0 -or $state -ne 'device') { throw 'Device must be connected and authorized.' }
$apk=Join-Path $repo 'build/output/app/outputs/apk/debug/app-debug.apk'
if (!(Test-Path -LiteralPath $apk)) { throw 'Build the debug APK first.' }
& $adb -s $Device install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Installation failed. Preserve device data and inspect the error.' }
& $adb -s $Device shell am start -n io.whaletodo.app/.MainActivity
