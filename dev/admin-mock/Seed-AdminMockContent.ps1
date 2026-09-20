<#
.SYNOPSIS
Creates the mock notices and goods through the admin API.

.DESCRIPTION
A thin wrapper around dev/admin-mock/seed-admin-content.mjs so the whole mock data
setup can run from PowerShell. The password is read as a secure string and is only
placed in this process's environment. Items that already exist are skipped, so the
script can be run again safely.

.EXAMPLE
.\dev\admin-mock\Seed-AdminMockContent.ps1 -BaseUrl 'https://api-festival.likelionerica.com' -AdminOrigin 'http://localhost:3001' -Username 'admin'
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BaseUrl,
    # Must equal the server's ADMIN_ALLOWED_ORIGIN.
    [Parameter(Mandatory)][string]$AdminOrigin,
    [Parameter(Mandatory)][string]$Username,
    [SecureString]$Password,
    # Prints what would be sent without logging in or writing anything.
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'seed-admin-content.mjs'
if (-not (Test-Path $script)) { throw "$script not found." }
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'node 18 이상이 필요합니다.' }
if (-not $DryRun -and -not $Password) { $Password = Read-Host -AsSecureString '관리자 비밀번호' }

$env:ADMIN_BASE_URL = $BaseUrl
$env:ADMIN_ORIGIN = $AdminOrigin
$env:ADMIN_USERNAME = $Username
if ($Password) { $env:ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $Password).Password }
try {
    if ($DryRun) { node $script --dry-run } else { node $script }
    if ($LASTEXITCODE -ne 0) { throw "seed script failed (exit $LASTEXITCODE)" }
}
finally {
    foreach ($name in 'ADMIN_BASE_URL', 'ADMIN_ORIGIN', 'ADMIN_USERNAME', 'ADMIN_PASSWORD') {
        Remove-Item "env:$name" -ErrorAction SilentlyContinue
    }
}
