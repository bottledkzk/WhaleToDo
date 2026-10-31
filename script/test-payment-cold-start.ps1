param(
    [ValidatePattern('^emulator-[0-9]+$')][string]$Serial = 'emulator-5580',
    [string]$Adb = 'adb',
    [ValidateSet('io.whaletodo.app.verification','io.whaletodo.app.paymentdemo')]
    [string]$Package = 'io.whaletodo.app.verification'
)
$ErrorActionPreference = 'Stop'

# Run only after installing matching app/test APKs. No uninstall, data clear or network call.
function Invoke-TestAdb([string[]]$Arguments) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'ADB test operation failed' }
    return ($output -join "`n")
}

foreach ($phase in @('save','restore')) {
    if ($phase -eq 'restore') {
        Invoke-TestAdb -Arguments @('shell','am','force-stop',$Package) | Out-Null
    }
    $output = Invoke-TestAdb -Arguments @(
        'shell','am','instrument','-w','-r','-e','payment_phase',$phase,
        '-e','class','io.whaletodo.app.CashierRecoveryIsolationTest#pendingReceiptSurvivesNewStoreAndColdStart',
        "$Package.test/androidx.test.runner.AndroidJUnitRunner"
    )
    Write-Output $output
    if (!$output.Contains('OK (1 test)') -or $output.Contains('FAILURES')) {
        throw "Cold-start receipt test failed in phase $phase"
    }
}
Write-Output 'COLD_START_RECEIPT_SAVE_STOP_RESTORE_PASSED'
