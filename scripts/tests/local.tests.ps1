$ErrorActionPreference = 'Stop'
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$logRoot = Join-Path $repoRoot ('logs/runtime/launcher-test-' + [guid]::NewGuid().ToString('N'))
$runtimeRoot = Join-Path $logRoot 'runtime'
$portRecord = Join-Path $logRoot 'app.port'
$buildJar = Join-Path $repoRoot 'target/facturas-ocr-0.0.1-SNAPSHOT.jar'
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null

# Cargar solo las funciones; nunca ejecutar el arranque ni detener procesos reales.
$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $repoRoot 'scripts/local.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'El script de arranque tiene errores de sintaxis.' }
foreach ($functionName in @('Get-LocalServiceProcess', 'Resolve-LocalAppPort')) {
    $definition = $ast.Find({ param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -eq $functionName
    }, $true)
    . ([scriptblock]::Create($definition.Extent.Text))
}

$launcherProcesses = @{}
$launcherListeners = @{}
function Get-NetTCPConnection {
    param($LocalPort, $State, $ErrorAction)
    foreach ($serviceId in $launcherListeners[$LocalPort]) { [pscustomobject]@{ OwningProcess = $serviceId } }
}
function Get-CimInstance {
    param($ClassName, $Filter)
    $serviceId = [int]([regex]::Match($Filter, 'ProcessId = (\d+)').Groups[1].Value)
    $launcherProcesses[$serviceId]
}

$record = Join-Path $logRoot 'ocr.pid'
try {
    $launcherProcesses[1201] = [pscustomobject]@{
        ProcessId = 1201; Name = 'python.exe';
        CommandLine = 'python.exe "' + (Join-Path $repoRoot 'ocr/service.py') + '"'
    }
    Set-Content -LiteralPath $record -Value 1201
    $found = @(Get-LocalServiceProcess 5000 'ocr')
    if ($found.Count -ne 1 -or $found[0].ProcessId -ne 1201) {
        throw 'No se reconoció el OCR que aún no abrió el puerto.'
    }

    $launcherProcesses[1202] = [pscustomobject]@{
        ProcessId = 1202; Name = 'other.exe'; CommandLine = 'other.exe'
    }
    Set-Content -LiteralPath $record -Value 1202
    if (@(Get-LocalServiceProcess 5000 'ocr').Count -ne 0 -or (Test-Path -LiteralPath $record)) {
        throw 'El registro de un PID reutilizado no fue descartado.'
    }

    $launcherListeners[5000] = @(1202)
    $rejected = $false
    try { Get-LocalServiceProcess 5000 'ocr' | Out-Null }
    catch {
        if ($_.Exception.Message -notlike '*puerto 5000 pertenece a otro proceso*') { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw 'Se aceptó un proceso ajeno que ocupa el puerto OCR.' }
    if ((Resolve-LocalAppPort) -ne 8080) {
        throw 'El puerto predeterminado no es 8080.'
    }
    Set-Content -LiteralPath $portRecord -Value 8081
    if ((Resolve-LocalAppPort) -ne 8080) {
        throw 'Un puerto usado anteriormente cambió el predeterminado de arranque.'
    }
    if ((Resolve-LocalAppPort -UseSavedPort) -ne 8081) {
        throw 'El apagado no recuperó el puerto de la instancia en ejecución.'
    }
    # Otra aplicación Java (CMS) ocupa 8080. No debe identificarse como ProyectoFacturas.
    $launcherProcesses[42184] = [pscustomobject]@{
        ProcessId = 42184; Name = 'java.exe';
        CommandLine = 'java.exe -jar target\local-run\cms-0.0.1-SNAPSHOT.jar'
    }
    $launcherListeners[8080] = @(42184)
    $rejected = $false
    try { Get-LocalServiceProcess (Resolve-LocalAppPort) 'app' | Out-Null }
    catch {
        if ($_.Exception.Message -notlike '*puerto 8080 pertenece a otro proceso*') { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw 'No se rechazó un conflicto en el puerto predeterminado.' }

    $launcherProcesses[1203] = [pscustomobject]@{
        ProcessId = 1203; Name = 'java.exe';
        CommandLine = 'java.exe -jar "' + (Join-Path $runtimeRoot 'app-test.jar') + '" --server.port=8082'
    }
    $launcherListeners[8082] = @(1203)
    if (@(Get-LocalServiceProcess 8082 'app').Count -ne 1) {
        throw 'No se reconoció una instancia propia en el puerto alternativo.'
    }
    if ((Resolve-LocalAppPort 9000) -ne 9000) {
        throw 'No se respetó un puerto personalizado libre.'
    }
    Set-Content -LiteralPath $portRecord -Value 'inválido'
    if ((Resolve-LocalAppPort -UseSavedPort) -ne 8080) {
        throw 'Un registro inválido cambió el puerto predeterminado.'
    }
    Write-Host 'OK: OCR en arranque, PID reutilizado, protección de procesos ajenos y puerto 8080 predeterminado.'
} finally {
    if (Test-Path -LiteralPath $record) { Remove-Item -LiteralPath $record }
    if (Test-Path -LiteralPath $portRecord) { Remove-Item -LiteralPath $portRecord }
    Remove-Item -LiteralPath $logRoot
}
