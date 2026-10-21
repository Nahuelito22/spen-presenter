<#
    Empaqueta el agente en un unico .exe, para que alguien pueda usarlo sin
    instalar Python ni armar un venv.

    Genera dist\spen-presenter-agent.exe (consola: ahi se ven el QR, la IP y
    el codigo de emparejamiento).
#>

$ErrorActionPreference = "Stop"

$agentDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$python   = Join-Path $agentDir ".venv\Scripts\python.exe"

if (-not (Test-Path $python)) {
    Write-Error "No existe el venv. Corre: python -m venv .venv; .\.venv\Scripts\python.exe -m pip install -r requirements.txt"
}

& $python -m pip install --quiet pyinstaller

& $python -m PyInstaller `
    --onefile `
    --name spen-presenter-agent `
    --distpath (Join-Path $agentDir "dist") `
    --workpath (Join-Path $agentDir "build") `
    --specpath $agentDir `
    --collect-submodules zeroconf `
    --noconfirm `
    (Join-Path $agentDir "agent.py")

Write-Host ""
Write-Host "Listo: $(Join-Path $agentDir 'dist\spen-presenter-agent.exe')"
