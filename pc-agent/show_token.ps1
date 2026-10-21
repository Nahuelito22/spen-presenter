<#
    Muestra el codigo de emparejamiento que hay que cargar en la app.

    Hace falta porque con el arranque automatico el agente corre sin consola,
    asi que el codigo no se ve en ningun lado.
#>

$tokenFile = Join-Path $env:LOCALAPPDATA "SPenPresenter\token.txt"

if (-not (Test-Path $tokenFile)) {
    Write-Host "Todavia no hay codigo: arranca el agente una vez y volve a correr esto."
    exit 1
}

$token = (Get-Content $tokenFile -Raw).Trim()
Write-Host ""
Write-Host "  Codigo de emparejamiento: $token"
Write-Host ""
Write-Host "  Cargalo en la app, en el campo 'Codigo'. Se guarda y no hay que"
Write-Host "  volver a tipearlo."
Write-Host ""
Write-Host "  Para generar uno nuevo (y desconectar los telefonos ya emparejados):"
Write-Host "    Remove-Item '$tokenFile'   y reinicia el agente"
