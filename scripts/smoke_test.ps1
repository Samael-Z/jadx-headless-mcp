# Smoke test: spawn jadx-mcp serve, send a few MCP JSON-RPC frames, print responses.
# Usage:  pwsh scripts/smoke_test.ps1 <path-to-apk>

param(
    [Parameter(Mandatory = $true)][string]$ApkPath
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path $PSScriptRoot -Parent
$exe = Join-Path $repoRoot 'target\release\jadx-mcp.exe'
if (-not (Test-Path $exe)) { throw "binary not built: $exe" }

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $exe
$psi.Arguments = "serve --apk `"$ApkPath`" --bridge-startup-secs 600"
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.WorkingDirectory = $repoRoot

$proc = [System.Diagnostics.Process]::Start($psi)

# Drain stderr in the background so the pipe never fills.
$errJob = Start-Job -ScriptBlock {
    param($Sr)
    while (-not $Sr.EndOfStream) { Write-Host "[stderr] $($Sr.ReadLine())" }
} -ArgumentList ($proc.StandardError)

function Send-Frame($obj) {
    $json = $obj | ConvertTo-Json -Depth 10 -Compress
    Write-Host ">>> $json"
    $proc.StandardInput.WriteLine($json)
    $proc.StandardInput.Flush()
}

function Read-Frame {
    $line = $proc.StandardOutput.ReadLine()
    if ($null -eq $line) { throw 'EOF on stdout' }
    Write-Host "<<< $line"
    return $line
}

# 1. initialize
Send-Frame @{
    jsonrpc = '2.0'
    id      = 1
    method  = 'initialize'
    params  = @{
        protocolVersion = '2025-06-18'
        capabilities    = @{}
        clientInfo      = @{ name = 'smoke-test'; version = '0.0.1' }
    }
}
[void](Read-Frame)

# 2. initialized notification
Send-Frame @{ jsonrpc = '2.0'; method = 'notifications/initialized'; params = @{} }

# 3. tools/list
Send-Frame @{ jsonrpc = '2.0'; id = 2; method = 'tools/list'; params = @{} }
$listLine = Read-Frame
$list = $listLine | ConvertFrom-Json
Write-Host ("tools: count = {0}" -f $list.result.tools.Count)
$list.result.tools | ForEach-Object { Write-Host "  - $($_.name)" }

# 4. call get_package_tree
Send-Frame @{
    jsonrpc = '2.0'
    id      = 3
    method  = 'tools/call'
    params  = @{ name = 'get_package_tree'; arguments = @{} }
}
[void](Read-Frame)

# 5. call get_android_manifest
Send-Frame @{
    jsonrpc = '2.0'
    id      = 4
    method  = 'tools/call'
    params  = @{ name = 'get_android_manifest'; arguments = @{} }
}
[void](Read-Frame)

Write-Host "Stopping..."
$proc.StandardInput.Close()
$null = $proc.WaitForExit(15000)
Stop-Job $errJob -ErrorAction SilentlyContinue
Remove-Job $errJob -ErrorAction SilentlyContinue
Write-Host "Exit code: $($proc.ExitCode)"
