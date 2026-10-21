<#
    Quita el arranque automatico del agente.
    No desinstala nada mas: el proyecto y el venv quedan intactos.
#>

$ErrorActionPreference = "Stop"

$shortcut = Join-Path ([Environment]::GetFolderPath("Startup")) "S Pen Presenter.lnk"

if (Test-Path $shortcut) {
    Remove-Item $shortcut -Force
    Write-Host "Arranque automatico desactivado ($shortcut borrado)."
} else {
    Write-Host "No estaba activado: no existe $shortcut"
}

$running = Get-NetTCPConnection -LocalPort 8765 -State Listen -ErrorAction SilentlyContinue
if ($running) {
    Write-Host "Ojo: el agente sigue corriendo (PID $($running.OwningProcess)). Para detenerlo ahora:"
    Write-Host "  Stop-Process -Id $($running.OwningProcess) -Force"
}
