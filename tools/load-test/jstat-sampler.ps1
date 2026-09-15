param(
    [int]$ProcessId,
    [string]$OutputPath,
    [string]$JstatPath,
    [int]$WarmupSeconds,
    [int]$StageSeconds,
    [string]$ReadyPath
)
$ErrorActionPreference = 'SilentlyContinue'
while (-not (Test-Path $ReadyPath)) { Start-Sleep -Milliseconds 250 }
$rawPath = "$OutputPath.raw"
$rawErrorPath = "$OutputPath.raw.err"
$jstat = $null
$base = Get-Date
try {
    $jstat = Start-Process $JstatPath -WindowStyle Hidden -PassThru -RedirectStandardOutput $rawPath -RedirectStandardError $rawErrorPath -ArgumentList "-gc $ProcessId 500"
    Start-Sleep -Seconds ($WarmupSeconds + 3 * $StageSeconds + 2)
}
finally {
    if ($jstat -and -not $jstat.HasExited) { Stop-Process -Id $jstat.Id -Force -ErrorAction SilentlyContinue }
}
Set-Content -Path $OutputPath -Value 'timestampUtc,stage,heapUsedKb,heapCommittedKb,ygc,fgc,gctSeconds'
$sampleIndex = 0
foreach ($line in (Get-Content $rawPath -ErrorAction SilentlyContinue)) {
    if ($line -notmatch '^\s*\d') { continue }
    $values = $line -split '\s+' | Where-Object { $_ }
    if ($values.Count -lt 19) { continue }
    $elapsed = $sampleIndex * 0.5
    $stage = if ($elapsed -lt $WarmupSeconds) { 'warmup' } elseif ($elapsed -lt ($WarmupSeconds + $StageSeconds)) { 'vus-100' } elseif ($elapsed -lt ($WarmupSeconds + 2 * $StageSeconds)) { 'vus-200' } elseif ($elapsed -lt ($WarmupSeconds + 3 * $StageSeconds)) { 'vus-500' } else { 'complete' }
    $used = [double]$values[2] + [double]$values[3] + [double]$values[5] + [double]$values[7]
    $committed = [double]$values[0] + [double]$values[1] + [double]$values[4] + [double]$values[6]
    Add-Content -Path $OutputPath -Value (($base.AddMilliseconds($sampleIndex * 500)).ToUniversalTime().ToString('o') + "," + $stage + "," + $used + "," + $committed + "," + $values[12] + "," + $values[14] + "," + $values[18])
    $sampleIndex += 1
}
