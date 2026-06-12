param(
  [int]$BackendPort = 8080,
  [int]$FrontendPort = 5173
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$backendDir = Join-Path $repoRoot "backend\goforbroke"
$frontendDir = Join-Path $repoRoot "frontend"
$devDir = Join-Path $repoRoot ".dev"
$backendOutLog = Join-Path $devDir "backend.out.log"
$backendErrLog = Join-Path $devDir "backend.err.log"
$frontendOutLog = Join-Path $devDir "frontend.out.log"
$frontendErrLog = Join-Path $devDir "frontend.err.log"
$backendPidFile = Join-Path $devDir "backend.pid"
$frontendPidFile = Join-Path $devDir "frontend.pid"
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
  throw "Port $BackendPort is already in use. Stop the existing backend before running start-dev again."
}

if (Test-PortInUse -Port $FrontendPort) {
  throw "Port $FrontendPort is already in use. Stop the existing frontend before running start-dev again."
}

New-Item -ItemType Directory -Force -Path $devDir | Out-Null
Remove-Item -ErrorAction SilentlyContinue `
  $backendOutLog, $backendErrLog, $frontendOutLog, $frontendErrLog, $backendPidFile, $frontendPidFile

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

Write-Host "Starting frontend on port $FrontendPort..." -ForegroundColor Cyan
$frontendProcess = Start-Process `
  -FilePath "py" `
  -ArgumentList "-3", "-m", "http.server", "$FrontendPort" `
  -WorkingDirectory $frontendDir `
  -WindowStyle Hidden `
  -RedirectStandardOutput $frontendOutLog `
  -RedirectStandardError $frontendErrLog `
  -PassThru
$frontendProcess.Id | Set-Content $frontendPidFile

Write-Host ""
Write-Host "AGP Bets is starting up." -ForegroundColor Green
Write-Host "Frontend: http://localhost:$FrontendPort"
Write-Host "Backend:  http://localhost:8080"
Write-Host "Backend logs:  $backendOutLog / $backendErrLog"
Write-Host "Frontend logs: $frontendOutLog / $frontendErrLog"
Write-Host ""
Write-Host "Leave this terminal open while you use the app." -ForegroundColor Yellow
