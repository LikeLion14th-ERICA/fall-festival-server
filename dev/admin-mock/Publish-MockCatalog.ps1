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
    # The published revision to build on. Required with -SkipPreflight.
    [string]$BaselineRevision,
    # For databases behind a connection pooler that rejects the preflight's startup
    # options. The preflight is the safety check, so state the baseline yourself.
    [switch]$SkipPreflight,
    # Stops after the preflight summary without importing anything.
    [switch]$DryRun,
    # Skips the confirmation prompt for an unattended run.
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

function Show-SslUrlHint {
    if ($DatabaseUrl -match 'sslmode=(require|verify-ca|verify-full)|ssl=true') {
        Write-Host ''
        Write-Host '이 URL은 SSL 접속을 요구하고 있습니다:' -ForegroundColor Yellow
        Write-Host "  $DatabaseUrl"
        Write-Host '  서버가 SSL을 지원하지 않으면 접속이 거절됩니다. 옵션을 빼고 다시 실행하세요:'
        Write-Host ("  -DatabaseUrl '{0}'" -f ($DatabaseUrl -split '\?')[0])
    }
}

function Invoke-CatalogCli {
    param([Parameter(Mandatory)][string[]]$CliArguments)
    # The CLI's own result lines are what matters here, so keep Spring's startup log out.
    $quiet = @('--spring.main.banner-mode=off', '--logging.level.root=WARN')
    $output = & java "-Dloader.main=dev.espero.festival.CatalogCliApplication" -cp $Jar `
        org.springframework.boot.loader.launch.PropertiesLauncher @CliArguments @quiet 2>&1
    $output | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) {
        if ($output | Select-String -Pattern 'enableSSL|does not support SSL|SSL.*지원') { Show-SslUrlHint }
        throw "catalog CLI failed (exit $LASTEXITCODE)"
    }
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

# A plain `return` inside the script body would skip the final exit and leak the
# preflight's exit code, so the flow lives in a function that returns its own code.
# The preflight hides server messages by design, so ask the catalog CLI, which
# connects without startup options and prints what the server actually said.
function Show-ConnectionError {
    Write-Host ''
    Write-Host '서버가 보낸 실제 메시지:' -ForegroundColor Yellow
    $probe = & java "-Dloader.main=dev.espero.festival.CatalogCliApplication" -cp $Jar `
        org.springframework.boot.loader.launch.PropertiesLauncher export `
        --revision=00000000-0000-4000-8000-000000000001 --out=$([System.IO.Path]::GetTempFileName()) `
        --spring.main.banner-mode=off --logging.level.root=WARN 2>&1
    # A reachable database still fails this probe on the made-up revision, so treat a
    # rejected revision or any server-side error as proof that the CLI did connect.
    $connected = $probe | Select-String -Pattern 'catalog-cli:|ERROR: |draft revision'
    if (-not $connected) {
        $probe | Select-String -Pattern 'PSQLException|FATAL|refused' | Select-Object -First 3 |
            ForEach-Object { Write-Host "  $_" }
    }
    else {
        Write-Host '  catalog CLI는 이 DB에 접속했습니다. 사전 점검만 거절당했다는 뜻입니다.' -ForegroundColor Green
        Write-Host '  연결 풀러(PgBouncer 등)가 사전 점검의 startup 옵션을 거부하는 경우입니다.'
        Write-Host '  DBeaver에서 아래를 실행해 published revision을 확인한 뒤,'
        Write-Host "    SELECT id FROM festival_revisions WHERE state = 'published';"
        Write-Host '  -SkipPreflight -BaselineRevision <그 id>를 붙여 다시 실행하세요.'
    }
}

function Invoke-MockCatalogPublish {
    if ($SkipPreflight) {
        if (-not $BaselineRevision) { throw '-SkipPreflight에는 -BaselineRevision이 필요합니다.' }
        Write-Host '사전 점검을 건너뜁니다. 기준 revision은 입력값을 그대로 씁니다.' -ForegroundColor Yellow
        return Invoke-ImportAndPublish -Baseline $BaselineRevision
    }
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
    ($preflight | Select-String -Pattern '^(status|postgresqlVersion|database|schema|currentUser|migrationCount|festivalCount|revisionCount|finding)=') |
        ForEach-Object { Write-Host "  $_" }

    $revisions = $preflight | Select-String -Pattern '^revision=' | ForEach-Object {
        $fields = @{}
        foreach ($part in ($_ -replace '^revision=', '') -split ' \| ') {
            $pair = $part -split ':', 2
            if ($pair.Count -eq 2) { $fields[$pair[0]] = $pair[1] }
        }
        [pscustomobject]$fields
    } | Where-Object { $_.festival_id -eq $FestivalId }

    if (-not $revisions) {
        # The findings above say why: a wrong schema, a missing read permission or
        # a festival id this database does not hold.
        Write-Host ''
        Write-Host '사전 점검 출력(테이블 목록 제외):' -ForegroundColor Yellow
        $preflight | Where-Object { $_ -notmatch '^(relation|migration|catalogPresent)' } |
            ForEach-Object { Write-Host "  $_" }
        Write-Host ''
        Write-Host '확인할 점' -ForegroundColor Yellow
        # A failed connection reports only its SQLSTATE, so say what each one means.
        $sqlStateMatch = $preflight | Select-String -Pattern '^sqlState=(.+)$' | Select-Object -First 1
        $sqlState = if ($sqlStateMatch) { $sqlStateMatch.Matches[0].Groups[1].Value } else { '' }
        if ($preflight -match '^finding=CONFIGURATION_INVALID$') {
            Write-Host '  - 접속 설정이 올바르지 않습니다. 비밀번호가 비어 있거나, URL에 sslmode·sslrootcert 외의 옵션 또는 사용자·비밀번호가 들어 있는지 확인하세요.'
            throw '접속 설정이 올바르지 않습니다 (CONFIGURATION_INVALID).'
        }
        switch ($sqlState) {
            '08001' { Write-Host '  - 서버에 닿지 못했습니다. host·port, 방화벽, VPN·SSH tunnel 사용 여부를 확인하세요.' }
            '08004' {
                Write-Host '  - 서버가 연결을 거절했습니다. 가장 흔한 원인은 SSL 설정 불일치입니다.'
                Write-Host '    · 서버가 SSL을 쓰지 않는데 URL에 ?sslmode=require를 붙이면 이 코드가 납니다. 옵션을 빼고 다시 실행하세요.'
                Write-Host '    · 반대로 서버가 SSL을 요구하면 ?sslmode=require를 붙이세요.'
                Write-Host '    · 그 밖에 접속 IP 허용 목록, pooler 전용 포트·사용자 형식도 확인하세요.'
            }
            '28P01' { Write-Host '  - 비밀번호가 맞지 않습니다.' }
            '08P01' {
                Write-Host '  - 서버가 접속 요청 형식을 거부했습니다. 연결 풀러(PgBouncer 등)가 사전 점검의 startup 옵션을 받지 않는 경우입니다.'
                Write-Host '    · DB 제공자가 직접 접속용 포트를 따로 준다면 그 포트를 쓰세요.'
                Write-Host '    · 없으면 -SkipPreflight -BaselineRevision <published revision id>로 진행할 수 있습니다.'
            }
            '28000' { Write-Host '  - 이 사용자·IP로는 접속이 허용되지 않습니다(pg_hba). 제공자에게 확인하세요.' }
            '3D000' { Write-Host '  - 그 이름의 database가 없습니다. URL 끝의 database 이름을 확인하세요.' }
            '53300' { Write-Host '  - 연결 수가 한도에 찼습니다. 잠시 뒤 다시 시도하세요.' }
            default {
                Write-Host "  - schema: 지금 '$Schema'로 조회했습니다. 다른 schema면 -Schema로 지정하세요."
                Write-Host '  - 축제 UUID: GET /api/v2/config 응답의 data.festival.id와 같은지 확인하세요.'
                Write-Host '  - 권한: 위에 SCHEMA_MISSING_OR_INACCESSIBLE·TABLE_ACCESS_INCOMPLETE가 있으면 이 DB 계정이 해당 schema를 읽지 못합니다.'
            }
        }
        if ($sqlState) {
            Write-Host '  - DBeaver 연결이 SSH tunnel을 쓰고 있다면, 같은 tunnel을 연 뒤 -DatabaseUrl을 127.0.0.1:<로컬포트>로 주세요.'
            Show-SslUrlHint
            Show-ConnectionError
            throw "DB에 연결하지 못했습니다 (sqlState=$sqlState)."
        }
        throw "이 festival($FestivalId)의 revision을 찾지 못했습니다."
    }
    Write-Host ''
    Write-Host '현재 revision:' -ForegroundColor Cyan
    $revisions | Sort-Object { [int]$_.revision_number } |
        ForEach-Object { Write-Host ("  #{0} {1} {2}" -f $_.revision_number, $_.state, $_.id) }

    $published = @($revisions | Where-Object { $_.state -eq 'published' })
    if ($published.Count -gt 1) { throw 'published revision이 둘 이상입니다. 원인을 확인하기 전에는 진행하지 않습니다.' }
    $baseline = if ($published.Count -eq 1) { $published[0].id } else { 'none' }
    if ($BaselineRevision) { $baseline = $BaselineRevision }

    return Invoke-ImportAndPublish -Baseline $baseline
}

function Invoke-ImportAndPublish {
    param([Parameter(Mandatory)][string]$Baseline)
    $baseline = $Baseline

    Write-Host ''
    Write-Host ("기준 revision: {0}" -f $baseline)
    Write-Host ("manifest     : {0}" -f $Manifest)
    Write-Host ("작업자       : {0}" -f $Actor)
    if ($DryRun) {
        Write-Host 'DryRun이므로 여기서 멈춥니다. DB는 바뀌지 않았습니다.' -ForegroundColor Yellow
        return 0
    }
    if (-not $Force) {
        $answer = Read-Host '이 DB에 목 catalog를 게시할까요? 공개 서비스의 부스·지도·공연이 바뀝니다 (yes/no)'
        if ($answer -ne 'yes') {
            Write-Host '취소했습니다. DB는 바뀌지 않았습니다.'
            return 0
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
    return 0
}

try {
    $exitCode = Invoke-MockCatalogPublish
}
catch {
    Write-Host ("실패: {0}" -f $_.Exception.Message) -ForegroundColor Red
    $exitCode = 1
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
