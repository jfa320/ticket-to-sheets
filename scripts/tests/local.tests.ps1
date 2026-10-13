$ErrorActionPreference = 'Stop'
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$logRoot = Join-Path $repoRoot ('logs/runtime/launcher-test-' + [guid]::NewGuid().ToString('N'))
$runtimeRoot = Join-Path $logRoot 'runtime'
$buildJar = Join-Path $repoRoot 'target/facturas-ocr-0.0.1-SNAPSHOT.jar'
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null

# Cargar solo la búsqueda de procesos; nunca ejecutar el arranque ni detener procesos reales.
$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $repoRoot 'scripts/local.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'El script de arranque tiene errores de sintaxis.' }
$definition = $ast.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Get-LocalServiceProcess'
}, $true)
. ([scriptblock]::Create($definition.Extent.Text))

$launcherProcesses = @{}
$launcherListeners = @()
function Get-NetTCPConnection {
    param($LocalPort, $State, $ErrorAction)
    foreach ($serviceId in $launcherListeners) { [pscustomobject]@{ OwningProcess = $serviceId } }
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

    $launcherListeners = @(1202)
    $rejected = $false
    try { Get-LocalServiceProcess 5000 'ocr' | Out-Null }
    catch {
        if ($_.Exception.Message -notlike '*puerto 5000 pertenece a otro proceso*') { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw 'Se aceptó un proceso ajeno que ocupa el puerto OCR.' }
    Write-Host 'OK: OCR en arranque, PID reutilizado y puerto ocupado por otro proceso.'
} finally {
    if (Test-Path -LiteralPath $record) { Remove-Item -LiteralPath $record }
    Remove-Item -LiteralPath $logRoot
}
