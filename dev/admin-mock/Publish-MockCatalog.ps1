<#
.SYNOPSIS
Imports and publishes the frontend mock catalog into a remote development database.

.DESCRIPTION
Runs the read-only preflight first, shows what the database already holds, asks for
confirmation, then runs the catalog CLI import and publish. The password is read as a
secure string and is only ever placed in this process's environment.

The backend must be restarted afterwards before the new revision is served.

.EXAMPLE
.\dev\admin-mock\Publish-MockCatalog.ps1 -DatabaseUrl 'jdbc:postgresql://db.example:5432/festival?sslmode=require' -Username 'app' -FestivalId 'ec00912b-...' -Actor '홍길동'
#>
[CmdletBinding()]
param(
    # jdbc:postgresql://<host>:<port>/<database>[?sslmode=require]
    [Parameter(Mandatory)][string]$DatabaseUrl,
    [Parameter(Mandatory)][string]$Username,
    # The festival UUID this server serves; GET /api/v2/config shows it as data.festival.id.
    [Parameter(Mandatory)][string]$FestivalId,
    # Recorded as the actor in the catalog audit trail.
    [Parameter(Mandatory)][string]$Actor,
    [SecureString]$Password,
    [string]$Schema = 'public',
    [string]$Manifest = 'dev/catalog/frontend-mock-catalog.json',
    [string]$Jar = 'target/fall-festival-server-0.0.1-SNAPSHOT.jar',
    # Stops after the preflight summary without importing anything.
    [switch]$DryRun,
    # Skips the confirmation prompt for an unattended run.
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$exitCode = 1

function Invoke-CatalogCli {
    param([Parameter(Mandatory)][string[]]$CliArguments)
    # The CLI's own result lines are what matters here, so keep Spring's startup log out.
    $quiet = @('--spring.main.banner-mode=off', '--logging.level.root=WARN')
    $output = & java "-Dloader.main=dev.espero.festival.CatalogCliApplication" -cp $Jar `
        org.springframework.boot.loader.launch.PropertiesLauncher @CliArguments @quiet 2>&1
    $output | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) { throw "catalog CLI failed (exit $LASTEXITCODE)" }
    return $output
}

if (-not (Test-Path $Jar)) {
    throw "$Jar not found. Build it first: .\mvnw.cmd --batch-mode --no-transfer-progress -DskipTests package"
}
if (-not (Test-Path $Manifest)) { throw "$Manifest not found." }
if (-not $Password) { $Password = Read-Host -AsSecureString 'DB 비밀번호' }
$plainPassword = [System.Net.NetworkCredential]::new('', $Password).Password

# The catalog CLI and the preflight read their own environment variables. Both stay in
# this process, and neither the password nor the URL is written to a file or the console.
$env:PREFLIGHT_DATASOURCE_URL = $DatabaseUrl
$env:PREFLIGHT_DATASOURCE_USERNAME = $Username
$env:PREFLIGHT_DATASOURCE_PASSWORD = $plainPassword
$env:PREFLIGHT_SCHEMA = $Schema
$env:PREFLIGHT_FESTIVAL_ID = $FestivalId
$env:SPRING_DATASOURCE_URL = $DatabaseUrl
$env:SPRING_DATASOURCE_USERNAME = $Username
$env:SPRING_DATASOURCE_PASSWORD = $plainPassword
$env:SPRING_FLYWAY_ENABLED = 'false'

try {
    Write-Host '읽기 전용 사전 점검 중...' -ForegroundColor Cyan
    $preflight = & java "-Dloader.main=dev.espero.festival.preflight.DatabasePreflightApplication" -cp $Jar `
        org.springframework.boot.loader.launch.PropertiesLauncher 2>&1
    $preflightExit = $LASTEXITCODE

    # 기존 catalog가 있는 DB에서는 STOP_AND_REVIEW(2)가 정상입니다. migration을 실행하는
    # 단계가 아니라 이미 게시된 revision 위에 새 revision을 얹는 작업이기 때문입니다.
    if ($preflightExit -notin 0, 2) {
        $preflight | ForEach-Object { Write-Host "  $_" }
        throw "preflight failed (exit $preflightExit)"
    }
    ($preflight | Select-String -Pattern '^(status|postgresqlVersion|database|currentUser|migrationCount)=') |
        ForEach-Object { Write-Host "  $_" }

    $revisions = $preflight | Select-String -Pattern '^revision=' | ForEach-Object {
        $fields = @{}
        foreach ($part in ($_ -replace '^revision=', '') -split ' \| ') {
            $pair = $part -split ':', 2
            if ($pair.Count -eq 2) { $fields[$pair[0]] = $pair[1] }
        }
        [pscustomobject]$fields
    } | Where-Object { $_.festival_id -eq $FestivalId }

    if (-not $revisions) { throw "이 festival($FestivalId)의 revision을 찾지 못했습니다. --festival-id와 schema를 확인하세요." }
    Write-Host ''
    Write-Host '현재 revision:' -ForegroundColor Cyan
    $revisions | Sort-Object { [int]$_.revision_number } |
        ForEach-Object { Write-Host ("  #{0} {1} {2}" -f $_.revision_number, $_.state, $_.id) }

    $published = @($revisions | Where-Object { $_.state -eq 'published' })
    if ($published.Count -gt 1) { throw 'published revision이 둘 이상입니다. 원인을 확인하기 전에는 진행하지 않습니다.' }
    $baseline = if ($published.Count -eq 1) { $published[0].id } else { 'none' }

    Write-Host ''
    Write-Host ("기준 revision: {0}" -f $baseline)
    Write-Host ("manifest     : {0}" -f $Manifest)
    Write-Host ("작업자       : {0}" -f $Actor)
    if ($DryRun) {
        Write-Host 'DryRun이므로 여기서 멈춥니다. DB는 바뀌지 않았습니다.' -ForegroundColor Yellow
        $exitCode = 0
        return
    }
    if (-not $Force) {
        $answer = Read-Host '이 DB에 목 catalog를 게시할까요? 공개 서비스의 부스·지도·공연이 바뀝니다 (yes/no)'
        if ($answer -ne 'yes') {
            Write-Host '취소했습니다. DB는 바뀌지 않았습니다.'
            $exitCode = 0
            return
        }
    }

    Write-Host ''
    Write-Host '가져오는 중(import)...' -ForegroundColor Cyan
    $importOutput = Invoke-CatalogCli @(
        'import', "--manifest=$Manifest", "--festival-id=$FestivalId", "--baseline-revision=$baseline", "--actor=$Actor"
    )
    $draft = ($importOutput | Select-String -Pattern '^draft revision: (.+)$').Matches.Groups[1].Value
    if (-not $draft) { throw 'import 출력에서 draft revision을 찾지 못했습니다.' }

    Write-Host '게시하는 중(publish)...' -ForegroundColor Cyan
    Invoke-CatalogCli @('publish', "--revision=$draft", "--actor=$Actor") | Out-Null

    Write-Host ''
    Write-Host "게시 완료: $draft" -ForegroundColor Green
    Write-Host '다음 단계' -ForegroundColor Cyan
    Write-Host '  1) 백엔드를 재시작합니다. 재시작 전에는 공개 API가 이전 revision을 그대로 제공합니다.'
    Write-Host '  2) /readyz가 200인지, /api/v2/spaces의 부스가 20개인지 확인합니다.'
    Write-Host '  3) 공지·굿즈는 dev/admin-mock/seed-admin-content.mjs로 넣습니다.'
    Write-Host ("  되돌리려면: rollback --revision=<이전 published id> --expected-current={0} --actor=<이름>" -f $draft)
    $exitCode = 0
}
catch {
    Write-Host ("실패: {0}" -f $_.Exception.Message) -ForegroundColor Red
}
finally {
    foreach ($name in 'PREFLIGHT_DATASOURCE_URL', 'PREFLIGHT_DATASOURCE_USERNAME', 'PREFLIGHT_DATASOURCE_PASSWORD',
        'PREFLIGHT_SCHEMA', 'PREFLIGHT_FESTIVAL_ID', 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME',
        'SPRING_DATASOURCE_PASSWORD', 'SPRING_FLYWAY_ENABLED') {
        Remove-Item "env:$name" -ErrorAction SilentlyContinue
    }
    $plainPassword = $null
}

exit $exitCode
