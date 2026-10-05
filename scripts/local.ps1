param(
    [ValidateSet('Start', 'Stop')][string]$Action = 'Start',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$logRoot = Join-Path $repoRoot 'logs'
$buildJar = Join-Path $repoRoot 'target/facturas-ocr-0.0.1-SNAPSHOT.jar'
$runtimeRoot = Join-Path $logRoot 'runtime'
New-Item -ItemType Directory -Force -Path $logRoot, $runtimeRoot | Out-Null

function Get-LocalServiceProcess([int]$port, [string]$name) {
    $listeners = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
    $record = Join-Path $logRoot "$name.pid"
    $ids = @($listeners | Select-Object -ExpandProperty OwningProcess)
    if (Test-Path -LiteralPath $record) {
        $recordedId = 0
        if ([int]::TryParse((Get-Content -LiteralPath $record -Raw).Trim(), [ref]$recordedId)) {
            $ids += $recordedId
        }
    }
    foreach ($serviceId in @($ids | Sort-Object -Unique)) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId = $serviceId"
        if (-not $process) { continue }
        $command = [string]$process.CommandLine
        $belongs = if ($name -eq 'app') {
            $process.Name -in @('java.exe', 'javaw.exe') -and (
                $command.Contains($runtimeRoot) -or $command.Contains($buildJar) -or
                $command -match '-jar\s+"?target[\\/]facturas-ocr-0\.0\.1-SNAPSHOT\.jar"?(?:\s|$)')
        } else {
            $process.Name -in @('python.exe', 'pythonw.exe') -and (
                $command.Contains((Join-Path $repoRoot 'ocr/service.py')) -or
                ((Test-Path -LiteralPath $record) -and $serviceId -eq $recordedId -and
                    $command -match '\bservice\.py(?:"|\s|$)'))
        }
        if (-not $belongs) { throw "El puerto $port o el PID guardado pertenece a otro proceso. No se detuvo: $serviceId." }
        $process
    }
}

function Stop-LocalService([int]$port, [string]$name) {
    foreach ($process in @(Get-LocalServiceProcess $port $name)) {
        if (Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue) {
            try { Stop-Process -Id $process.ProcessId -Force }
            catch {
                if (Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue) { throw }
            }
            Wait-Process -Id $process.ProcessId -Timeout 10 -ErrorAction SilentlyContinue
        }
        Write-Host "$name detenido (PID $($process.ProcessId))."
    }
    $record = Join-Path $logRoot "$name.pid"
    if (Test-Path -LiteralPath $record) { Remove-Item -LiteralPath $record }
}

function Get-OcrHealth {
    try { Invoke-RestMethod 'http://127.0.0.1:5000/health' -TimeoutSec 2 } catch { $null }
}

Push-Location $repoRoot
try {
    if ($Action -eq 'Stop') {
        Stop-LocalService 8080 'app'
        Stop-LocalService 5000 'ocr'
        return
    }

    # Legacy launchers execute target directly. Stop those instances before
    # Maven can replace their archive and break lazy class loading.
    foreach ($process in @(Get-LocalServiceProcess 8080 'app')) {
        if (-not $process.CommandLine.Contains($runtimeRoot)) {
            Stop-Process -Id $process.ProcessId -Force
            Wait-Process -Id $process.ProcessId -Timeout 10 -ErrorAction SilentlyContinue
        }
    }
    if (-not $SkipBuild) {
        Write-Host 'Compilando ProyectoFacturas...'
        & mvn.cmd package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo compilar la aplicación.' }
    }
    if (-not (Test-Path -LiteralPath $buildJar)) { throw 'No se encontró el JAR compilado.' }

    if (-not (Get-OcrHealth)) {
        $python = (Get-Command python.exe -ErrorAction Stop).Source
        $ocrScript = Join-Path $repoRoot 'ocr/service.py'
        $ocrProcess = Start-Process -FilePath $python -ArgumentList ('"' + $ocrScript + '"') `
            -WorkingDirectory (Join-Path $repoRoot 'ocr') -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $logRoot 'ocr.log') `
            -RedirectStandardError (Join-Path $logRoot 'ocr-error.log') -PassThru
        Set-Content -LiteralPath (Join-Path $logRoot 'ocr.pid') -Value $ocrProcess.Id
    }
    Write-Host 'Esperando los modelos OCR (la primera carga puede tardar)...'
    $ocrReady = $false
    for ($attempt = 0; $attempt -lt 180; $attempt++) {
        $health = Get-OcrHealth
        if ($health -and $health.ocrReady) { $ocrReady = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ocrReady) { throw 'El OCR no está listo. Revisá logs/ocr-error.log.' }

    $snapshotJar = Join-Path $runtimeRoot ('app-' + [guid]::NewGuid().ToString('N') + '.jar')
    Copy-Item -LiteralPath $buildJar -Destination $snapshotJar
    Stop-LocalService 8080 'app'
    # Java 17 on some Windows environments cannot connect AF_UNIX pipes.
    # A long temporary path selects the JDK's built-in TCP loopback fallback.
    $socketRoot = Join-Path $runtimeRoot 'java-internal-loopback-sockets-windows-temporary-directory'
    New-Item -ItemType Directory -Force -Path $socketRoot | Out-Null
    $java = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        Join-Path $env:JAVA_HOME 'bin/java.exe'
    } else { (Get-Command java.exe -ErrorAction Stop).Source }
    $arguments = '"-Djdk.net.unixdomain.tmpdir=' + $socketRoot + '" -jar "' + $snapshotJar + '"'
    $appProcess = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $repoRoot `
        -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logRoot 'app.log') `
        -RedirectStandardError (Join-Path $logRoot 'app-error.log') -PassThru
    Set-Content -LiteralPath (Join-Path $logRoot 'app.pid') -Value $appProcess.Id
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        try {
            $response = Invoke-WebRequest 'http://127.0.0.1:8080/' -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -eq 200) { Write-Host 'App lista: http://localhost:8080'; return }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw 'La aplicación no respondió. Revisá logs/app.log y logs/app-error.log.'
} finally { Pop-Location }
