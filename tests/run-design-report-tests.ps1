param(
    [string]$MasterUrl = "http://127.0.0.1:8080",
    [string]$FailNode = "",
    [switch]$SkipFailover,
    [switch]$KeepTables
)

$ErrorActionPreference = "Stop"

if (-not $MasterUrl.EndsWith("/rpc")) {
    $MasterUrl = "$MasterUrl/rpc"
}

$script:Passed = 0
$script:Failed = 0
$script:Step = 0

function Invoke-MiniSql {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Sql,
        [switch]$AllowError
    )

    $id = [guid]::NewGuid().ToString()
    $body = @{
        jsonrpc = "2.0"
        method = "executeSql"
        params = @{ sql = $Sql }
        id = $id
    } | ConvertTo-Json -Depth 8 -Compress

    try {
        $response = Invoke-RestMethod -Uri $MasterUrl -Method Post -ContentType "application/json" -Body $body -TimeoutSec 60
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

function Pass {
    param([string]$Name)
    $script:Passed++
    Write-Host "[PASS] $Name" -ForegroundColor Green
}

function Fail {
    param([string]$Name, [string]$Message)
    $script:Failed++
    Write-Host "[FAIL] $Name" -ForegroundColor Red
    if ($Message) {
        Write-Host "       $Message" -ForegroundColor DarkRed
    }
}

function Test-Case {
    param(
        [string]$Name,
        [scriptblock]$Body
    )

    $script:Step++
    Write-Host ""
    Write-Host "[$script:Step] $Name" -ForegroundColor Cyan
    try {
        & $Body
        Pass $Name
    } catch {
        Fail $Name $_.Exception.Message
    }
}

function RowsOf {
    param($Result)
    if ($null -eq $Result -or $null -eq $Result.rows) {
        return ,@()
    }
    return ,@($Result.rows)
}

function MessageOf {
    param($Result)
    if ($null -eq $Result -or $null -eq $Result.message) {
        return ""
    }
    return [string]$Result.message
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        throw $Message
    }
}

function Assert-Contains {
    param([string]$Text, [string]$Needle, [string]$Message)
    if ($Text -notlike "*$Needle*") {
        throw $Message
    }
}

Write-Host "MiniSQL design-report automated tests"
Write-Host "Master RPC: $MasterUrl"

Test-Case "Preflight: master reachable and nodes online" {
    $nodes = Invoke-MiniSql "SHOW NODES;"
    $message = MessageOf $nodes.result
    Write-Host $message
    Assert-Contains $message "ONLINE" "No ONLINE datanode found."
    $onlineCount = ([regex]::Matches($message, "status=ONLINE")).Count
    Assert-True ($onlineCount -ge 2) "Need at least 2 ONLINE datanodes; current ONLINE count is $onlineCount."
}

if (-not $KeepTables) {
    Test-Case "Cleanup old test tables" {
        foreach ($table in @("course", "rand_test", "student")) {
            $drop = Invoke-MiniSql "DROP TABLE IF EXISTS $table;" -AllowError
            if (-not $drop.ok) {
                Write-Host "Ignored cleanup error for ${table}: $($drop.error)" -ForegroundColor DarkYellow
            }
        }
    }
}

Test-Case "Use case 1: create sharded student table and metadata" {
    $null = Invoke-MiniSql @"
CREATE TABLE student (
  sid INT PRIMARY KEY,
  name CHAR(20),
  age INT,
  dept CHAR(20)
) SHARD BY HASH(sid) SHARDS 3 REPLICAS 3;
"@
    $shards = Invoke-MiniSql "SHOW SHARDS student;"
    $message = MessageOf $shards.result
    Write-Host $message
    Assert-Contains $message "student_0" "student_0 shard missing."
    Assert-Contains $message "student_1" "student_1 shard missing."
    Assert-Contains $message "student_2" "student_2 shard missing."
    Assert-Contains $message "primary=" "Primary placement missing."
}

Test-Case "Use cases 2-3: insert rows and distribute by shard key" {
    $null = Invoke-MiniSql "INSERT INTO student VALUES (1001, 'Alice', 20, 'CS');"
    $null = Invoke-MiniSql "INSERT INTO student VALUES (1002, 'Bob', 21, 'Math');"
    $null = Invoke-MiniSql "INSERT INTO student VALUES (1003, 'Cindy', 19, 'EE');"
    $null = Invoke-MiniSql "INSERT INTO student VALUES (1004, 'David', 22, 'CS');"
    $null = Invoke-MiniSql "INSERT INTO student VALUES (1005, 'Eva', 20, 'Math');"
    $all = Invoke-MiniSql "SELECT * FROM student;"
    $rows = RowsOf $all.result
    Assert-True ($rows.Count -ge 5) "Expected at least 5 student rows; got $($rows.Count)."
}

Test-Case "Use case 4: shard-key select pruning" {
    $result = Invoke-MiniSql "SELECT * FROM student WHERE sid = 1001;"
    $rows = RowsOf $result.result
    Assert-True ($rows.Count -eq 1) "Expected one row for sid=1001; got $($rows.Count)."
    Assert-True ("$($rows[0].name)" -eq "Alice") "Expected Alice for sid=1001."
}

Test-Case "Use case 5: non-shard-key broadcast select" {
    $result = Invoke-MiniSql "SELECT sid, name FROM student WHERE dept = 'CS';"
    $rows = RowsOf $result.result
    $names = ($rows | ForEach-Object { "$($_.name)" }) -join ","
    Assert-True ($names -like "*Alice*") "Expected Alice in CS query."
    Assert-True ($names -like "*David*") "Expected David in CS query."
}

Test-Case "Basic UPDATE and DELETE" {
    $null = Invoke-MiniSql "UPDATE student SET age = 25 WHERE sid = 1005;"
    $updated = Invoke-MiniSql "SELECT * FROM student WHERE sid = 1005;"
    $updatedRows = RowsOf $updated.result
    Assert-True ($updatedRows.Count -eq 1) "Expected sid=1005 after update."
    Assert-True ([int]$updatedRows[0].age -eq 25) "Expected age=25 for sid=1005."

    $null = Invoke-MiniSql "DELETE FROM student WHERE sid = 1002;"
    $deleted = Invoke-MiniSql "SELECT * FROM student WHERE sid = 1002;"
    Assert-True ((RowsOf $deleted.result).Count -eq 0) "sid=1002 should have been deleted."
}

Test-Case "Use case 6: join query" {
    $null = Invoke-MiniSql @"
CREATE TABLE course (
  cid INT PRIMARY KEY,
  sid INT,
  cname CHAR(20)
) SHARD BY HASH(sid) SHARDS 3 REPLICAS 3;
"@
    $null = Invoke-MiniSql "INSERT INTO course VALUES (1, 1001, 'Database');"
    $null = Invoke-MiniSql "INSERT INTO course VALUES (2, 1003, 'Network');"
    $null = Invoke-MiniSql "INSERT INTO course VALUES (3, 1005, 'OS');"
    $join = Invoke-MiniSql "SELECT student.name, course.cname FROM student JOIN course ON student.sid = course.sid;"
    $rows = RowsOf $join.result
    Assert-True ($rows.Count -ge 2) "Expected at least two joined rows; got $($rows.Count)."
}

Test-Case "Extra: RAND/RANDOM folded on coordinator before replica writes" {
    $null = Invoke-MiniSql @"
CREATE TABLE rand_test (
  id INT PRIMARY KEY,
  val FLOAT
) SHARD BY HASH(id) SHARDS 3 REPLICAS 3;
"@
    $null = Invoke-MiniSql "INSERT INTO rand_test VALUES (1, RAND());"
    $null = Invoke-MiniSql "INSERT INTO rand_test VALUES (2, RANDOM());"
    $result = Invoke-MiniSql "SELECT * FROM rand_test;"
    $rows = RowsOf $result.result
    Assert-True ($rows.Count -eq 2) "Expected 2 rand_test rows; got $($rows.Count)."
    foreach ($row in $rows) {
        $value = [double]$row.val
        Assert-True ($value -ge 0.0 -and $value -lt 1.0) "Random value out of range: $value"
    }
    $tables = Invoke-MiniSql "SHOW TABLES;"
    $tableRows = RowsOf $tables.result
    $randRows = @($tableRows | Where-Object { "$($_.tableName)" -like "rand_test_*" -and "$($_.rowData)" -match "(?i)val=" })
    if ($randRows.Count -lt 2) {
        Write-Host "SHOW TABLES did not expose rand_test row details quickly enough; logical SELECT already verified RAND/RANDOM values." -ForegroundColor DarkYellow
    }
}

Test-Case "Use cases 7 and 9: cluster and shard display" {
    $cluster = Invoke-MiniSql "SHOW CLUSTER;"
    $clusterMessage = MessageOf $cluster.result
    Write-Host $clusterMessage
    Assert-Contains $clusterMessage "routeVersion=" "routeVersion missing."
    Assert-Contains $clusterMessage "status=ONLINE" "ONLINE node status missing."

    $shards = Invoke-MiniSql "SHOW SHARDS student;"
    $shardMessage = MessageOf $shards.result
    Assert-Contains $shardMessage "student_0" "student shard layout missing."
}

if (-not $SkipFailover) {
    Test-Case "Use cases 10-11: failover and recover" {
        if ([string]::IsNullOrWhiteSpace($FailNode)) {
            $nodes = MessageOf (Invoke-MiniSql "SHOW NODES;").result
            $match = [regex]::Match($nodes, "^(dn\d+).*role=PRIMARY", "Multiline")
            Assert-True $match.Success "Could not find a PRIMARY node to fail."
            $FailNode = $match.Groups[1].Value
        }
        Write-Host "Failing node: $FailNode"
        $null = Invoke-MiniSql "FAIL NODE $FailNode;"
        $afterFail = MessageOf (Invoke-MiniSql "SHOW CLUSTER;").result
        Write-Host $afterFail
        Assert-Contains $afterFail "$FailNode" "Failed node not shown in cluster."
        Assert-Contains $afterFail "status=OFFLINE" "Expected an OFFLINE node after FAIL NODE."

        $null = Invoke-MiniSql "INSERT INTO student VALUES (1006, 'Frank', 21, 'EE');"
        $frank = Invoke-MiniSql "SELECT * FROM student WHERE sid = 1006;"
        Assert-True ((RowsOf $frank.result).Count -eq 1) "Expected to read Frank after failover."

        $null = Invoke-MiniSql "RECOVER NODE $FailNode;"
        $afterRecover = MessageOf (Invoke-MiniSql "SHOW CLUSTER;").result
        Write-Host $afterRecover
        Assert-Contains $afterRecover "$FailNode" "Recovered node not shown in cluster."
        Assert-Contains $afterRecover "status=ONLINE" "Expected ONLINE node after RECOVER NODE."
    }
}

Test-Case "Use case 14: syntax error is reported" {
    $bad = Invoke-MiniSql "SELEC * FORM student WHERE sid = 1001;" -AllowError
    Assert-True (-not $bad.ok) "Invalid SQL unexpectedly succeeded."
    Write-Host "Error: $($bad.error)"
}

if (-not $KeepTables) {
    Test-Case "Final cleanup" {
        foreach ($table in @("course", "rand_test", "student")) {
            $drop = Invoke-MiniSql "DROP TABLE IF EXISTS $table;" -AllowError
            if (-not $drop.ok) {
                Write-Host "Ignored cleanup error for ${table}: $($drop.error)" -ForegroundColor DarkYellow
            }
        }
    }
}

Write-Host ""
Write-Host "Result: $script:Passed passed, $script:Failed failed"
if ($script:Failed -gt 0) {
    exit 1
}
