$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$serverDir = Join-Path $root "server"
$configPath = Join-Path $root "web\config.js"

function Get-LanAddress {
    $route = Get-NetRoute -DestinationPrefix "0.0.0.0/0" -ErrorAction SilentlyContinue |
        Sort-Object RouteMetric |
        Select-Object -First 1
    if ($route) {
        $address = Get-NetIPAddress -InterfaceIndex $route.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.IPAddress -notlike "127.*" } |
            Select-Object -First 1
        if ($address) { return $address.IPAddress }
    }
    return "127.0.0.1"
}

$lan = Get-LanAddress
@"
window.DISPATCH = {
  lanAddress: "$lan",
  cameraPath: "cam-alpha-09",
  dispatchPath: "dispatch",
  webrtcPort: 8889,
  hlsPort: 8888,
  rtspPort: 8554
};
"@ | Set-Content -LiteralPath $configPath -Encoding utf8
Write-Output "Phone RTSP target: rtsp://${lan}:8554/cam-alpha-09"

$mtx = $null
$cmd = Get-Command mediamtx -ErrorAction SilentlyContinue
if ($cmd) { $mtx = $cmd.Source }
if (-not $mtx) {
    $mtx = Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Packages\bluenviron.mediamtx_Microsoft.Winget.Source_8wekyb3d8bbwe\mediamtx.exe"
}
if (-not (Test-Path -LiteralPath $mtx)) {
    throw "MediaMTX was not found. Install it with: winget install bluenviron.mediamtx"
}

$rtspBusy = Get-NetTCPConnection -LocalPort 8554 -State Listen -ErrorAction SilentlyContinue
if ($rtspBusy) {
    Write-Output "MediaMTX is already listening on port 8554."
}
else {
    Start-Process -FilePath $mtx -ArgumentList "`"$serverDir\mediamtx.yml`"" -WorkingDirectory $serverDir
    Write-Output "Started MediaMTX."
}

$webBusy = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($webBusy) {
    Write-Output "Dispatch page is already listening on http://127.0.0.1:8080/"
}
else {
    Start-Process -FilePath "powershell.exe" -ArgumentList @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $root "web\serve.ps1")
    )
    Write-Output "Dispatch page: http://127.0.0.1:8080/"
}
