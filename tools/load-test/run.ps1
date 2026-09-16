[CmdletBinding()]
param(
    [int]$WarmupSeconds = 10,
    [int]$StageSeconds = 30,
    [int]$MaxSupportedVUs = 500,
    [string]$OutputDirectory = "$(Join-Path (Get-Location) 'target\load-test-results')"
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$container = "espero-catalog-load-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$runId = "{0}-{1}" -f ([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')), ([Guid]::NewGuid().ToString('N').Substring(0, 8))
$dbPassword = [Guid]::NewGuid().ToString('N')
$adminSigningSecret = [Guid]::NewGuid().ToString('N')
$adminAllowedOrigin = 'http://127.0.0.1:3000'
# The generated fixture extends V6's published festival row.
$fixtureFestivalId = 'ec00912b-763f-4f8f-8f57-4bdfc389ccbf'
$port = Get-Random -Minimum 18080 -Maximum 18999
$runDirectory = Join-Path $OutputDirectory $runId
$fixtureDirectory = Join-Path $runDirectory 'db'
$fixturePath = Join-Path $fixtureDirectory 'R__synthetic_catalog.sql'
$resultPath = Join-Path $runDirectory 'load-results.json'
$jstatPath = (Get-Command jstat -ErrorAction Stop).Source
$server = $null
$sampler = $null
$stdoutTask = $null
$stderrTask = $null
$serverLog = $null
$serverError = $null
$statusPath = Join-Path $runDirectory 'run-status.json'
$loadLog = Join-Path $runDirectory 'load-generator.log'
$loadError = Join-Path $runDirectory 'load-generator-error.log'
$loadExitPath = Join-Path $runDirectory 'load-generator-exit.json'

if ($MaxSupportedVUs -lt 500) { throw 'MaxSupportedVUs must be at least 500 so the 500 VU stage is covered.' }
function Write-RunStatus([string]$status, [hashtable]$detail = @{}) {
    $tempStatus = "$statusPath.tmp-$PID"
    [pscustomobject]@{ status = $status; at = [DateTime]::UtcNow.ToString('o'); detail = $detail } | ConvertTo-Json -Depth 6 | Set-Content $tempStatus
    Move-Item -Force $tempStatus $statusPath
}

New-Item -ItemType Directory -Path $fixtureDirectory -Force | Out-Null

try {
    Write-RunStatus 'starting'
    Push-Location $root
    Write-RunStatus 'fixture-generated'
    node tools/catalog-load-fixture.mjs $fixturePath | Out-Host

    if ($env:MAVEN_CMD) {
        & $env:MAVEN_CMD --batch-mode --no-transfer-progress -DskipTests package
    } else {
        & '.\mvnw.cmd' --batch-mode --no-transfer-progress -DskipTests package
    }
    if ($LASTEXITCODE -ne 0) { throw "Maven package failed with exit code $LASTEXITCODE" }
    $jar = Get-ChildItem (Join-Path $root 'target') -Filter '*.jar' | Where-Object { $_.Name -notmatch 'original|plain' } | Select-Object -First 1
    if (-not $jar) { throw 'Packaged Spring JAR was not found under target.' }
    Write-RunStatus 'jar-ready'

    docker run --rm -d --name $container -p '127.0.0.1::5432' -e "POSTGRES_PASSWORD=$dbPassword" -e POSTGRES_DB=espero_load postgres:16-alpine | Out-Null
    try {
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            if ((docker exec $container pg_isready -U postgres -d espero_load 2>$null) -match 'accepting connections') { break }
            Start-Sleep -Seconds 1
        }
        if ((docker exec $container pg_isready -U postgres -d espero_load 2>$null) -notmatch 'accepting connections') { throw 'PostgreSQL did not become ready.' }
        $mapped = docker port $container 5432/tcp
        $dbPort = [int](($mapped -split ':')[-1])

        $serverLog = Join-Path $runDirectory 'server.log'
        $serverError = Join-Path $runDirectory 'server-error.log'
        $serverInfo = [System.Diagnostics.ProcessStartInfo]::new()
        $serverInfo.FileName = 'java'
        $serverInfo.WorkingDirectory = $root
        $serverInfo.UseShellExecute = $false
        $serverInfo.RedirectStandardOutput = $true
        $serverInfo.RedirectStandardError = $true
        $serverInfo.Arguments = "-jar `"$($jar.FullName)`""
        foreach ($variable in @(
            'SPRING_APPLICATION_JSON', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS',
            'ADMIN_JWT_SIGNING_SECRET', 'ADMIN_ALLOWED_ORIGIN',
            'ADMIN_BOOTSTRAP_USERNAME', 'ADMIN_BOOTSTRAP_PASSWORD'
        )) {
            [void]$serverInfo.EnvironmentVariables.Remove($variable)
        }
        $serverInfo.EnvironmentVariables['SPRING_PROFILES_ACTIVE'] = 'db'
        $serverInfo.EnvironmentVariables['SPRING_DATASOURCE_URL'] = "jdbc:postgresql://127.0.0.1:$dbPort/espero_load"
        $serverInfo.EnvironmentVariables['SPRING_DATASOURCE_USERNAME'] = 'postgres'
        $serverInfo.EnvironmentVariables['SPRING_DATASOURCE_PASSWORD'] = $dbPassword
        $serverInfo.EnvironmentVariables['SPRING_FLYWAY_URL'] = "jdbc:postgresql://127.0.0.1:$dbPort/espero_load"
        $serverInfo.EnvironmentVariables['SPRING_FLYWAY_USER'] = 'postgres'
        $serverInfo.EnvironmentVariables['SPRING_FLYWAY_PASSWORD'] = $dbPassword
        $serverInfo.EnvironmentVariables['SPRING_FLYWAY_ENABLED'] = 'true'
        $serverInfo.EnvironmentVariables['SPRING_FLYWAY_LOCATIONS'] = "classpath:db/migration,filesystem:$fixtureDirectory"
        $serverInfo.EnvironmentVariables['ADMIN_JWT_SIGNING_SECRET'] = $adminSigningSecret
        $serverInfo.EnvironmentVariables['ADMIN_ALLOWED_ORIGIN'] = $adminAllowedOrigin
        $serverInfo.EnvironmentVariables['FESTIVAL_ID'] = $fixtureFestivalId
        $serverInfo.EnvironmentVariables['SERVER_PORT'] = "$port"
        $serverInfo.EnvironmentVariables['SERVER_ADDRESS'] = '127.0.0.1'
        $server = [System.Diagnostics.Process]::new()
        $server.StartInfo = $serverInfo
        $server.Start() | Out-Null
        $stdoutTask = $server.StandardOutput.ReadToEndAsync()
        $stderrTask = $server.StandardError.ReadToEndAsync()

        $samplesPath = Join-Path $runDirectory 'jstat-samples.csv'
        $smokeReadyPath = Join-Path $runDirectory 'smoke-ready'
        Write-RunStatus 'server-started' @{ pid = $server.Id; port = $port }
        $samplerScript = Join-Path $root 'tools\load-test\jstat-sampler.ps1'
        $samplerArgs = "-NoProfile -ExecutionPolicy Bypass -File `"$samplerScript`" -ProcessId $($server.Id) -OutputPath `"$samplesPath`" -JstatPath `"$jstatPath`" -WarmupSeconds $WarmupSeconds -StageSeconds $StageSeconds -ReadyPath `"$smokeReadyPath`""
        $sampler = Start-Process powershell -WindowStyle Hidden -PassThru -ArgumentList $samplerArgs

        Write-RunStatus 'load-running' @{ maxSupportedVUs = $MaxSupportedVUs }
        & java tools/load-test/CatalogLoadGenerator.java --base-url "http://127.0.0.1:$port" --output $resultPath --status-file (Join-Path $runDirectory 'load-status.json') --smoke-ready-file $smokeReadyPath --warmup-ms ($WarmupSeconds * 1000) --stage-ms ($StageSeconds * 1000) --max-supported-vus $MaxSupportedVUs 1> $loadLog 2> $loadError
        $loadExit = $LASTEXITCODE
        [pscustomobject]@{ exitCode = $loadExit; at = [DateTime]::UtcNow.ToString('o') } | ConvertTo-Json | Set-Content $loadExitPath
        if ($sampler -and -not $sampler.HasExited) { Wait-Process -Id $sampler.Id -Timeout 30 -ErrorAction SilentlyContinue }
        if ($sampler -and -not $sampler.HasExited) { Stop-Process -Id $sampler.Id -Force -ErrorAction SilentlyContinue }

        $samples = if (Test-Path $samplesPath) { @(Import-Csv $samplesPath) } else { @() }
        $previousSample = $null
        $jstatSummary = foreach ($stageName in @('warmup', 'vus-100', 'vus-200', 'vus-500')) {
            $stageSamples = @($samples | Where-Object stage -eq $stageName)
            if ($stageSamples.Count -gt 0) {
                $baselineSample = if ($previousSample) { $previousSample } else { $stageSamples[0] }
                $lastSample = $stageSamples[-1]
                [pscustomobject]@{
                    stage = $stageName
                    samples = $stageSamples.Count
                    peakHeapUsedKb = [math]::Round((($stageSamples | Measure-Object heapUsedKb -Maximum).Maximum), 2)
                    peakHeapCommittedKb = [math]::Round((($stageSamples | Measure-Object heapCommittedKb -Maximum).Maximum), 2)
                    baselineTimestampUtc = $baselineSample.timestampUtc
                    lastTimestampUtc = $lastSample.timestampUtc
                    gcCountDelta = [int]$lastSample.ygc + [int]$lastSample.fgc - [int]$baselineSample.ygc - [int]$baselineSample.fgc
                    gcTimeDeltaSeconds = [math]::Round(([double]$lastSample.gctSeconds - [double]$baselineSample.gctSeconds), 4)
                }
                $previousSample = $lastSample
            }
        }
        [pscustomobject]@{ machine = [Environment]::MachineName; javaExecutable = (Get-Command java).Source; jstat = $jstatPath; fixture = 'synthetic:100 spaces, 7 maps, 101 places, 207 pins'; localhostOnly = $true; jstatStages = @($jstatSummary) } | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $runDirectory 'environment-and-gc.json')
        if ($loadExit -ne 0) { throw "HTTP load generator failed with exit code $loadExit" }
        Write-RunStatus 'load-complete'
        Write-RunStatus 'complete'
        Write-Host "Load verification results: $resultPath"
    }
    finally {
        try {
            if ($sampler -and -not $sampler.HasExited) { Stop-Process -Id $sampler.Id -Force -ErrorAction SilentlyContinue }
            if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
            if ($stdoutTask) { [System.IO.File]::WriteAllText($serverLog, $stdoutTask.GetAwaiter().GetResult()) }
            if ($stderrTask) { [System.IO.File]::WriteAllText($serverError, $stderrTask.GetAwaiter().GetResult()) }
        }
        finally {
            docker rm -f $container 2>$null | Out-Null
        }
    }
}
catch {
    try { Write-RunStatus 'failed' @{ error = $_.Exception.Message } } catch {}
    throw
}
finally {
    Pop-Location
}
