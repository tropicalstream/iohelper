# run.ps1 - start / restart / stop iohelper, and create the desktop icon.
#
#   .\run.ps1              restart the service (idempotent) and open the portal
#   .\run.ps1 -Quiet       same, no window/browser (for autostart if ever wanted)
#   .\run.ps1 -Stop        stop it
#   .\run.ps1 -Shortcut    create "iohelper" on the Desktop (also restarts)
#
# One process runs everything (assistant + proactive pusher + web portal), so
# exactly one instance is ever running: any previous one is stopped first.

param([switch]$Quiet, [switch]$Stop, [switch]$Shortcut)

$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$py  = "C:\Python313\python.exe"
if (-not (Test-Path $py)) { $c = Get-Command python -ErrorAction SilentlyContinue; if ($c) { $py = $c.Source } }

function Say($m) { if (-not $Quiet) { Write-Host $m } }

# stop any running instance
$old = @(Get-CimInstance Win32_Process -Filter "name='python.exe' OR name='pythonw.exe'" |
    Where-Object { $_.CommandLine -match '-m iohelper' })
foreach ($p in $old) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
if ($old.Count -gt 0) { Say "  stopped previous instance"; Start-Sleep -Seconds 2 }
if ($Stop) { Say "  iohelper stopped"; return }

if ($Shortcut) {
    if (-not (Test-Path "$dir\iohelper.ico")) { & $py "$dir\make-icon.py" | Out-Null }
    $desktop = [Environment]::GetFolderPath('Desktop')
    $ws = New-Object -ComObject WScript.Shell
    $l = $ws.CreateShortcut("$desktop\iohelper.lnk")
    $l.TargetPath = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
    $l.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$dir\run.ps1`""
    $l.WorkingDirectory = $dir
    $l.IconLocation = "$dir\iohelper.ico,0"
    $l.Description = "Start / restart the iohelper glasses assistant"
    $l.Save()
    Say "  desktop shortcut created: $desktop\iohelper.lnk"
}

Say ""
Say "  iohelper - starting"
Start-Process -FilePath $py -ArgumentList "-m", "iohelper" -WorkingDirectory $dir -WindowStyle Hidden
Start-Sleep -Seconds 4

$port = 8765
try {
    $cfg = Get-Content "$dir\config.json" -Raw -ErrorAction Stop | ConvertFrom-Json
    if ($cfg.portal -and $cfg.portal.port) { $port = $cfg.portal.port }
} catch {}
$url = "http://127.0.0.1:$port/"
try {
    $s = Invoke-RestMethod -Uri "$($url)api/status" -TimeoutSec 5
    Say ("  adb: {0}   listening: {1}   backend: {2}   wake word: '{3}'" -f $s.status.adb, $s.status.listening, $s.backend, $s.trigger)
    Say "  portal: $url"
    if (-not $Quiet) { Start-Process $url }
} catch {
    Say "  portal not answering yet - check $dir\iohelper.log"
}
if (-not $Quiet) { Start-Sleep -Seconds 4 }
