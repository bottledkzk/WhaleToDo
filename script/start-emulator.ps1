param([string]$Name='WhaleToDo_API36', [int]$Port=5580, [ValidateSet('auto','host','software','swiftshader')][string]$Gpu='swiftshader')
$ErrorActionPreference='Stop'
if (!$env:ANDROID_HOME -or !$env:ANDROID_AVD_HOME) { throw 'Load android-env.ps1 first.' }
if ($Name -notmatch '^[A-Za-z][A-Za-z0-9_]{0,40}$' -or $Port -lt 5554 -or $Port -gt 5682 -or $Port%2 -ne 0) { throw 'Invalid AVD name or console port.' }
$adb=Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if ((& $adb devices) -match "emulator-$Port") { throw 'This port already has a device. Reuse it.' }
$exe=Join-Path $env:ANDROID_HOME 'emulator/emulator.exe'
Start-Process -FilePath $exe -ArgumentList @('-avd',$Name,'-port',"$Port",'-memory','4096','-cores','4','-no-snapshot','-gpu',$Gpu,'-feature','-Vulkan') -WindowStyle Hidden
