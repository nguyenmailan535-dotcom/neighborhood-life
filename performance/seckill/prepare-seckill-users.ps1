param(
    [int]$UserCount = 100000,
    [long]$VoucherId = 9001,
    [int]$Stock = 100000,
    [int]$RedisDatabase = 6,
    [string]$RedisCli = "redis-cli",
    [string]$OutputFile = "performance/results/seckill-users.csv"
)

$ErrorActionPreference = "Stop"
$outputPath = [System.IO.Path]::GetFullPath($OutputFile)
$outputDirectory = [System.IO.Path]::GetDirectoryName($outputPath)
[System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null

& $RedisCli -n $RedisDatabase DEL "seckill:stock:$VoucherId" "seckill:order:$VoucherId" "seckill:pending:orders" | Out-Null
& $RedisCli -n $RedisDatabase SET "seckill:stock:$VoucherId" $Stock | Out-Null

$rateKeys = & $RedisCli -n $RedisDatabase --scan --pattern "rate:sliding:*"
foreach ($rateKey in $rateKeys) {
    if ($rateKey) {
        & $RedisCli -n $RedisDatabase DEL $rateKey | Out-Null
    }
}

$csvWriter = [System.IO.StreamWriter]::new($outputPath, $false, [System.Text.UTF8Encoding]::new($false))
$pipePath = [System.IO.Path]::GetTempFileName()
$pipeWriter = [System.IO.StreamWriter]::new($pipePath, $false, [System.Text.UTF8Encoding]::new($false))

function Write-RespCommand {
    param([System.IO.StreamWriter]$Writer, [string[]]$Parts)
    $Writer.Write("*{0}`r`n", $Parts.Count)
    foreach ($part in $Parts) {
        $bytes = [System.Text.Encoding]::UTF8.GetByteCount($part)
        $Writer.Write("`${0}`r`n{1}`r`n", $bytes, $part)
    }
}

try {
    for ($index = 1; $index -le $UserCount; $index++) {
        $token = "seckill-perf-$index"
        $userId = 1000000 + $index
        $octet2 = [math]::Floor(($index - 1) / 65025) % 254 + 1
        $octet3 = [math]::Floor(($index - 1) / 255) % 254 + 1
        $octet4 = (($index - 1) % 254) + 1
        $csvWriter.WriteLine("$token,10.$octet2.$octet3.$octet4")
        Write-RespCommand $pipeWriter @("HSET", "login:token:$token", "id", "$userId", "nickName", "perf-$index")
        Write-RespCommand $pipeWriter @("EXPIRE", "login:token:$token", "7200")
    }
}
finally {
    $csvWriter.Dispose()
    $pipeWriter.Dispose()
}

try {
    Get-Content -LiteralPath $pipePath -Raw | & $RedisCli -n $RedisDatabase --pipe
}
finally {
    Remove-Item -LiteralPath $pipePath -Force
}

Write-Host "Prepared $UserCount unique users and voucher $VoucherId with Redis stock $Stock."
Write-Host "Credentials: $outputPath"
