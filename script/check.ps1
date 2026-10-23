$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
Push-Location $repo
try {
    & .\gradlew.bat :core:test :app:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Build or tests failed.' }
    $apk = Join-Path $repo 'build/output/app/outputs/apk/debug/app-debug.apk'
    Get-FileHash -LiteralPath $apk -Algorithm SHA256
} finally { Pop-Location }
