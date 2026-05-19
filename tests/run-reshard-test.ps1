param(
    [string]$MasterUrl = "http://127.0.0.1:8080",
    [int]$ExpectedPrimaryCount = 0,
    [switch]$KeepTable
)

$ErrorActionPreference = "Stop"

if (-not $MasterUrl.EndsWith("/rpc")) {
    $MasterUrl = "$MasterUrl/rpc"
}

function Invoke-MiniSql {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Sql,
        [switch]$AllowError
    )

    $body = @{
        jsonrpc = "2.0"
        method = "executeSql"
        params = @{ sql = $Sql }
        id = [guid]::NewGuid().ToString()
    } | ConvertTo-Json -Depth 8 -Compress

    try {
        $response = Invoke-RestMethod -Uri $MasterUrl -Method Post -ContentType "application/json" -Body $body -TimeoutSec 120
    } catch {
        if ($AllowError) {
            return [pscustomobject]@{ ok = $false; error = $_.Exception.Message; result = $null }
        }
        throw
    }

    if ($response.error) {
        $message = if ($response.error.message) { $response.error.message } else { "$($response.error)" }
        if ($AllowError) {
            return [pscustomobject]@{ ok = $false; error = $message; result = $null }
        }
        throw "SQL failed: $Sql`n$message"
    }
    return [pscustomobject]@{ ok = $true; error = $null; result = $response.result }
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

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw $Message
    }
}

function Show-Step([string]$Text) {
    Write-Host ""
    Write-Host "== $Text ==" -ForegroundColor Cyan
}

function PrimaryCount() {
    $nodes = MessageOf (Invoke-MiniSql "SHOW NODES;").result
    return ([regex]::Matches($nodes, "role=PRIMARY")).Count
}

function ShardCount() {
    $shards = MessageOf (Invoke-MiniSql "SHOW SHARDS reshard_user;").result
    return ([regex]::Matches($shards, "reshard_user_\d+")).Count
}

function Wait-ForPrimaryCount([int]$Count) {
    if ($Count -le 0) {
        return
    }
    Show-Step "Wait for $Count primary nodes"
    for ($i = 0; $i -lt 60; $i++) {
        $current = PrimaryCount
        Write-Host "primary count: $current"
        if ($current -ge $Count) {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "Timed out waiting for $Count primary nodes."
}

Write-Host "MiniSQL reshard automated test"
Write-Host "Master RPC: $MasterUrl"

Show-Step "Preflight"
$nodes = MessageOf (Invoke-MiniSql "SHOW NODES;").result
Write-Host $nodes
Assert-True (($nodes -like "*ONLINE*")) "No ONLINE nodes."

Show-Step "Cleanup old reshard_user"
$drop = Invoke-MiniSql "DROP TABLE IF EXISTS reshard_user;" -AllowError
if (-not $drop.ok) {
    Write-Host "Ignored cleanup error: $($drop.error)" -ForegroundColor DarkYellow
}

Show-Step "Create 2-shard table"
$null = Invoke-MiniSql @"
CREATE TABLE reshard_user (
  uid INT PRIMARY KEY,
  name CHAR(20),
  age INT,
  dept CHAR(20)
) SHARD BY HASH(uid) SHARDS 2 REPLICAS 1;
"@
Write-Host (MessageOf (Invoke-MiniSql "SHOW SHARDS reshard_user;").result)

Show-Step "Insert 50 rows"
$values = for ($i = 1; $i -le 50; $i++) {
    $dept = @("CS", "Math", "EE", "AI", "DB")[($i - 1) % 5]
    $age = 18 + (($i - 1) % 10)
    "($i, 'User$($i.ToString('000'))', $age, '$dept')"
}
$insertSql = "INSERT INTO reshard_user VALUES`n  " + ($values -join ",`n  ") + ";"
$null = Invoke-MiniSql $insertSql

$all = Invoke-MiniSql "SELECT * FROM reshard_user;"
$rows = RowsOf $all.result
Assert-True ($rows.Count -eq 50) "Expected 50 rows before reshard; got $($rows.Count)."
Write-Host "rows before reshard: $($rows.Count)"
Write-Host "shards before reshard: $(ShardCount)"

Wait-ForPrimaryCount $ExpectedPrimaryCount

Show-Step "Force reshard"
$reshard = Invoke-MiniSql "RESHARD CLUSTER;"
Write-Host (MessageOf $reshard.result)
Start-Sleep -Seconds 2

$afterShardMessage = MessageOf (Invoke-MiniSql "SHOW SHARDS reshard_user;").result
Write-Host $afterShardMessage
$afterShardCount = ShardCount
$primaryCount = PrimaryCount
Write-Host "primary count: $primaryCount"
Write-Host "shards after reshard: $afterShardCount"
Assert-True ($afterShardCount -eq $primaryCount) "Expected shard count to equal primary count after reshard."

Show-Step "Verify data after reshard"
$after = Invoke-MiniSql "SELECT * FROM reshard_user;"
$afterRows = RowsOf $after.result
Assert-True ($afterRows.Count -eq 50) "Expected 50 rows after reshard; got $($afterRows.Count)."

$first = RowsOf (Invoke-MiniSql "SELECT * FROM reshard_user WHERE uid = 1;").result
$last = RowsOf (Invoke-MiniSql "SELECT * FROM reshard_user WHERE uid = 50;").result
Assert-True ($first.Count -eq 1) "Could not read uid=1 after reshard."
Assert-True ($last.Count -eq 1) "Could not read uid=50 after reshard."
Write-Host "uid=1 and uid=50 verified"

Show-Step "Physical tables"
$tables = Invoke-MiniSql "SHOW TABLES;"
RowsOf $tables.result | Where-Object { "$($_.tableName)" -like "reshard_user_*" } | Format-Table -AutoSize

if (-not $KeepTable) {
    Show-Step "Cleanup"
    $null = Invoke-MiniSql "DROP TABLE IF EXISTS reshard_user;" -AllowError
}

Write-Host ""
Write-Host "[PASS] reshard test completed" -ForegroundColor Green
