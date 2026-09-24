[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [ValidateSet('staging')]
    [string]$Environment = 'staging',
    [Parameter(Mandatory)]
    [string]$Image,
    [Parameter(Mandatory)]
    [ValidatePattern('^[a-z0-9][a-z0-9_.-]*$')]
    [string]$ContainerName,
    [Parameter(Mandatory)]
    [ValidatePattern('^[a-z0-9][a-z0-9_.-]*$')]
    [string]$MediaVolume,
    [string]$LogVolume = '',
    [Parameter(Mandatory)]
    [ValidatePattern('^[a-z0-9][a-z0-9_.-]*$')]
    [string]$Network,
    [Parameter(Mandatory)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$RuntimeEnvFile,
    [ValidateRange(1024, 65535)]
    [int]$HostPort = 8080,
    [switch]$ConfirmStagingTarget
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Require([bool]$condition, [string]$message) {
    if (-not $condition) { throw $message }
}

Require $ConfirmStagingTarget.IsPresent 'Pass -ConfirmStagingTarget only after verifying the protected staging target identity.'
Require ($Environment -eq 'staging') 'This template permits staging only.'
Require ($Image -match '^.+@sha256:[0-9a-f]{64}$') 'Image must be an immutable image@sha256 reference.'
if (-not $LogVolume) { $LogVolume = "$ContainerName-logs" }
Require ($LogVolume -match '^[a-z0-9][a-z0-9_.-]*$') 'Log volume name is invalid.'
Require ($LogVolume -ne $MediaVolume) 'Log and media volumes must be separate.'

$envPath = (Resolve-Path -LiteralPath $RuntimeEnvFile).Path
$settings = @{}
foreach ($line in Get-Content -LiteralPath $envPath) {
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
    if ($trimmed -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') {
        throw "Runtime env file has an invalid line for a Docker env file."
    }
    $name = $Matches[1]
    if ($settings.ContainsKey($name)) { throw "Runtime env file repeats $name." }
    $settings[$name] = $Matches[2]
}

foreach ($name in @(
    'SPRING_PROFILES_ACTIVE', 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME',
    'SPRING_DATASOURCE_PASSWORD', 'ADMIN_JWT_SIGNING_SECRET', 'ADMIN_ALLOWED_ORIGIN', 'FESTIVAL_ID'
)) {
    Require $settings.ContainsKey($name) "Runtime env file must provide $name."
}
Require ($settings['SPRING_PROFILES_ACTIVE'] -eq 'db') 'Runtime must use only the db profile.'
Require ($settings['SPRING_FLYWAY_ENABLED'] -eq 'false') 'Run Flyway through the protected migration step before starting runtime.'
Require ($settings['FESTIVAL_CLEANUP_SCHEDULE_ENABLED'] -eq 'false') 'Release runtime must keep cleanup scheduling disabled.'
Require ($settings['FESTIVAL_CLEANUP_DRY_RUN'] -eq 'true') 'Release runtime must keep cleanup in dry-run mode.'
foreach ($name in @('ADMIN_BOOTSTRAP_USERNAME', 'ADMIN_BOOTSTRAP_PASSWORD', 'SPRING_APPLICATION_JSON', 'SPRING_CONFIG_IMPORT', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS')) {
    Require (-not $settings.ContainsKey($name)) "Runtime env file must not include $name."
}

& docker version --format '{{.Server.Version}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker daemon is unavailable.' }
& docker network inspect $Network | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Docker network $Network does not exist." }
& docker volume inspect $MediaVolume | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Docker media volume $MediaVolume does not exist; create and verify it in the protected staging procedure." }
& docker container inspect $ContainerName 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) { throw "Container $ContainerName already exists; this template never replaces a running container." }

$arguments = @(
    'run', '--detach', '--name', $ContainerName,
    '--network', $Network,
    '--publish', "127.0.0.1:${HostPort}:8080",
    '--mount', "type=volume,src=$MediaVolume,dst=/var/lib/espero/media",
    '--mount', "type=volume,src=$LogVolume,dst=/var/log/espero",
    '--env-file', $envPath,
    '--env', 'LOGGING_FILE_NAME=/var/log/espero/application.jsonl',
    '--env', 'FESTIVAL_HTTP_LOG_SUCCESS=true',
    '--env', 'LOGGING_LEVEL_ROOT=INFO',
    '--log-driver', 'local',
    '--log-opt', 'max-size=20m',
    '--log-opt', 'max-file=5',
    '--label', 'espero.release-environment=staging',
    '--label', "espero.release-image=$Image",
    $Image
)
if ($PSCmdlet.ShouldProcess($ContainerName, "start immutable staging candidate $Image")) {
    & docker @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Docker failed to start the staging candidate.' }
}
