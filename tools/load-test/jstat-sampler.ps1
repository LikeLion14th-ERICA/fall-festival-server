param(
    [int]$ProcessId,
    [string]$OutputPath,
    [string]$JstatPath,
    [int]$WarmupSeconds,
    [int]$StageSeconds,
    [int]$DynamicStageSeconds = 0,
    [string]$ReadyPath
)
$ErrorActionPreference = 'SilentlyContinue'
while (-not (Test-Path $ReadyPath)) { Start-Sleep -Milliseconds 250 }
$rawPath = "$OutputPath.raw"
$rawErrorPath = "$OutputPath.raw.err"
$jstat = $null
$processStartUtc = [DateTime]::UtcNow
try { $processStartUtc = (Get-Process -Id $ProcessId -ErrorAction Stop).StartTime.ToUniversalTime() } catch {}
$smokeReadyUtc = (Get-Item $ReadyPath).LastWriteTimeUtc
try {
    $jstat = Start-Process $JstatPath -WindowStyle Hidden -PassThru -RedirectStandardOutput $rawPath -RedirectStandardError $rawErrorPath -ArgumentList "-gc -t $ProcessId 500"
    Start-Sleep -Seconds ($WarmupSeconds + 3 * $StageSeconds + $DynamicStageSeconds + 15)
}
finally {
    if ($jstat -and -not $jstat.HasExited) { Stop-Process -Id $jstat.Id -Force -ErrorAction SilentlyContinue }
}
Set-Content -Path $OutputPath -Value 'timestampUtc,stage,heapUsedKb,heapCommittedKb,ygc,fgc,gctSeconds'
foreach ($line in (Get-Content $rawPath -ErrorAction SilentlyContinue)) {
    if ($line -notmatch '^\s*\d') { continue }
    $values = $line -split '\s+' | Where-Object { $_ }
    if ($values.Count -lt 20) { continue }
    $sampleUtc = $processStartUtc.AddSeconds([double]$values[0])
    $elapsed = ($sampleUtc - $smokeReadyUtc).TotalSeconds
    $stage = if ($elapsed -lt $WarmupSeconds) { 'warmup' } elseif ($elapsed -lt ($WarmupSeconds + $StageSeconds)) { 'vus-100' } elseif ($elapsed -lt ($WarmupSeconds + 2 * $StageSeconds)) { 'vus-200' } elseif ($elapsed -lt ($WarmupSeconds + 3 * $StageSeconds)) { 'vus-500' } elseif ($elapsed -lt ($WarmupSeconds + 3 * $StageSeconds + $DynamicStageSeconds)) { 'rate-67' } else { 'complete' }
    $used = [double]$values[3] + [double]$values[4] + [double]$values[6] + [double]$values[8]
    $committed = [double]$values[1] + [double]$values[2] + [double]$values[5] + [double]$values[7]
    Add-Content -Path $OutputPath -Value ($sampleUtc.ToString('o') + "," + $stage + "," + $used + "," + $committed + "," + $values[13] + "," + $values[15] + "," + $values[19])
}
