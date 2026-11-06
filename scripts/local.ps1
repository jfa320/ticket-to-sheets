param(
    [ValidateSet('Start', 'Stop')][string]$Action = 'Start',
    [switch]$SkipBuild,
    [ValidateRange(1, 3600)][int]$OcrStartupTimeoutSec = 300,
    [string]$Python = '',
    [ValidateRange(1, 65535)][int]$Port = 8080,
    [switch]$OpenBrowser
)

$ErrorActionPreference = 'Stop'
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'python-runtime.ps1')
$logRoot = Join-Path $repoRoot 'logs'
$buildJar = Join-Path $repoRoot 'target/facturas-ocr-0.0.1-SNAPSHOT.jar'
$runtimeRoot = Join-Path $logRoot 'runtime'
$portRecord = Join-Path $logRoot 'app.port'
New-Item -ItemType Directory -Force -Path $logRoot, $runtimeRoot | Out-Null

function Get-LocalServiceProcess([int]$port, [string]$name) {
    $listeners = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
    $record = Join-Path $logRoot "$name.pid"
    $listeningIds = @($listeners | Select-Object -ExpandProperty OwningProcess)
    $ids = @($listeningIds)
    $recordedId = 0
    if (Test-Path -LiteralPath $record) {
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
        if (-not $belongs) {
            if ($listeningIds -contains $serviceId) {
                $hint = if ($name -eq 'app') { ' Para usar otro puerto: scripts/local.ps1 -Port 8081 -OpenBrowser.' } else { '' }
                throw "El puerto $port pertenece a otro proceso. No se detuvo: $serviceId.$hint"
            }
            # Después de un reinicio, Windows puede asignar el PID guardado a otro programa.
            # Descartar el registro obsoleto sin tocar ese proceso.
            if ($serviceId -eq $recordedId) { Remove-Item -LiteralPath $record }
            continue
        }
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

function Resolve-LocalAppPort([int]$requestedPort = 8080, [switch]$UseSavedPort) {
    if ($UseSavedPort -and (Test-Path -LiteralPath $portRecord)) {
        $savedPort = 0
        if ([int]::TryParse((Get-Content -LiteralPath $portRecord -Raw).Trim(), [ref]$savedPort) -and
            $savedPort -ge 1 -and $savedPort -le 65535) {
            return $savedPort
        }
    }
    return $requestedPort
}

function Get-OcrHealth {
    try { Invoke-RestMethod 'http://127.0.0.1:5000/health' -TimeoutSec 2 } catch { $null }
}

Push-Location $repoRoot
try {
    $explicitPort = $PSBoundParameters.ContainsKey('Port')
    $Port = Resolve-LocalAppPort $Port -UseSavedPort:($Action -eq 'Stop' -and -not $explicitPort)
    if ($Action -eq 'Stop') {
        Stop-LocalService $Port 'app'
        Stop-LocalService 5000 'ocr'
        return
    }

    # Legacy launchers execute target directly. Stop those instances before
    # Maven can replace their archive and break lazy class loading.
    foreach ($process in @(Get-LocalServiceProcess $Port 'app')) {
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

    $ocrProcess = $null
    # Un proceso recién lanzado puede estar importando Paddle antes de abrir el puerto.
    # Reutilizar su PID evita iniciar otra copia durante esa ventana.
    if (-not (Get-OcrHealth) -and @(Get-LocalServiceProcess 5000 'ocr').Count -eq 0) {
        $python = Resolve-ProjectPython $repoRoot $Python
        Write-Host "Python OCR: $python"
        $ocrScript = Join-Path $repoRoot 'ocr/service.py'
        $ocrProcess = Start-Process -FilePath $python -ArgumentList ('"' + $ocrScript + '"') `
            -WorkingDirectory (Join-Path $repoRoot 'ocr') -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $logRoot 'ocr.log') `
            -RedirectStandardError (Join-Path $logRoot 'ocr-error.log') -PassThru
        Set-Content -LiteralPath (Join-Path $logRoot 'ocr.pid') -Value $ocrProcess.Id
    }
    Write-Host 'Esperando los modelos OCR (la primera carga puede tardar)...'
    $ocrReady = $false
    $ocrWait = [System.Diagnostics.Stopwatch]::StartNew()
    while ($ocrWait.Elapsed.TotalSeconds -lt $OcrStartupTimeoutSec) {
        if ($ocrProcess -and $ocrProcess.HasExited) {
            throw 'El OCR terminó durante el arranque. Revisá logs/ocr-error.log y las dependencias Python.'
        }
        $health = Get-OcrHealth
        if ($health -and $health.ocrReady) { $ocrReady = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ocrReady) {
        throw "El OCR no estuvo listo en $OcrStartupTimeoutSec segundos. Revisá logs/ocr-error.log; si continúa descargando modelos, esperá y volvé a ejecutar el arranque. Podés ampliar la espera con -OcrStartupTimeoutSec."
    }

    $snapshotJar = Join-Path $runtimeRoot ('app-' + [guid]::NewGuid().ToString('N') + '.jar')
    Copy-Item -LiteralPath $buildJar -Destination $snapshotJar
    Stop-LocalService $Port 'app'
    # Java 17 on some Windows environments cannot connect AF_UNIX pipes.
    # A long temporary path selects the JDK's built-in TCP loopback fallback.
    $socketRoot = Join-Path $runtimeRoot 'java-internal-loopback-sockets-windows-temporary-directory'
    New-Item -ItemType Directory -Force -Path $socketRoot | Out-Null
    $java = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        Join-Path $env:JAVA_HOME 'bin/java.exe'
    } else { (Get-Command java.exe -ErrorAction Stop).Source }
    $arguments = '"-Djdk.net.unixdomain.tmpdir=' + $socketRoot + '" -jar "' + $snapshotJar + '" --server.port=' + $Port
    if (-not $env:APP_GOOGLE_REDIRECT_URI) {
        $arguments += ' --app.google.redirect-uri=http://localhost:' + $Port + '/api/sheets/oauth/callback'
    }
    $appProcess = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $repoRoot `
        -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logRoot 'app.log') `
        -RedirectStandardError (Join-Path $logRoot 'app-error.log') -PassThru
    Set-Content -LiteralPath (Join-Path $logRoot 'app.pid') -Value $appProcess.Id
    Set-Content -LiteralPath $portRecord -Value $Port
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($appProcess.HasExited) {
            throw 'La aplicación terminó durante el arranque. Revisá logs/app.log y logs/app-error.log.'
        }
        try {
            $response = Invoke-WebRequest "http://127.0.0.1:$Port/" -UseBasicParsing -TimeoutSec 2
        } catch { $response = $null }
        if ($response -and $response.StatusCode -eq 200) {
            $appUrl = "http://localhost:$Port"
            Write-Host "App lista: $appUrl"
            if ($OpenBrowser) { Start-Process $appUrl }
            return
        }
        Start-Sleep -Seconds 1
    }
    throw 'La aplicación no respondió. Revisá logs/app.log y logs/app-error.log.'
} finally { Pop-Location }
