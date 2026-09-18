#requires -Version 5.1
<#
.SYNOPSIS
    联网工具实测探针：验证模型会真的去调 get_weather / search_web，并跑通真实 HTTP。

.DESCRIPTION
    对 /open/v1/chat/stream 发起两轮真实请求，逐行消费 SSE 并把原始报文落盘到
    chat-flow-evidence/web-tools/：
        1.sse.txt / 2.sse.txt   原始事件流
        summary.json            事件时间线 + 工具调用与结果（供报告引用）

    与 chat-flow-probe.ps1 的分工：那个脚本测的是记忆链路（召回 / 写回 / 洞察树），
    这个只关心「联网工具能不能被正确选中、参数对不对、真实数据有没有回来」。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\web-tools-probe.ps1
#>
param(
    [string]$BaseUrl = 'http://localhost:8366',
    [string]$Tenant = 'acme',
    [string]$User = 'u-100',
    [string]$OutDir = ''
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

if (-not $OutDir) { $OutDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'chat-flow-evidence\web-tools' }

$QUESTIONS = @(
    [pscustomobject]@{
        No       = 1
        Question = '郑州今天天气怎么样？'
        Expect   = 'get_weather → Open-Meteo geocoding + forecast'
    },
    [pscustomobject]@{
        No       = 2
        Question = '帮我搜一下郑州最近有什么新闻'
        Expect   = 'search_web → www.so.com 结果页解析'
    }
)

function Save-Utf8 {
    param([string]$Path, [string]$Text)
    $dir = Split-Path -Parent $Path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

function Invoke-ChatRound {
    param([int]$No, [string]$Question)

    $payload = @{
        message     = $Question
        sessionId   = 'web-tools-probe'
        scopes      = @('USER', 'AGENT')
        enableTools = $true
        # 打开写回：done 之后服务端还要跑两轮抽取，这段静默是 ERR_ABORTED 最容易被触发的窗口，
        # 探针要如实覆盖它（同时验证心跳帧确实把那 40 秒填满了）。
        writeback   = $true
    } | ConvertTo-Json -Depth 6 -Compress

    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(300)
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Post, "$BaseUrl/open/v1/chat/stream")
    $request.Headers.Add('X-Tenant-Id', $Tenant)
    $request.Headers.Add('X-User-Id', $User)
    $request.Headers.Add('X-Memory-Scope', 'USER')
    $request.Content = [System.Net.Http.StringContent]::new(
        $payload, [System.Text.Encoding]::UTF8, 'application/json')

    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $response = $client.SendAsync($request,
        [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
    $status = [int]$response.StatusCode
    $stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
    $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)

    $rawLines = New-Object System.Collections.Generic.List[string]
    $events = New-Object System.Collections.Generic.List[object]
    $name = $null
    $data = New-Object System.Collections.Generic.List[string]

    while (-not $reader.EndOfStream) {
        $line = $reader.ReadLine()
        if ($null -eq $line) { break }
        $rawLines.Add($line)
        if ($line.StartsWith('event:')) {
            $name = $line.Substring(6).Trim()
        }
        elseif ($line.StartsWith('data:')) {
            $data.Add($line.Substring(5).Trim())
        }
        elseif (($line.Trim() -eq '') -and $name) {
            $events.Add([pscustomobject]@{
                    index = $events.Count
                    name  = $name
                    ms    = $watch.ElapsedMilliseconds
                    json  = ($data -join "`n")
                })
            $name = $null
            $data = New-Object System.Collections.Generic.List[string]
        }
    }
    $watch.Stop()
    $reader.Dispose()
    $client.Dispose()

    Save-Utf8 (Join-Path $OutDir ("{0}.sse.txt" -f $No)) ($rawLines -join "`n")

    return [pscustomobject]@{
        No      = $No
        Status  = $status
        TotalMs = $watch.ElapsedMilliseconds
        Events  = $events
    }
}

function Show-Round {
    param($Result, [string]$Question, [string]$Expect)

    $meta = $Result.Events | Where-Object { $_.name -eq 'meta' } | Select-Object -First 1
    $done = $Result.Events | Where-Object { $_.name -eq 'done' } | Select-Object -First 1
    $errors = @($Result.Events | Where-Object { $_.name -eq 'error' })
    $toolCalls = @($Result.Events | Where-Object { $_.name -eq 'tool_call' })

    Write-Host ""
    Write-Host ("=== 第 {0} 轮 HTTP {1} 总耗时 {2}ms ===" -f $Result.No, $Result.Status, $Result.TotalMs)
    Write-Host ("  提问：{0}" -f $Question)
    Write-Host ("  期望：{0}" -f $Expect)
    if ($meta) {
        $metaJson = $meta.json | ConvertFrom-Json
        Write-Host ("  meta：{0} 模型={1} 工具数={2} 注入记忆={3}" -f `
                $meta.ms, $(if ($metaJson.degraded) { '离线降级' } else { '真实模型' }), `
                $metaJson.tools.Count, $metaJson.memoryCount)
    }
    if ($toolCalls.Count -eq 0) {
        Write-Host "  !! 本轮没有发生任何工具调用" -ForegroundColor Yellow
    }
    foreach ($call in $toolCalls) {
        $callJson = $call.json | ConvertFrom-Json
        $resultEvent = $Result.Events |
            Where-Object { $_.name -eq 'tool_result' -and ($_.json | ConvertFrom-Json).id -eq $callJson.id } |
            Select-Object -First 1
        $resultJson = if ($resultEvent) { $resultEvent.json | ConvertFrom-Json } else { $null }
        Write-Host ("  → {0} 参数={1}" -f $callJson.name, $callJson.arguments) -ForegroundColor Cyan
        if ($resultJson) {
            Write-Host ("    结果 ok={0} 耗时={1}ms" -f $resultJson.ok, $resultJson.elapsedMs)
            Write-Host ("    {0}" -f $resultJson.result.Substring(0, [Math]::Min(400, $resultJson.result.Length)))
        }
        else {
            Write-Host "    结果：未收到 tool_result（可能被工具超时掐断）" -ForegroundColor Yellow
        }
    }

    $content = New-Object System.Text.StringBuilder
    foreach ($e in @($Result.Events | Where-Object { $_.name -eq 'delta' })) {
        [void]$content.Append(($e.json | ConvertFrom-Json).text)
    }
    Write-Host ("  正文（{0} 字）：{1}" -f $content.Length, $content.ToString())
    if ($done) {
        $doneJson = $done.json | ConvertFrom-Json
        Write-Host ("  done：{0}ms 轮数={1} 工具={2} 次" -f `
                $doneJson.elapsedMs, $doneJson.rounds, $doneJson.toolCalls)
    }
    if ($errors.Count -gt 0) {
        foreach ($e in $errors) {
            $errJson = $e.json | ConvertFrom-Json
            Write-Host ("  error：[{0}] {1}" -f $errJson.stage, $errJson.message) -ForegroundColor Red
        }
    }

    return [pscustomobject]@{
        no         = $Result.No
        question   = $Question
        expect     = $Expect
        status     = $Result.Status
        totalMs    = $Result.TotalMs
        toolCalls  = $toolCalls.Count
        contentLen = $content.Length
        errors     = $errors.Count
        answer     = $content.ToString()
    }
}

$summaries = New-Object System.Collections.Generic.List[object]
foreach ($item in $QUESTIONS) {
    $result = Invoke-ChatRound -No $item.No -Question $item.Question
    $summaries.Add((Show-Round -Result $result -Question $item.Question -Expect $item.Expect))
}

Save-Utf8 (Join-Path $OutDir 'summary.json') (ConvertTo-Json -Depth 6 $summaries)
Write-Host ""
Write-Host ("证据已落盘：{0}" -f $OutDir)