$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptRoot
Set-Location $projectRoot

Write-Host "=== Cloudflare Tunnel Quick Start ==="

$cloudflaredPath = Join-Path $scriptRoot "cloudflared.exe"

if (-not (Test-Path $cloudflaredPath)) {
    Write-Host "1. cloudflared.exe not found. Downloading from official Cloudflare GitHub releases..."
    $url = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"
    
    try {
        # Using WebClient or Invoke-WebRequest
        Invoke-WebRequest -Uri $url -OutFile $cloudflaredPath -UseBasicParsing
        Write-Host "Download complete!"
    } catch {
        Write-Error "Failed to download cloudflared.exe. Please download it manually from Cloudflare and place it in the scripts directory."
        exit 1
    }
} else {
    Write-Host "1. cloudflared.exe already exists."
}

# Test if Cloudflare tunnel port 7844 is open
Write-Host "2. Checking outbound port 7844 availability..."
$portOpen = $false
try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $connect = $tcp.BeginConnect("edge.argotunnel.com", 7844, $null, $null)
    $success = $connect.AsyncWaitHandle.WaitOne(2000, $true)
    if ($success) {
        $tcp.EndConnect($connect)
        $portOpen = $true
    }
} catch {
    # Ignore
} finally {
    if ($null -ne $tcp) { $tcp.Close() }
}

if (-not $portOpen) {
    Write-Host "WARNING: Outbound port 7844 is blocked or unreachable (firewall restriction)."
    Write-Host "Falling back to Localtunnel (HTTPS via standard port 443)..."
    Write-Host "Press Ctrl+C to terminate the tunnel."
    Write-Host "--------------------------------------------------------"
    npx -y localtunnel --port 8765
} else {
    Write-Host "Port 7844 is open. Launching Cloudflare Tunnel mapped to http://127.0.0.1:8765..."
    Write-Host "Please look below for the generated URL ending with '.trycloudflare.com'."
    Write-Host "Press Ctrl+C to terminate the tunnel."
    Write-Host "--------------------------------------------------------"
    # Run cloudflared tunnel
    & $cloudflaredPath tunnel --protocol http2 --url http://127.0.0.1:8765
}
