function Get-ProjectPythonVersion([string]$Executable) {
    $output = & $Executable -I -c 'import json,sys; print(json.dumps(list(sys.version_info[:3])))' 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'El intérprete Python no respondió correctamente.' }
    try { return ($output | ConvertFrom-Json) } catch { throw 'No se pudo determinar la versión de Python.' }
}

function Resolve-ProjectPython([string]$Root, [string]$ExplicitPath = '') {
    $requested = if ($ExplicitPath) { $ExplicitPath } else { $env:APP_PYTHON }
    $candidates = if ($requested) { @($requested) } else {
        @((Join-Path $Root '.venv/Scripts/python.exe'), (Join-Path $Root '.venv/bin/python'))
        $command = Get-Command python.exe, python3, python -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($command) { $command.Source }
    }
    foreach ($candidate in $candidates) {
        $path = if ([IO.Path]::IsPathRooted($candidate)) { $candidate } else { Join-Path $Root $candidate }
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            if ($requested) { throw "No existe el Python indicado: $candidate" }
            continue
        }
        $version = @(Get-ProjectPythonVersion $path)
        if ($version.Count -ne 3 -or $version[0] -ne 3 -or $version[1] -ne 11) {
            throw "Se requiere Python 3.11. El intérprete encontrado usa $($version -join '.'): $path. Creá .venv con Python 3.11 o indicá -Python / APP_PYTHON."
        }
        return [IO.Path]::GetFullPath($path)
    }
    throw 'No se encontró Python 3.11. Creá .venv o configurá APP_PYTHON con su ruta completa.'
}
