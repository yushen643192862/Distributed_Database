param(
    [string]$MySqlHost = "frp-try.com",
    [int]$MySqlPort = 58868,
    [string]$PostgresHost = "frp-try.com",
    [int]$PostgresPort = 49610,
    [string]$User = "ddb_user",
    [string]$Password = "db_1234567",
    [int[]]$NodeNumbers = @(1, 2, 3, 4, 5, 6, 7, 8),
    [switch]$SkipRemoteDatabases
)

$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $projectRoot

Write-Host "Reset MiniSQL test state" -ForegroundColor Cyan
Write-Host "Project: $projectRoot"

Write-Host ""
Write-Host "Remove master metadata and local H2 data" -ForegroundColor Cyan
Remove-Item ".\data\minisql-state.bin" -Force -ErrorAction SilentlyContinue
Remove-Item ".\data\terminal-sim-state.bin" -Force -ErrorAction SilentlyContinue
Remove-Item ".\datanode\data\*.mv.db" -Force -ErrorAction SilentlyContinue
Remove-Item ".\datanode\data\*.trace.db" -Force -ErrorAction SilentlyContinue

if ($SkipRemoteDatabases) {
    Write-Host "Skipped remote database cleanup."
    exit 0
}

$mysqlDbs = $NodeNumbers | Where-Object { $_ % 2 -eq 1 } | ForEach-Object { "minisql_dn$_" }
$postgresDbs = $NodeNumbers | Where-Object { $_ % 2 -eq 0 } | ForEach-Object { "minisql_dn$_" }

Write-Host ""
Write-Host "Drop MySQL test databases" -ForegroundColor Cyan
$mysql = Get-Command mysql -ErrorAction SilentlyContinue
if ($mysql) {
    $mysqlSql = ($mysqlDbs | ForEach-Object { "DROP DATABASE IF EXISTS ``$_``;" }) -join " "
    $env:MYSQL_PWD = $Password
    & mysql -h $MySqlHost -P $MySqlPort -u $User -e $mysqlSql
    Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    Write-Host "Dropped MySQL databases: $($mysqlDbs -join ', ')"
} else {
    Write-Host "mysql CLI not found. Run this in MySQL manually:" -ForegroundColor Yellow
    foreach ($db in $mysqlDbs) {
        Write-Host "DROP DATABASE IF EXISTS ``$db``;"
    }
}

Write-Host ""
Write-Host "Drop PostgreSQL test databases" -ForegroundColor Cyan
$psql = Get-Command psql -ErrorAction SilentlyContinue
if ($psql) {
    $env:PGPASSWORD = $Password
    foreach ($db in $postgresDbs) {
        & psql -h $PostgresHost -p $PostgresPort -U $User -d postgres -c "DROP DATABASE IF EXISTS `"$db`";"
    }
    Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue
    Write-Host "Dropped PostgreSQL databases: $($postgresDbs -join ', ')"
} else {
    Write-Host "psql CLI not found. Run this in PostgreSQL manually:" -ForegroundColor Yellow
    foreach ($db in $postgresDbs) {
        Write-Host "DROP DATABASE IF EXISTS ""$db"";"
    }
}

Write-Host ""
Write-Host "[OK] Reset complete. Restart master first, then datanodes." -ForegroundColor Green
