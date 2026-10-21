<#
    Registra el agente para que arranque solo al iniciar sesion en Windows.

    Crea un acceso directo en la carpeta de Inicio del usuario: no hace falta
    ser administrador y se desinstala borrando ese acceso directo (o corriendo
    uninstall_autostart.ps1).

    El agente corre con pythonw.exe, o sea sin ventana de consola. El log va a
    %LOCALAPPDATA%\SPenPresenter\agent.log.
#>

$ErrorActionPreference = "Stop"

$agentDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pythonw  = Join-Path $agentDir ".venv\Scripts\pythonw.exe"
$launcher = Join-Path $agentDir "run_agent.pyw"
$startup  = [Environment]::GetFolderPath("Startup")
$shortcut = Join-Path $startup "S Pen Presenter.lnk"

if (-not (Test-Path $pythonw)) {
    Write-Error "No existe $pythonw. Crea el venv primero: python -m venv .venv; .\.venv\Scripts\python.exe -m pip install -r requirements.txt"
}

$shell = New-Object -ComObject WScript.Shell
$link  = $shell.CreateShortcut($shortcut)
$link.TargetPath       = $pythonw
$link.Arguments        = "`"$launcher`""
$link.WorkingDirectory = $agentDir
$link.Description      = "Agente de S Pen Presenter"
$link.Save()

Write-Host "Listo. El agente va a arrancar solo en el proximo inicio de sesion."
Write-Host "  Acceso directo: $shortcut"
Write-Host "  Log:            $env:LOCALAPPDATA\SPenPresenter\agent.log"
Write-Host ""
Write-Host "Para arrancarlo ahora sin reiniciar sesion:"
Write-Host "  Start-Process `"$pythonw`" -ArgumentList `"$launcher`""
