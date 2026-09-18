param(
    [string]$Container,
    [string]$Database,
    [string]$OutputPath,
    [int]$WarmupSeconds,
    [int]$StageSeconds,
    [int]$DynamicStageSeconds,
    [string]$ReadyPath
)
# Samples the PostgreSQL side of the application's connection pool once per
# second. The server has no metrics endpoint, so client backend counts stand in
# for Hikari pool usage and ungranted locks show lock contention. The sampler's
# own backend is excluded.
$ErrorActionPreference = 'SilentlyContinue'
while (-not (Test-Path $ReadyPath)) { Start-Sleep -Milliseconds 250 }
$smokeReadyUtc = (Get-Item $ReadyPath).LastWriteTimeUtc
$endUtc = $smokeReadyUtc.AddSeconds($WarmupSeconds + 3 * $StageSeconds + $DynamicStageSeconds + 15)
$query = @"
SELECT
  (SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND backend_type = 'client backend' AND pid <> pg_backend_pid()),
  (SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND backend_type = 'client backend' AND state = 'active' AND pid <> pg_backend_pid()),
  (SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND state = 'idle in transaction'),
  (SELECT count(*) FROM pg_locks WHERE NOT granted),
  (SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock')
"@
Set-Content -Path $OutputPath -Value 'timestampUtc,stage,connections,active,idleInTransaction,ungrantedLocks,lockWaits'
while ([DateTime]::UtcNow -lt $endUtc) {
    $sampleUtc = [DateTime]::UtcNow
    $row = docker exec $Container psql -U postgres -d $Database -At -F ',' -c $query 2>$null
    if ($row -match '^\d+(,\d+){4}$') {
        $elapsed = ($sampleUtc - $smokeReadyUtc).TotalSeconds
        $stage = if ($elapsed -lt $WarmupSeconds) { 'warmup' }
            elseif ($elapsed -lt ($WarmupSeconds + $StageSeconds)) { 'vus-100' }
            elseif ($elapsed -lt ($WarmupSeconds + 2 * $StageSeconds)) { 'vus-200' }
            elseif ($elapsed -lt ($WarmupSeconds + 3 * $StageSeconds)) { 'vus-500' }
            elseif ($elapsed -lt ($WarmupSeconds + 3 * $StageSeconds + $DynamicStageSeconds)) { 'rate-67' }
            else { 'complete' }
        Add-Content -Path $OutputPath -Value ($sampleUtc.ToString('o') + ',' + $stage + ',' + $row)
    }
    Start-Sleep -Milliseconds 1000
}
