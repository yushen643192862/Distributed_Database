param(
    [string]$MasterUrl = "http://127.0.0.1:8080",
    [string]$TableName = "reshard_user",
    [int]$ExpectedRows = 50,
    [int]$PollSeconds = 3,
    [int]$TimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"

if (-not $MasterUrl.EndsWith("/rpc")) {
    $MasterUrl = "$MasterUrl/rpc"
}

function Invoke-MiniSql {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $body = @{
        jsonrpc = "2.0"
        method = "executeSql"
        params = @{ sql = $Sql }
        id = [guid]::NewGuid().ToString()
    } | ConvertTo-Json -Depth 8 -Compress

    $response = Invoke-RestMethod -Uri $MasterUrl -Method Post -ContentType "application/json" -Body $body -TimeoutSec 180
    if ($response.error) {
        $message = if ($response.error.message) { $response.error.message } else { "$($response.error)" }
        throw "SQL failed: $Sql`n$message"
    }
    return $response.result
}

function MessageOf($Result) {
    if ($null -eq $Result -or $null -eq $Result.message) {
        return ""
    }
    return [string]$Result.message
}

function RowsOf($Result) {
    if ($null -eq $Result -or $null -eq $Result.rows) {
        return ,@()
    }
    return ,@($Result.rows)
}

function PrimaryCount {
    $nodes = MessageOf (Invoke-MiniSql "SHOW NODES;")
    return ([regex]::Matches($nodes, "status=ONLINE.*role=PRIMARY")).Count
}

function ShardCount {
    $shards = MessageOf (Invoke-MiniSql "SHOW SHARDS $TableName;")
    return ([regex]::Matches($shards, "$([regex]::Escape($TableName))_\d+")).Count
}

function RowCount {
    $rows = RowsOf (Invoke-MiniSql "SELECT * FROM $TableName;")
    return $rows.Count
}

Write-Host "Watch reshard"
Write-Host "Master RPC: $MasterUrl"
Write-Host "Table: $TableName"
Write-Host "Expected rows: $ExpectedRows"
Write-Host ""

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$reshardTriggered = $false

while ((Get-Date) -lt $deadline) {
    $primaryCount = PrimaryCount
    $shardCount = ShardCount
    $rowCount = RowCount

    Write-Host "primary=$primaryCount shard=$shardCount rows=$rowCount"

    if ($ExpectedRows -gt 0 -and $rowCount -ne $ExpectedRows) {
        throw "Expected $ExpectedRows rows before/after reshard, got $rowCount."
    }

    if ($primaryCount -gt 0 -and $shardCount -ne $primaryCount) {
        Write-Host "Shard count differs from primary count; running RESHARD CLUSTER..." -ForegroundColor Yellow
        $message = MessageOf (Invoke-MiniSql "RESHARD CLUSTER;")
        Write-Host $message
        $reshardTriggered = $true
        Start-Sleep -Seconds $PollSeconds
        continue
    }

    if ($reshardTriggered -or $shardCount -eq $primaryCount) {
        $afterRows = RowCount
        if ($ExpectedRows -gt 0 -and $afterRows -ne $ExpectedRows) {
            throw "Expected $ExpectedRows rows after reshard, got $afterRows."
        }
        Write-Host ""
        Write-Host "[PASS] $TableName shard count matches online primary count and data count is valid." -ForegroundColor Green
        Write-Host (MessageOf (Invoke-MiniSql "SHOW SHARDS $TableName;"))
        exit 0
    }

    Start-Sleep -Seconds $PollSeconds
}

throw "Timed out waiting for reshard condition."
