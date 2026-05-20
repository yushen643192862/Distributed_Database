param(
    [string]$MasterUrl = "http://127.0.0.1:8080",
    [int]$ForcedPrimaryReads = 20,
    [int]$BalancedReads = 5,
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

function Read-NodeStats {
    $result = (Invoke-MiniSql "SHOW NODES;").result
    $stats = @{}
    foreach ($row in (RowsOf $result)) {
        $nodeId = "$($row.nodeId)"
        if ([string]::IsNullOrWhiteSpace($nodeId)) {
            $nodeId = "$($row.NodeId)"
        }
        if ([string]::IsNullOrWhiteSpace($nodeId)) {
            continue
        }
        $stats[$nodeId] = [pscustomobject]@{
            NodeId = $nodeId
            Status = "$($row.status)"
            Role = "$($row.role)"
            Partner = "$($row.partner)"
            Reads = [long]$row.reads
            Writes = [long]$row.writes
        }
    }
    return $stats
}

function Get-NodeReads([hashtable]$Stats, [string]$NodeId) {
    Assert-True ($Stats.ContainsKey($NodeId)) "SHOW NODES did not contain node $NodeId."
    return [long]$Stats[$NodeId].Reads
}

function Get-LbShardPlacement {
    $result = (Invoke-MiniSql "SHOW SHARDS lb_user;").result
    $rows = RowsOf $result
    $row = $rows | Where-Object { "$($_.shardName)" -eq "lb_user_0" } | Select-Object -First 1
    Assert-True ($null -ne $row) "Could not find lb_user_0 placement."
    $replicas = @(
        "$($row.replicas)" -replace '^\[|\]$', '' -split "," |
            ForEach-Object { $_.Trim() } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    Assert-True ($replicas.Count -ge 1) "lb_user_0 has no replica. Start at least one replica datanode."
    return [pscustomobject]@{
        Primary = "$($row.primary)"
        Replica = $replicas[0]
    }
}

function Wait-NodeOnline([string]$NodeId) {
    for ($i = 0; $i -lt 30; $i++) {
        $stats = Read-NodeStats
        if ($stats.ContainsKey($NodeId) -and $stats[$NodeId].Status -eq "ONLINE") {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "Timed out waiting for $NodeId to become ONLINE."
}

function Select-LbRow([int]$Times) {
    for ($i = 0; $i -lt $Times; $i++) {
        $result = Invoke-MiniSql "SELECT * FROM lb_user WHERE uid = 1;"
        $rows = RowsOf $result.result
        Assert-True ($rows.Count -eq 1) "Expected one row for uid=1; got $($rows.Count)."
    }
}

Write-Host "MiniSQL read load-balance test"
Write-Host "Master RPC: $MasterUrl"

$placement = $null
$replicaFailed = $false

try {
    Show-Step "Preflight"
    $nodeRows = RowsOf (Invoke-MiniSql "SHOW NODES;").result
    $onlineCount = @($nodeRows | Where-Object { "$($_.status)" -eq "ONLINE" }).Count
    $nodeRows | Format-Table | Out-String | Write-Host
    Assert-True ($onlineCount -ge 2) "Need at least 2 ONLINE datanodes."

    Show-Step "Prepare table"
    $drop = Invoke-MiniSql "DROP TABLE IF EXISTS lb_user;" -AllowError
    if (-not $drop.ok) {
        Write-Host "Ignored cleanup error: $($drop.error)" -ForegroundColor DarkYellow
    }
    $null = Invoke-MiniSql @"
CREATE TABLE lb_user (
  uid INT PRIMARY KEY,
  name CHAR(20)
) SHARD BY HASH(uid) SHARDS 1 REPLICAS 2;
"@
    $null = Invoke-MiniSql "INSERT INTO lb_user VALUES (1, 'Alice');"
    $placement = Get-LbShardPlacement
    Write-Host "primary=$($placement.Primary), replica=$($placement.Replica)"

    Show-Step "Force reads onto primary by failing replica"
    $null = Invoke-MiniSql "FAIL NODE $($placement.Replica);"
    $replicaFailed = $true
    Select-LbRow $ForcedPrimaryReads
    $afterForced = Read-NodeStats
    $primaryAfterForced = Get-NodeReads $afterForced $placement.Primary
    $replicaAfterForced = Get-NodeReads $afterForced $placement.Replica
    Write-Host "after forced reads: primary=$primaryAfterForced replica=$replicaAfterForced"

    Show-Step "Recover replica and verify lower-read replica is selected"
    $null = Invoke-MiniSql "RECOVER NODE $($placement.Replica);"
    $replicaFailed = $false
    Wait-NodeOnline $placement.Replica
    Start-Sleep -Seconds 1

    $beforeBalanced = Read-NodeStats
    $primaryBefore = Get-NodeReads $beforeBalanced $placement.Primary
    $replicaBefore = Get-NodeReads $beforeBalanced $placement.Replica
    Write-Host "before balanced reads: primary=$primaryBefore replica=$replicaBefore"
    Assert-True ($primaryBefore -gt $replicaBefore) "Expected primary reads to be greater than replica reads before balance verification."

    Select-LbRow $BalancedReads

    $afterBalanced = Read-NodeStats
    $primaryAfter = Get-NodeReads $afterBalanced $placement.Primary
    $replicaAfter = Get-NodeReads $afterBalanced $placement.Replica
    $primaryDelta = $primaryAfter - $primaryBefore
    $replicaDelta = $replicaAfter - $replicaBefore
    Write-Host "after balanced reads: primary=$primaryAfter replica=$replicaAfter"
    Write-Host "delta: primary=$primaryDelta replica=$replicaDelta"

    Assert-True ($replicaDelta -ge $BalancedReads) "Expected replica to serve at least $BalancedReads reads; delta was $replicaDelta."
    Assert-True ($primaryDelta -eq 0) "Expected primary not to receive balanced reads while replica score is lower; delta was $primaryDelta."
} finally {
    if ($replicaFailed -and $placement -ne $null) {
        Write-Host ""
        Write-Host "Recovering failed replica $($placement.Replica)..." -ForegroundColor DarkYellow
        $null = Invoke-MiniSql "RECOVER NODE $($placement.Replica);" -AllowError
    }
    if (-not $KeepTable) {
        Write-Host ""
        Write-Host "Cleanup" -ForegroundColor Cyan
        $null = Invoke-MiniSql "DROP TABLE IF EXISTS lb_user;" -AllowError
    }
}

Write-Host ""
Write-Host "[PASS] read load-balance test completed" -ForegroundColor Green
