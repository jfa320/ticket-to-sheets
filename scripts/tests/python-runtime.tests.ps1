$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../python-runtime.ps1')
$testRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ('../../logs/runtime/python-test-' + [guid]::NewGuid().ToString('N'))))
New-Item -ItemType Directory -Path (Join-Path $testRoot '.venv/Scripts') -Force | Out-Null
$projectPython = Join-Path $testRoot '.venv/Scripts/python.exe'
$oldPython = Join-Path $testRoot 'old.exe'
New-Item -ItemType File -Path $projectPython, $oldPython | Out-Null
$previousSelection = $env:APP_PYTHON
function Get-ProjectPythonVersion([string]$Executable) { if ($Executable -eq $oldPython) { return @(3,10,4) }; return @(3,11,17) }
try {
    $env:APP_PYTHON = ''
    if ((Resolve-ProjectPython $testRoot) -ne $projectPython) { throw 'No se eligió .venv.' }
    $rejected = $false
    try { Resolve-ProjectPython $testRoot $oldPython | Out-Null } catch { $rejected = $_.Exception.Message -like '*3.11*' }
    if (-not $rejected) { throw 'Se aceptó una versión incompatible.' }
    $rejected = $false
    try { Resolve-ProjectPython $testRoot 'missing.exe' | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Una selección explícita inexistente cayó silenciosamente en otro intérprete.' }
    $env:APP_PYTHON = $oldPython
    $rejected = $false
    try { Resolve-ProjectPython $testRoot | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Se ignoró APP_PYTHON.' }
    if ((Resolve-ProjectPython $testRoot $projectPython) -ne $projectPython) { throw 'La selección explícita no tuvo prioridad.' }
    Write-Host 'OK: .venv, selección explícita, APP_PYTHON y rechazo de versiones incompatibles.'
} finally {
    $env:APP_PYTHON = $previousSelection
    # Borrar únicamente los archivos conocidos y las carpetas vacías creadas arriba.
    Remove-Item -LiteralPath $projectPython, $oldPython
    Remove-Item -LiteralPath (Join-Path $testRoot '.venv/Scripts'), (Join-Path $testRoot '.venv'), $testRoot
}
