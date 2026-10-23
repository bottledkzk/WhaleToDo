param([string]$SdkRoot, [string]$JavaHome)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$workspace = Split-Path $repo -Parent
if (!$SdkRoot) { $SdkRoot = Join-Path $workspace 'env/android-sdk' }
if (!$JavaHome) { $JavaHome = $env:JAVA_HOME }
if (!(Test-Path -LiteralPath (Join-Path $SdkRoot 'cmdline-tools/latest/bin/sdkmanager.bat'))) { throw 'Set -SdkRoot to an installed Android SDK.' }
if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'Set -JavaHome to JDK 21.' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:ANDROID_AVD_HOME = Join-Path $workspace 'env/avd'
$env:ANDROID_USER_HOME = Join-Path $workspace 'env/android-user'
$env:GRADLE_USER_HOME = Join-Path $workspace 'env/gradle-cache'
$env:Path = "$JavaHome/bin;$SdkRoot/platform-tools;$SdkRoot/emulator;$SdkRoot/cmdline-tools/latest/bin;" + $env:Path
"sdk.dir=$($SdkRoot.Replace('\','/'))" | Set-Content -LiteralPath (Join-Path $repo 'local.properties') -Encoding utf8
Write-Host 'Android process environment is ready.'
