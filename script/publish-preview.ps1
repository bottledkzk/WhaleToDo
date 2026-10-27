param([Parameter(Mandatory=$true)][string]$ArtifactDirectory)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$dir = (Resolve-Path -LiteralPath $ArtifactDirectory).Path
$m = Get-Content -LiteralPath (Join-Path $dir 'release.json') -Raw | ConvertFrom-Json
if ($m.tag -cne "v$($m.version)" -or $m.package -cne 'io.whaletodo.app.preview' -or $m.debuggable -ne $false -or $m.source_commit -notmatch '^[a-f0-9]{40}$' -or $m.asset -cne "WhaleToDo-$($m.version).apk") { throw 'Invalid release manifest.' }
$pin = (Get-Content -LiteralPath (Join-Path $repo 'release/preview-certificate.sha256') -Raw).Trim()
if ($m.certificate_sha256 -cne $pin) { throw 'Unexpected certificate.' }
$apk = Join-Path $dir $m.asset
if ((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant() -cne $m.sha256 -or (Get-Item -LiteralPath $apk).Length -ne $m.bytes) { throw 'APK hash or size mismatch.' }
$sdk = $env:ANDROID_SDK_ROOT
if (!$sdk) { throw 'Run android-env.ps1 first.' }
$signed = (& (Join-Path $sdk 'build-tools/35.0.0/apksigner.bat') verify --print-certs $apk 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0 -or $signed -notmatch 'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)' -or $Matches[1].ToLowerInvariant() -cne $pin) { throw 'APK certificate verification failed.' }
# Never overwrite an existing tag or release. A failed draft can be inspected and resumed manually.
& gh api "repos/bottledkzk/WhaleToDo/releases/tags/$($m.tag)" --silent 2>$null
if ($LASTEXITCODE -eq 0) { throw 'Release already exists; inspect it instead of replacing assets.' }
& git -C $repo ls-remote --exit-code origin "refs/tags/$($m.tag)" *> $null
if ($LASTEXITCODE -eq 0) { throw 'Tag already exists; inspect it instead of moving it.' }
$files = @($apk,(Join-Path $dir 'SHA256SUMS.txt'),(Join-Path $dir 'release.json'),(Join-Path $dir 'RELEASE-NOTES.md'))
$website = Join-Path $dir "WhaleToDo-website-$($m.tag).zip"
if (Test-Path -LiteralPath $website) {
    $websiteSum = "$website.sha256"
    $expectedSiteHash = ((Get-Content -LiteralPath $websiteSum -Raw).Trim() -split '\s+')[0]
    if ((Get-FileHash -LiteralPath $website -Algorithm SHA256).Hash.ToLowerInvariant() -cne $expectedSiteHash) { throw 'Website package hash mismatch.' }
    $files += @($website, $websiteSum)
}
& gh release create $m.tag @files --repo bottledkzk/WhaleToDo --target $m.source_commit --title "WhaleToDo $($m.version)" --notes-file (Join-Path $dir 'RELEASE-NOTES.md') --draft --prerelease
if ($LASTEXITCODE -ne 0) { throw 'Draft creation failed.' }
$readback = Join-Path $dir 'github-readback'
New-Item -ItemType Directory -Path $readback | Out-Null
& gh release download $m.tag --repo bottledkzk/WhaleToDo --dir $readback
if ($LASTEXITCODE -ne 0) { throw 'Draft readback failed; the release remains a draft.' }
foreach ($file in $files) {
    $download = Join-Path $readback (Split-Path $file -Leaf)
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -cne (Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash) { throw 'Uploaded attachment hash differs; the release remains a draft.' }
}
& gh release edit $m.tag --repo bottledkzk/WhaleToDo --draft=false --prerelease
if ($LASTEXITCODE -ne 0) { throw 'Publication failed; inspect draft state.' }
Write-Host "Published verified preview: https://github.com/bottledkzk/WhaleToDo/releases/tag/$($m.tag)"
