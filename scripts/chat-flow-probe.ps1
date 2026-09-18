#requires -Version 5.1
<#
.SYNOPSIS
    流式聊天全链路实测探针：检索 → 流式生成 → 工具/skill 调用 → 自动写回。

.DESCRIPTION
    对 http://localhost:8366/open/v1/chat/stream 发起真实模型请求，逐行消费 SSE，
    记录每个事件的到达时刻，并把全部证据落盘到 chat-flow-evidence/：

      before/ after-round1/ after-round2/   各阶段的记忆、洞察树、健康状态快照
      round1.sse.txt round2.sse.txt         原始事件流（未被任何解析加工过）
      timing.txt                            首字延迟与各阶段耗时的对照表
      summary.json                          结构化汇总，供报告引用

    之所以不用 Invoke-WebRequest 收流：它会把响应整体缓冲后才返回，
    "meta → 首个 delta 隔了多久" 这个思考模型最关键的指标就测不出来了。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\chat-flow-probe.ps1
#>
param(
    [string]$BaseUrl = 'http://localhost:8366',
    [string]$Tenant = 'acme',
    [string]$User = 'u-100',
    [string]$Session = '',
    [string]$OutDir = ''
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

if (-not $Session) { $Session = 'probe-' + (Get-Date -Format 'MMdd-HHmmss') }
if (-not $OutDir) { $OutDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'chat-flow-evidence' }

# 两轮场景：第一轮把"闲聊式自我介绍"和"数据问题"放在同一句里，
# 于是同一轮既要写回用户偏好，又必须调工具才能回答；第二轮才验证 skill 与记忆补全。
$ROUNDS = @(
    [pscustomobject]@{
        No       = 1
        Question = '我是林悦，做增长运营的，以后给我数据尽量用柱状图这种图表口径。最近30天的GMV是多少？'
        Expect   = '闲聊写回 + 工具调用（query_sales / run_sql / gmv_report）'
    },
    [pscustomobject]@{
        No       = 2
        Question = '帮我出一份渠道维度的日报'
        Expect   = 'skill 调用（gmv_report）+ 复用第一轮记住的展示偏好'
    }
)

# ------------------------------------------------------------------ 基础设施

function Get-TenantHeaders {
    @{
        'X-Tenant-Id'    = $Tenant
        'X-User-Id'      = $User
        'X-Memory-Scope' = 'USER'
    }
}

function Save-Utf8 {
    param([string]$Path, [string]$Text)
    $dir = Split-Path -Parent $Path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

# PS 5.1 的 Invoke-WebRequest.Content 会按 ANSI 解码中文，必须自己从原始字节转 UTF-8
function Invoke-Json {
    param([string]$Method, [string]$Path, $Body)
    $uri = "$BaseUrl$Path"
    $headers = Get-TenantHeaders
    if ($null -eq $Body) {
        $response = Invoke-WebRequest -Uri $uri -Method $Method -Headers $headers -UseBasicParsing
    }
    else {
        $json = $Body | ConvertTo-Json -Depth 6 -Compress
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        $response = Invoke-WebRequest -Uri $uri -Method $Method -Headers $headers `
            -ContentType 'application/json; charset=utf-8' -Body $bytes -UseBasicParsing
    }
    return [System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
}

function Save-Snapshot {
    param([string]$Stage)
    $dir = Join-Path $OutDir $Stage
    Save-Utf8 (Join-Path $dir 'health.json') (Invoke-Json 'GET' '/open/v1/health' $null)
    Save-Utf8 (Join-Path $dir 'memories-user.json') (Invoke-Json 'GET' '/admin/v1/memories?scope=USER' $null)
    Save-Utf8 (Join-Path $dir 'memories-agent.json') (Invoke-Json 'GET' '/admin/v1/memories?scope=AGENT' $null)
    Save-Utf8 (Join-Path $dir 'insight-user.json') (Invoke-Json 'GET' '/admin/v1/insight?scope=USER' $null)
    Save-Utf8 (Join-Path $dir 'insight-agent.json') (Invoke-Json 'GET' '/admin/v1/insight?scope=AGENT' $null)
    Write-Host ("  [快照] {0} 已保存" -f $Stage)
}

# ------------------------------------------------------------------ SSE 消费

function Invoke-ChatRound {
    param([int]$Round, [string]$Question)

    $payload = @{
        message     = $Question
        sessionId   = $Session
        scopes      = @('USER', 'AGENT')
        enableTools = $true
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
    $contentType = $response.Content.Headers.ContentType.ToString()
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

    $raw = ($rawLines -join "`n")
    Save-Utf8 (Join-Path $OutDir ("round{0}.sse.txt" -f $Round)) $raw

    return [pscustomobject]@{
        Round       = $Round
        Question    = $Question
        Status      = $status
        ContentType = $contentType
        TotalMs     = $watch.ElapsedMilliseconds
        Events      = $events
        Raw         = $raw
    }
}

# ------------------------------------------------------------------ 汇总

function Get-RoundSummary {
    param($Result)

    $meta = $Result.Events | Where-Object { $_.name -eq 'meta' } | Select-Object -First 1
    $firstReasoning = $Result.Events | Where-Object { $_.name -eq 'reasoning' } | Select-Object -First 1
    $firstDelta = $Result.Events | Where-Object { $_.name -eq 'delta' } | Select-Object -First 1
    $done = $Result.Events | Where-Object { $_.name -eq 'done' } | Select-Object -First 1
    $writeback = $Result.Events | Where-Object { $_.name -eq 'writeback' } | Select-Object -First 1
    $errors = @($Result.Events | Where-Object { $_.name -eq 'error' })

    $reasoningEvents = @($Result.Events | Where-Object { $_.name -eq 'reasoning' })
    $deltaEvents = @($Result.Events | Where-Object { $_.name -eq 'delta' })
    $toolCalls = @($Result.Events | Where-Object { $_.name -eq 'tool_call' })

    $reasoningChars = 0
    foreach ($e in $reasoningEvents) { $reasoningChars += ($e.json | ConvertFrom-Json).text.Length }

    $contentChars = 0
    $content = New-Object System.Text.StringBuilder
    foreach ($e in $deltaEvents) {
        $text = ($e.json | ConvertFrom-Json).text
        [void]$content.Append($text)
        $contentChars += $text.Length
    }

    $calls = New-Object System.Collections.Generic.List[object]
    foreach ($c in $toolCalls) {
        $call = $c.json | ConvertFrom-Json
        # 注意：PowerShell 变量名大小写不敏感，这里绝不能叫 $result，否则会覆盖函数参数 $Result
        $resultEvent = $Result.Events |
            Where-Object { $_.name -eq 'tool_result' -and ($_.json | ConvertFrom-Json).id -eq $call.id } |
            Select-Object -First 1
        $resultJson = if ($resultEvent) { $resultEvent.json | ConvertFrom-Json } else { $null }
        $calls.Add([pscustomobject]@{
                round      = $call.round
                name       = $call.name
                type       = $call.type
                arguments  = $call.arguments
                ok         = if ($resultJson) { $resultJson.ok } else { $false }
                elapsedMs  = if ($resultJson) { $resultJson.elapsedMs } else { $null }
                resultHead = if ($resultJson) { $resultJson.result.Substring(0, [Math]::Min(300, $resultJson.result.Length)) } else { '' }
            })
    }

    $metaJson = if ($meta) { $meta.json | ConvertFrom-Json } else { $null }

    return [pscustomobject]@{
        round            = $Result.Round
        question         = $Result.Question
        status           = $Result.Status
        contentType      = $Result.ContentType
        sessionId        = if ($metaJson) { $metaJson.sessionId } else { '' }
        degraded         = if ($metaJson) { $metaJson.degraded } else { $null }
        provider         = if ($metaJson) { $metaJson.provider } else { '' }
        memoryCount      = if ($metaJson) { $metaJson.memoryCount } else { 0 }
        insightCount     = if ($metaJson) { $metaJson.insightCount } else { 0 }
        estimatedTokens  = if ($metaJson) { $metaJson.estimatedTokens } else { 0 }
        truncated        = if ($metaJson) { $metaJson.truncated } else { $null }
        registeredTools  = if ($metaJson) { @($metaJson.tools) } else { @() }
        metaMs           = if ($meta) { $meta.ms } else { $null }
        firstReasoningMs = if ($firstReasoning) { $firstReasoning.ms } else { $null }
        firstDeltaMs     = if ($firstDelta) { $firstDelta.ms } else { $null }
        reasoningChars   = $reasoningChars
        reasoningEvents  = $reasoningEvents.Count
        contentChars     = $contentChars
        deltaEvents      = $deltaEvents.Count
        content          = $content.ToString()
        calls            = $calls
        writeback        = if ($writeback) { $writeback.json | ConvertFrom-Json } else { $null }
        done             = if ($done) { $done.json | ConvertFrom-Json } else { $null }
        errors           = @($errors | ForEach-Object { $_.json | ConvertFrom-Json })
        eventOrder       = @($Result.Events | ForEach-Object { $_.name })
    }
}

function Get-MemoryCount {
    param([string]$File)
    $json = Get-Content -Path $File -Raw -Encoding UTF8
    if (-not $json.Trim()) { return 0 }
    # 不能写成 @($json | ConvertFrom-Json).Count：ConvertFrom-Json 把整个数组当成"一个"输出对象，@() 只会得到 1
    $parsed = $json | ConvertFrom-Json
    return @($parsed).Count
}

# ------------------------------------------------------------------ 主流程

Write-Host "== 流式聊天实测 =="
Write-Host ("baseUrl={0}  tenant={1}  user={2}  session={3}" -f $BaseUrl, $Tenant, $User, $Session)
Write-Host ("evidence={0}" -f $OutDir)

if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

Save-Snapshot 'before'

$summaries = New-Object System.Collections.Generic.List[object]
foreach ($round in $ROUNDS) {
    Write-Host ""
    Write-Host ("-- 第 {0} 轮：{1}" -f $round.No, $round.Question)
    Write-Host ("   预期：{0}" -f $round.Expect)
    $result = Invoke-ChatRound -Round $round.No -Question $round.Question
    $summary = Get-RoundSummary -Result $result
    $summaries.Add($summary)
    if ($round.No -eq 1) { Save-Snapshot 'after-round1' }
    Write-Host ("   HTTP {0} {1} / 总耗时 {2}ms / 事件 {3} 个" -f `
            $summary.status, $summary.contentType, $result.TotalMs, $result.Events.Count)
}

Save-Snapshot 'after-round2'

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("流式聊天轨实测耗时（session=$Session，model=$($summaries[0].provider)）")
$lines.Add('')
foreach ($s in $summaries) {
    $lines.Add(("第 {0} 轮：{1}" -f $s.round, $s.question))
    $lines.Add(("  meta 首帧                 {0} ms" -f $s.metaMs))
    $lines.Add(("  首个 reasoning（思维链）   {0} ms" -f $s.firstReasoningMs))
    $lines.Add(("  首个 delta（正文首字）     {0} ms" -f $s.firstDeltaMs))
    $lines.Add(("  完成（done）              {0} ms" -f $s.done.elapsedMs))
    $lines.Add(("  思维链 {0} 字符 / {1} 帧，正文 {2} 字符 / {3} 帧" -f `
                $s.reasoningChars, $s.reasoningEvents, $s.contentChars, $s.deltaEvents))
    $lines.Add(("  工具调用 {0} 次" -f $s.calls.Count))
    foreach ($c in $s.calls) {
        $lines.Add(("    - {0} [{1}] ok={2} {3}ms args={4}" -f $c.name, $c.type, $c.ok, $c.elapsedMs, $c.arguments))
    }
    $lines.Add(("  写回：{0}" -f ($s.writeback.message)))
    $lines.Add('')
}
$lines.Add('记忆条数变化')
$lines.Add(("  USER  ：before={0} → after-round1={1} → after-round2={2}" -f `
        (Get-MemoryCount (Join-Path $OutDir 'before\memories-user.json')),
        (Get-MemoryCount (Join-Path $OutDir 'after-round1\memories-user.json')),
        (Get-MemoryCount (Join-Path $OutDir 'after-round2\memories-user.json'))))
$lines.Add(("  AGENT ：before={0} → after-round1={1} → after-round2={2}" -f `
        (Get-MemoryCount (Join-Path $OutDir 'before\memories-agent.json')),
        (Get-MemoryCount (Join-Path $OutDir 'after-round1\memories-agent.json')),
        (Get-MemoryCount (Join-Path $OutDir 'after-round2\memories-agent.json'))))
Save-Utf8 (Join-Path $OutDir 'timing.txt') ($lines -join [Environment]::NewLine)

Save-Utf8 (Join-Path $OutDir 'summary.json') ($summaries | ConvertTo-Json -Depth 8)

Write-Host ""
Write-Host ($lines -join [Environment]::NewLine)
Write-Host ""
Write-Host ("证据已写入 {0}" -f $OutDir)