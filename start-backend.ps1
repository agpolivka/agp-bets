param(
  [int]$BackendPort = 8080
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$backendDir = Join-Path $repoRoot "backend\goforbroke"
$devDir = Join-Path $repoRoot ".dev"
$backendOutLog = Join-Path $devDir "backend.out.log"
$backendErrLog = Join-Path $devDir "backend.err.log"
$backendPidFile = Join-Path $devDir "backend.pid"
$backendWrapper = Join-Path $backendDir "mvnw.cmd"

function Test-PortInUse {
  param(
    [int]$Port
  )

  try {
    $listeners = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop
    return $null -ne $listeners
  } catch {
    return $false
  }
}

if (Test-PortInUse -Port $BackendPort) {
  throw "Port $BackendPort is already in use. Stop the existing backend before running start-backend again."
}

New-Item -ItemType Directory -Force -Path $devDir | Out-Null
Remove-Item -ErrorAction SilentlyContinue $backendOutLog, $backendErrLog, $backendPidFile

Write-Host "Starting PostgreSQL..." -ForegroundColor Cyan
docker compose up -d postgres

Write-Host "Starting backend..." -ForegroundColor Cyan
$backendProcess = Start-Process `
  -FilePath "cmd.exe" `
  -ArgumentList "/c", "`"$backendWrapper`" clean spring-boot:run" `
  -WorkingDirectory $backendDir `
  -WindowStyle Hidden `
  -RedirectStandardOutput $backendOutLog `
  -RedirectStandardError $backendErrLog `
  -PassThru
$backendProcess.Id | Set-Content $backendPidFile

Write-Host ""
Write-Host "Backend is starting up (frontend not started - use start-dev.ps1 for the full stack)." -ForegroundColor Green
Write-Host "Backend:  http://localhost:$BackendPort"
Write-Host "Backend logs:  $backendOutLog / $backendErrLog"
Write-Host ""
Write-Host "Leave this terminal open while you use the app." -ForegroundColor Yellow
