<#
.SYNOPSIS
Adds booth stamp QR tokens to the published catalog of a remote database.

.DESCRIPTION
Exports the currently published revision with the catalog CLI, generates a random
token for every booth in -Booths, writes the private QR link list, and imports and
publishes the exported catalog plus the booth token hashes as a new revision. The
rest of the catalog is carried over unchanged.

The server must already run a version with migration V27. Restart the backend
afterwards; it serves the new revision only after a restart. The password is read as
a secure string and is only ever placed in this process's environment.

.EXAMPLE
.\ops\stamp\Publish-BoothStamps.ps1 -DatabaseUrl 'jdbc:postgresql://db.example:5432/festival' -Username 'catalog' -FestivalId 'ec00912b-...' -BaselineRevision '<published revision id>' -Actor '홍길동' -Daily
#>
[CmdletBinding()]
param(
    # jdbc:postgresql://<host>:<port>/<database>[?sslmode=require]
    [Parameter(Mandatory)][string]$DatabaseUrl,
    [Parameter(Mandatory)][string]$Username,
    # The festival UUID this server serves; GET /api/v2/config shows it as data.festival.id.
    [Parameter(Mandatory)][string]$FestivalId,
    # The revision that is published now. DBeaver: SELECT id FROM festival_revisions WHERE state = 'published';
    [Parameter(Mandatory)][string]$BaselineRevision,
    # Recorded as the actor in the catalog audit trail.
    [Parameter(Mandatory)][string]$Actor,
    [SecureString]$Password,
    # JSON array of {"id", "name"} in display order.
    [string]$Booths = 'ops/stamp/booths.json',
    [string]$BaseUrl = 'https://festival.likelionerica.com/stamps',
    # Git-ignored folder for the exported catalog, the merged manifest and the secret link list.
    [string]$OutDir = 'ops/stamp/out',
    [string]$Jar = 'target/fall-festival-server-0.0.1-SNAPSHOT.jar',
    # One token per booth per festival day instead of one for the whole festival.
    [switch]$Daily,
    # Replaces booth tokens the published catalog already has. Every printed QR stops working.
    [switch]$Rotate,
    # Stops after generating the files; the database is not changed.
    [switch]$DryRun,
    # Skips the confirmation prompt and overwrites an existing link list.
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

function Invoke-CatalogCli {
    param([Parameter(Mandatory)][string[]]$CliArguments)
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
if (-not (Test-Path $Booths)) { throw "$Booths not found. Copy ops/stamp/booths.example.json and fill in the booths." }
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Node.js 18 or later is required.' }
if (-not $Password) { $Password = Read-Host -AsSecureString 'DB 비밀번호' }
$plainPassword = [System.Net.NetworkCredential]::new('', $Password).Password

$env:SPRING_DATASOURCE_URL = $DatabaseUrl
$env:SPRING_DATASOURCE_USERNAME = $Username
$env:SPRING_DATASOURCE_PASSWORD = $plainPassword
$env:SPRING_FLYWAY_ENABLED = 'false'

function Invoke-BoothStampPublish {
    New-Item -ItemType Directory -Force $OutDir | Out-Null
    $published = Join-Path $OutDir "published-$BaselineRevision.json"
    $merged = Join-Path $OutDir 'manifest-with-stamps.json'
    $links = Join-Path $OutDir 'stamp-qr-links.csv'

    Write-Host '게시본 내보내는 중(export)...' -ForegroundColor Cyan
    Invoke-CatalogCli @('export', "--revision=$BaselineRevision", "--out=$published") | Out-Null

    Write-Host '부스 토큰 생성 중...' -ForegroundColor Cyan
    $generator = @('tools/stamp/generate-booth-stamps.mjs', '--booths', $Booths, '--manifest-in', $published,
        '--manifest-out', $merged, '--links-out', $links, '--base-url', $BaseUrl)
    if ($Daily) { $generator += '--daily' }
    if ($Rotate) { $generator += '--rotate' }
    if ($Force) { $generator += '--force' }
    & node @generator
    if ($LASTEXITCODE -ne 0) { throw 'token generation failed' }

    Write-Host ''
    Write-Host ("기준 revision: {0}" -f $BaselineRevision)
    Write-Host ("manifest     : {0}" -f $merged)
    Write-Host ("QR 링크      : {0}  (비밀. 커밋·공유 금지)" -f $links)
    if ($DryRun) {
        Write-Host 'DryRun이므로 여기서 멈춥니다. DB는 바뀌지 않았습니다.' -ForegroundColor Yellow
        return 0
    }
    if (-not $Force) {
        $answer = Read-Host '부스 스탬프 토큰을 포함한 새 revision을 게시할까요? 기존 부스 QR은 이 게시 뒤 무효가 됩니다 (yes/no)'
        if ($answer -ne 'yes') {
            Write-Host '취소했습니다. DB는 바뀌지 않았습니다. 생성된 링크 파일은 게시되지 않았으므로 쓰지 마세요.'
            return 0
        }
    }

    Write-Host '가져오는 중(import)...' -ForegroundColor Cyan
    $importOutput = Invoke-CatalogCli @(
        'import', "--manifest=$merged", "--festival-id=$FestivalId", "--baseline-revision=$BaselineRevision", "--actor=$Actor"
    )
    $draft = ($importOutput | Select-String -Pattern '^draft revision: (.+)$').Matches.Groups[1].Value
    if (-not $draft) { throw 'import 출력에서 draft revision을 찾지 못했습니다.' }

    Write-Host '게시하는 중(publish)...' -ForegroundColor Cyan
    Invoke-CatalogCli @('publish', "--revision=$draft", "--actor=$Actor") | Out-Null

    Write-Host ''
    Write-Host "게시 완료: $draft" -ForegroundColor Green
    Write-Host '다음 단계' -ForegroundColor Cyan
    Write-Host '  1) 백엔드를 재시작합니다. 재시작 전에는 이전 revision의 부스 토큰이 쓰입니다.'
    Write-Host "  2) $links 의 링크로 QR을 만들어 부스별로 전달합니다."
    Write-Host '  3) 링크 하나로 POST /api/v2/stamp-collections 가 200인지 확인합니다.'
    Write-Host ("  되돌리려면: rollback --revision={0} --expected-current={1} --actor=<이름>" -f $BaselineRevision, $draft)
    return 0
}

try {
    $exitCode = Invoke-BoothStampPublish
}
catch {
    Write-Host ("실패: {0}" -f $_.Exception.Message) -ForegroundColor Red
    $exitCode = 1
}
finally {
    foreach ($name in 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD', 'SPRING_FLYWAY_ENABLED') {
        Remove-Item "env:$name" -ErrorAction SilentlyContinue
    }
    $plainPassword = $null
}

exit $exitCode
