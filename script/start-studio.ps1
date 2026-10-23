param([string]$StudioExe)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$workspace=Split-Path $repo -Parent
if (!$StudioExe) { $StudioExe=Join-Path $workspace 'env/ide/android-studio/bin/studio64.exe' }
if (!(Test-Path -LiteralPath $StudioExe)) { throw 'Set -StudioExe to the official Android Studio executable.' }
. (Join-Path $PSScriptRoot 'android-env.ps1')
$privateEnv=Join-Path $workspace 'env'
$properties=Join-Path $privateEnv 'studio.properties'
$base=$privateEnv.Replace('\','/')
@("idea.config.path=$base/studio-config","idea.system.path=$base/studio-system","idea.plugins.path=$base/studio-plugins","idea.log.path=$base/studio-log") | Set-Content -LiteralPath $properties -Encoding utf8
$env:STUDIO_PROPERTIES=$properties
Start-Process -FilePath $StudioExe -ArgumentList @('"'+$repo+'"') -WindowStyle Hidden
