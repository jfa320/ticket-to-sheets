param([string]$Python = '', [switch]$Docker, [switch]$Browser)
$ErrorActionPreference = 'Stop'
$checkRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'python-runtime.ps1')
$failures = [Collections.Generic.List[string]]::new()
Push-Location $checkRoot
try {
    try { $Python = Resolve-ProjectPython $checkRoot $Python }
    catch { $failures.Add('Python 3.11'); Write-Warning $_.Exception.Message; $Python = '' }
    function Invoke-Check([string]$Name, [string]$Command, [string[]]$Arguments) {
        Write-Host "Validando: $Name"
        try {
            if (-not $Command -or -not (Get-Command $Command -ErrorAction SilentlyContinue)) { throw "No se encontró el comando: $Command" }
            & $Command @Arguments
            if ($LASTEXITCODE -ne 0) { throw "Código de salida $LASTEXITCODE" }
            Write-Host "OK: $Name"
        } catch { $failures.Add($Name); Write-Warning "ERROR en $Name : $($_.Exception.Message)" }
    }
    Invoke-Check 'Java' 'mvn' @('test')
    $jsFiles = @(Get-ChildItem -LiteralPath src/test/js -Filter '*.test.mjs' | ForEach-Object { $_.FullName })
    if ($jsFiles.Count -eq 0) { $failures.Add('JavaScript sin pruebas') }
    else { Invoke-Check 'JavaScript' 'node' (@('--test') + $jsFiles) }
    Invoke-Check 'Python OCR e importación' $Python @('scripts/check_python.py')
    Invoke-Check 'Imports del contenedor OCR' $Python @('scripts/check_container_context.py')
    Invoke-Check 'Arranque local PowerShell' (Get-Process -Id $PID).Path @('-NoProfile', '-File', 'scripts/tests/local.tests.ps1')
    Invoke-Check 'Selección de Python' (Get-Process -Id $PID).Path @('-NoProfile', '-File', 'scripts/tests/python-runtime.tests.ps1')
    if ($Browser) {
        Invoke-Check 'Paquete para navegador' 'mvn' @('package', '-DskipTests')
        $previousPython = $env:APP_PYTHON
        try {
            $env:APP_PYTHON = $Python
            Invoke-Check 'Navegador E2E aislado' 'node' @('scripts/browser_e2e.mjs')
        } finally { $env:APP_PYTHON = $previousPython }
    } else { Write-Host 'Navegador: no solicitado. Agregá -Browser con Playwright y un navegador instalados.' }
    if ($Docker) {
        Invoke-Check 'Docker Compose' 'docker' @('compose', 'config', '--quiet')
        Invoke-Check 'Imágenes Docker' 'docker' @('compose', 'build')
    } else { Write-Host 'Docker: no solicitado. Agregá -Docker para validar configuración y construir imágenes.' }
    if ($failures.Count) { Write-Error ('Validación incompleta: ' + ($failures -join ', ')) }
    Write-Host 'Validación local completa: todas las suites pasaron sin omisiones.'
} finally { Pop-Location }
