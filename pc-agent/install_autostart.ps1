<#
    Registra el agente para que arranque solo al iniciar sesion en Windows.

    Crea un acceso directo en la carpeta de Inicio del usuario: no hace falta
    ser administrador y se desinstala borrando ese acceso directo (o corriendo
    uninstall_autostart.ps1).

    Usa el .exe empaquetado si existe; si no, el venv con pythonw.exe.
#>

$ErrorActionPreference = "Stop"

$agentDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$startup  = [Environment]::GetFolderPath("Startup")
$shortcut = Join-Path $startup "S Pen Presenter.lnk"

# Preferimos el .exe: no depende del venv ni de que Python siga instalado.
$exe      = Join-Path $agentDir "dist\spen-presenter-agent.exe"
$usingExe = Test-Path $exe

if ($usingExe) {
    $target    = $exe
    $arguments = ""
    # 7 = minimizada. El agente no necesita que lo miren, pero si algo falla
    # la ventana esta ahi en la barra de tareas.
    $style     = 7
    $runNow    = "Start-Process `"$exe`" -WindowStyle Minimized"
} else {
    $pythonw  = Join-Path $agentDir ".venv\Scripts\pythonw.exe"
    $launcher = Join-Path $agentDir "run_agent.pyw"

    if (-not (Test-Path $pythonw)) {
        Write-Error "No hay ni .exe ni venv. Corre build_exe.ps1, o crea el venv: python -m venv .venv; .\.venv\Scripts\python.exe -m pip install -r requirements.txt"
    }

    $target    = $pythonw
    $arguments = "`"$launcher`""
    # pythonw.exe no abre consola, asi que el estilo de ventana da igual.
    $style     = 1
    $runNow    = "Start-Process `"$pythonw`" -ArgumentList `"$launcher`""
}

$shell = New-Object -ComObject WScript.Shell
$link  = $shell.CreateShortcut($shortcut)
$link.TargetPath       = $target
$link.Arguments        = $arguments
$link.WorkingDirectory = $agentDir
$link.WindowStyle      = $style
$link.Description      = "Agente de S Pen Presenter"
$link.Save()

Write-Host "Listo. El agente va a arrancar solo en el proximo inicio de sesion."
if ($usingExe) {
    Write-Host "  Modo:           ejecutable empaquetado"
} else {
    Write-Host "  Modo:           venv + pythonw (sin consola)"
    Write-Host "  Log:            $env:LOCALAPPDATA\SPenPresenter\agent.log"
}
Write-Host "  Acceso directo: $shortcut"
Write-Host ""
Write-Host "Para arrancarlo ahora sin reiniciar sesion:"
Write-Host "  $runNow"
