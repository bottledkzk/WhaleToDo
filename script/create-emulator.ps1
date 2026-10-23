param([string]$Name='WhaleToDo_API36')
$ErrorActionPreference='Stop'
if ($Name -notmatch '^[A-Za-z][A-Za-z0-9_]{0,40}$') { throw 'Use a simple AVD name.' }
if (!$env:ANDROID_HOME -or !$env:ANDROID_AVD_HOME) { throw 'Load android-env.ps1 first.' }
$manager=Join-Path $env:ANDROID_HOME 'cmdline-tools/latest/bin/avdmanager.bat'
$avd=Join-Path $env:ANDROID_AVD_HOME ($Name+'.avd')
if (Test-Path -LiteralPath $avd) { throw 'AVD exists. Preserve it or use another name.' }
New-Item -ItemType Directory -Force -Path $env:ANDROID_AVD_HOME | Out-Null
'no' | & $manager create avd --name $Name --package 'system-images;android-36;google_apis;x86_64' --device pixel_7 --path $avd
if ($LASTEXITCODE -ne 0) { throw 'AVD creation failed.' }
$config=Join-Path $avd 'config.ini'
$text=Get-Content -Raw -LiteralPath $config
$text=$text -replace '(?m)^hw.ramSize=.*','hw.ramSize=4096' -replace '(?m)^hw.cpu.ncore=.*','hw.cpu.ncore=4' -replace '(?m)^hw.gpu.enabled=.*','hw.gpu.enabled=yes'
Set-Content -LiteralPath $config -Value $text -Encoding ascii
& (Join-Path $env:ANDROID_HOME 'emulator/emulator.exe') -accel-check
Write-Host "AVD ready: $Name"
