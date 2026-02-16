# check_logs.ps1 - Reusable script to fetch and display Render logs
# Usage: powershell -ExecutionPolicy Bypass -File check_logs.ps1
# Optional: powershell -ExecutionPolicy Bypass -File check_logs.ps1 -Lines 30

param([int]$Lines = 50)

$headers = @{ 
    "Authorization" = "Bearer rnd_N9SHgRuTTrOkLau31gdkrPRVwpwh"
    "Accept" = "application/json"
}

$url = "https://api.render.com/v1/logs?resource=srv-d66tmo15pdvs73c5j9t0&ownerId=tea-d66t4a5um26s73851lcg&limit=200&type=app"

# Always overwrite the same file
$response = Invoke-RestMethod -Uri $url -Headers $headers
$response | ConvertTo-Json -Depth 10 | Out-File -FilePath "logs.json" -Encoding utf8 -Force

# Parse and display game-relevant messages
$msgs = @()
foreach ($log in $response.logs) {
    $m = $log.message
    if ($m -and $m.Length -gt 5 -and $m -notmatch "^#\d|^sha256|extracting|CACHED|Pushing|Upload|exporting|preparing|writing cache|DONE \d") {
        $ts = $log.timestamp.Substring(11, 8)
        $msgs += "$ts $m"
    }
}

Write-Host "=== Last $Lines game messages ===" -ForegroundColor Cyan
$msgs | Select-Object -Last $Lines | ForEach-Object { Write-Host $_ }
Write-Host "=== Total: $($msgs.Count) messages ===" -ForegroundColor Cyan
