[CmdletBinding()]
param(
    [int]$WarmupSeconds = 10,
    [int]$StageSeconds = 30,
    [string]$OutputDirectory = "$(Join-Path (Get-Location) 'target\load-test-results')"
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$container = "espero-catalog-load-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$dbPassword = [Guid]::NewGuid().ToString('N')
$port = Get-Random -Minimum 18080 -Maximum 18999
$runDirectory = Join-Path $OutputDirectory ([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))
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

New-Item -ItemType Directory -Path $fixtureDirectory -Force | Out-Null

try {
    Push-Location $root
    node tools/catalog-load-fixture.mjs $fixturePath | Out-Host

    if ($env:MAVEN_CMD) {
        & $env:MAVEN_CMD --batch-mode --no-transfer-progress -DskipTests package
    } else {
        $bash = Get-Command bash -ErrorAction SilentlyContinue
        if ($bash) {
            & $bash.Source -lc './mvnw --batch-mode --no-transfer-progress -DskipTests package'
        } else {
            & '.\mvnw.cmd' --batch-mode --no-transfer-progress -DskipTests package
        }
    }
    if ($LASTEXITCODE -ne 0) { throw "Maven package failed with exit code $LASTEXITCODE" }
    $jar = Get-ChildItem (Join-Path $root 'target') -Filter '*.jar' | Where-Object { $_.Name -notmatch 'original|plain' } | Select-Object -First 1
    if (-not $jar) { throw 'Packaged Spring JAR was not found under target.' }

    docker run --rm -d --name $container -P -e "POSTGRES_PASSWORD=$dbPassword" -e POSTGRES_DB=espero_load postgres:16-alpine | Out-Null
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
        $serverInfo.EnvironmentVariables['SPRING_PROFILES_ACTIVE'] = 'db'
        $serverInfo.EnvironmentVariables['SPRING_DATASOURCE_URL'] = "jdbc:postgresql://127.0.0.1:$dbPort/espero_load"
        $serverInfo.EnvironmentVariables['SPRING_DATASOURCE_USERNAME'] = 'postgres'
        $serverInfo.EnvironmentVariables['SPRING_DATASOURCE_PASSWORD'] = $dbPassword
        $serverInfo.EnvironmentVariables['SPRING_FLYWAY_LOCATIONS'] = "classpath:db/migration,filesystem:$fixtureDirectory"
        $serverInfo.EnvironmentVariables['SERVER_PORT'] = "$port"
        $serverInfo.EnvironmentVariables['SERVER_ADDRESS'] = '127.0.0.1'
        $server = [System.Diagnostics.Process]::new()
        $server.StartInfo = $serverInfo
        $server.Start() | Out-Null
        $stdoutTask = $server.StandardOutput.ReadToEndAsync()
        $stderrTask = $server.StandardError.ReadToEndAsync()

        $samplesPath = Join-Path $runDirectory 'jstat-samples.csv'
        $smokeReadyPath = Join-Path $runDirectory 'smoke-ready'
        $samplerScript = Join-Path $root 'tools\load-test\jstat-sampler.ps1'
        $samplerArgs = "-NoProfile -ExecutionPolicy Bypass -File `"$samplerScript`" -ProcessId $($server.Id) -OutputPath `"$samplesPath`" -JstatPath `"$jstatPath`" -WarmupSeconds $WarmupSeconds -StageSeconds $StageSeconds -ReadyPath `"$smokeReadyPath`""
        $sampler = Start-Process powershell -WindowStyle Hidden -PassThru -ArgumentList $samplerArgs

        node tools/load-test/http-load.mjs --base-url "http://127.0.0.1:$port" --output $resultPath --smoke-ready-file $smokeReadyPath --warmup-ms ($WarmupSeconds * 1000) --stage-ms ($StageSeconds * 1000) | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "HTTP load generator failed with exit code $LASTEXITCODE" }
        if ($sampler -and -not $sampler.HasExited) { Wait-Process -Id $sampler.Id -Timeout 10 -ErrorAction SilentlyContinue }
        if ($sampler -and -not $sampler.HasExited) { Stop-Process -Id $sampler.Id -Force -ErrorAction SilentlyContinue }

        $samples = Import-Csv $samplesPath
        $jstatSummary = foreach ($stageName in @('warmup', 'vus-100', 'vus-200', 'vus-500')) {
            $stageSamples = @($samples | Where-Object stage -eq $stageName)
            if ($stageSamples.Count -gt 0) {
                [pscustomobject]@{
                    stage = $stageName
                    samples = $stageSamples.Count
                    peakHeapUsedKb = [math]::Round((($stageSamples | Measure-Object heapUsedKb -Maximum).Maximum), 2)
                    peakHeapCommittedKb = [math]::Round((($stageSamples | Measure-Object heapCommittedKb -Maximum).Maximum), 2)
                    gcCountDelta = [int]$stageSamples[-1].ygc + [int]$stageSamples[-1].fgc - [int]$stageSamples[0].ygc - [int]$stageSamples[0].fgc
                    gcTimeDeltaSeconds = [math]::Round(([double]$stageSamples[-1].gctSeconds - [double]$stageSamples[0].gctSeconds), 4)
                }
            }
        }
        [pscustomobject]@{ machine = [Environment]::MachineName; javaExecutable = (Get-Command java).Source; jstat = $jstatPath; fixture = 'synthetic:100 spaces, 7 maps, 101 places, 207 pins'; localhostOnly = $true; jstatStages = @($jstatSummary) } | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $runDirectory 'environment-and-gc.json')
        Write-Host "Load verification results: $resultPath"
    }
    finally {
        if ($sampler -and -not $sampler.HasExited) { Stop-Process -Id $sampler.Id -Force -ErrorAction SilentlyContinue }
        if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
        if ($stdoutTask) { [System.IO.File]::WriteAllText($serverLog, $stdoutTask.GetAwaiter().GetResult()) }
        if ($stderrTask) { [System.IO.File]::WriteAllText($serverError, $stderrTask.GetAwaiter().GetResult()) }
        docker rm -f $container 2>$null | Out-Null
    }
}
finally {
    Pop-Location
}
